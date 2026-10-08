#!/usr/bin/env python3
"""
Assemble an OCI image (downloaded layers) into a proot-distro rootfs tarball.

CRITICAL LESSON (this bit me):
    The packing step runs on Windows. `os.stat()` on Windows has NO execute
    bits, and `tarfile.add()` copies `st_mode` verbatim into the archive. So a
    naive repack silently turns every 0755 into 0666, and the resulting rootfs
    has a non-executable /bin/bash -- proot then fails with:
        proot error: '/bin/bash' is not executable
    Therefore modes are harvested from the ORIGINAL layer tar metadata and
    re-applied explicitly when packing. Never trust os.stat() here.

Usage:
    python assemble_rootfs.py            # extract layers (if needed) + pack
    python assemble_rootfs.py --pack     # re-pack from existing stage, reusing
                                         # the mode map harvested from layers
"""
import gzip, json, os, shutil, stat, sys, tarfile, time

SRC = r"D:\Temp\m7a-rootfs"
STAGE = r"D:\Temp\rootfs-stage"
MODE_MAP = r"D:\Temp\rootfs-modes.json"     # deliberately OUTSIDE stage
MARKER = r"D:\Temp\rootfs-extract.done"     # deliberately OUTSIDE stage
OUT = r"D:\MaaTermux\app\src\main\assets\rootfs\m7a-rootfs.bin"

DEFAULT_FILE_MODE = 0o644
DEFAULT_DIR_MODE = 0o755
DEFAULT_LINK_MODE = 0o777


def log(*a):
    print(*a, flush=True)


def norm(name):
    """Normalise a tar member name to a plain relative path.

    Do NOT use str.lstrip('./'): that strips a leading dot from dotfiles too,
    turning '.bashrc' into 'bashrc'.
    """
    n = name.replace("\\", "/")
    while True:
        if n.startswith("./"):
            n = n[2:]
        elif n.startswith("/"):
            n = n[1:]
        else:
            break
    return n


def is_whiteout(base):
    return base == ".wh..wh..opq" or base.startswith(".wh.")


def apply_whiteout(name, modes):
    """Mirror OCI whiteouts onto the mode map."""
    base = os.path.basename(name)
    parent = os.path.dirname(name)
    if base == ".wh..wh..opq":
        prefix = (parent + "/") if parent else ""
        for k in [k for k in modes if k.startswith(prefix) and k != parent]:
            modes.pop(k, None)
        return True
    if base.startswith(".wh."):
        victim = os.path.join(parent, base[4:]) if parent else base[4:]
        for k in [k for k in modes if k == victim or k.startswith(victim + "/")]:
            modes.pop(k, None)
        return True
    return False


def harvest_modes(layers):
    """Read every layer's tar metadata (no extraction) to build path -> mode."""
    modes = {}
    for i, lf in enumerate(layers):
        t0 = time.time()
        n = 0
        with tarfile.open(lf, "r|gz") as tf:
            for m in tf:
                name = norm(m.name)
                if not name:
                    continue
                if apply_whiteout(name, modes):
                    continue
                if m.issym():
                    modes[name] = ("link", m.linkname)
                elif m.isdir():
                    modes[name] = ("dir", m.mode & 0o7777)
                elif m.isfile():
                    modes[name] = ("file", m.mode & 0o7777)
                elif m.islnk():
                    modes[name] = ("hardlink", m.linkname)
                n += 1
        log(f"  harvested layer{i:02d}: {n} members in {time.time()-t0:.0f}s "
            f"(map size {len(modes)})")
    return modes


def extract_layer(path, root, index):
    t0 = time.time()
    n_files = n_dirs = n_links = 0
    with tarfile.open(path, "r|gz") as tf:
        for m in tf:
            name = norm(m.name)
            if not name:
                continue
            base = os.path.basename(name)
            parent = os.path.dirname(name)
            if base == ".wh..wh..opq":
                d = os.path.join(root, parent)
                if os.path.isdir(d):
                    for e in os.listdir(d):
                        p = os.path.join(d, e)
                        if os.path.isdir(p) and not os.path.islink(p):
                            shutil.rmtree(p, ignore_errors=True)
                        else:
                            try:
                                os.remove(p)
                            except OSError:
                                pass
                continue
            if base.startswith(".wh."):
                victim = os.path.join(root, parent, base[4:])
                if os.path.isdir(victim) and not os.path.islink(victim):
                    shutil.rmtree(victim, ignore_errors=True)
                elif os.path.lexists(victim):
                    try:
                        os.remove(victim)
                    except OSError:
                        pass
                continue

            target = os.path.join(root, name)
            try:
                if m.isdir():
                    os.makedirs(target, exist_ok=True)
                    n_dirs += 1
                elif m.issym():
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if os.path.lexists(target):
                        os.remove(target)
                    os.symlink(m.linkname, target)
                    n_links += 1
                elif m.islnk():
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    srcp = os.path.join(root, norm(m.linkname))
                    if os.path.lexists(target):
                        os.remove(target)
                    try:
                        os.link(srcp, target)
                    except OSError:
                        shutil.copy2(srcp, target)
                    n_links += 1
                elif m.ischr() or m.isblk() or m.isfifo():
                    continue
                else:
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    f = tf.extractfile(m)
                    if f is None:
                        continue
                    with open(target, "wb") as out:
                        shutil.copyfileobj(f, out, 1 << 20)
                    n_files += 1
            except (OSError, IOError) as e:
                log(f"      warn: {name}: {e}")
    log(f"  layer{index:02d}: {n_files} files, {n_dirs} dirs, {n_links} links, "
        f"{time.time()-t0:.0f}s")


def pack(modes, entries_override=None):
    """Pack STAGE into OUT, forcing modes from `modes`."""
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = OUT + ".tmp"
    log(f"packing -> {tmp}")

    # remove our own bookkeeping file if it ever landed in the tree
    stray = os.path.join(STAGE, ".done-extract")
    if os.path.exists(stray):
        os.remove(stray)

    t0 = time.time()
    count = 0
    missing_modes = 0
    exec_fixed = 0

    with tarfile.open(tmp, "w:gz", compresslevel=6) as out:
        # collect entries; dirs first so extraction creates parents early
        dirs, others = [], []
        for root, dnames, fnames in os.walk(STAGE):
            dnames.sort()
            fnames.sort()
            for d in dnames:
                full = os.path.join(root, d)
                rel = os.path.relpath(full, STAGE).replace(os.sep, "/")
                if os.path.islink(full):
                    others.append((full, rel))
                else:
                    dirs.append((full, rel))
            for f in fnames:
                full = os.path.join(root, f)
                rel = os.path.relpath(full, STAGE).replace(os.sep, "/")
                others.append((full, rel))

        for full, rel in sorted(dirs, key=lambda x: x[1]) + sorted(others, key=lambda x: x[1]):
            try:
                ti = out.gettarinfo(full, arcname=rel)
            except (OSError, IOError) as e:
                log(f"    warn stat: {rel}: {e}")
                continue
            if ti is None:
                continue

            rec = modes.get(rel)
            if ti.issym() or ti.islnk():
                ti.mode = DEFAULT_LINK_MODE
            elif ti.isdir():
                ti.mode = (rec[1] if rec and rec[0] == "dir" else DEFAULT_DIR_MODE)
            else:
                if rec and rec[0] == "file":
                    ti.mode = rec[1]
                elif rec is None:
                    missing_modes += 1
                    ti.mode = DEFAULT_FILE_MODE
                else:
                    ti.mode = DEFAULT_FILE_MODE
                # sanity: keep at least one execute bit if any was recorded
                if rec and (rec[1] & 0o111):
                    exec_fixed += 1

            # normalise ownership
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"

            try:
                if ti.isreg():
                    with open(full, "rb") as fh:
                        out.addfile(ti, fh)
                else:
                    out.addfile(ti)
                count += 1
            except (OSError, IOError) as e:
                log(f"    warn add: {rel}: {e}")
            if count % 20000 == 0:
                log(f"    {count} entries, {time.time()-t0:.0f}s")

    os.replace(tmp, OUT)
    log(f"DONE: {OUT}  ({os.path.getsize(OUT)/1048576:.1f} MB, {count} entries, "
        f"{time.time()-t0:.0f}s)")
    log(f"  entries with execute bit restored: {exec_fixed}")
    log(f"  entries with no recorded mode (defaulted): {missing_modes}")
    return 0


def main():
    pack_only = "--pack" in sys.argv
    if not os.path.isdir(SRC):
        log(f"missing {SRC}")
        return 1
    layers = sorted(f for f in os.listdir(SRC)
                    if f.startswith("layer") and f.endswith(".tar.gz"))
    layers = [os.path.join(SRC, f) for f in layers]

    # ---- modes ----
    if os.path.exists(MODE_MAP):
        log(f"loading mode map from {MODE_MAP}")
        raw = json.load(open(MODE_MAP))
        modes = {k: tuple(v) for k, v in raw.items()}
    else:
        log(f"harvesting modes from {len(layers)} layers")
        modes = harvest_modes(layers)
        json.dump({k: list(v) for k, v in modes.items()}, open(MODE_MAP, "w"))
        log(f"saved mode map ({len(modes)} entries) -> {MODE_MAP}")

    # ---- extract ----
    if not os.path.exists(MARKER):
        if os.path.exists(STAGE):
            shutil.rmtree(STAGE, ignore_errors=True)
        os.makedirs(STAGE)
        for i, lf in enumerate(layers):
            log(f"  [{i:02d}] {os.path.basename(lf)}")
            extract_layer(lf, STAGE, i)
        open(MARKER, "w").close()
    else:
        log("extraction already done (marker present)")

    # ---- sanity ----
    for p in ("bin", "usr", "etc", "opt", "m7a", "usr/bin/bash"):
        log(f"  {p:14s} {'OK' if os.path.exists(os.path.join(STAGE, p)) else 'MISSING'}")

    return pack(modes)


if __name__ == "__main__":
    sys.exit(main())
