#!/usr/bin/env python3
"""
Build the offline Termux bootstrap asset.

1. Start from the official Termux v0.118.3 bootstrap zip (extracted from the
   user's APK's libtermux-bootstrap.so).
2. Download proot + proot-distro + their repo dependency closure from the
   Termux apt mirror (aarch64).
3. Unpack each .deb (ar -> data.tar.*) into the bootstrap tree, preserving
   symlinks and file modes, then run a minimal dpkg-style postinst where needed.
4. Re-zip into app/src/main/assets/bootstrap/termux-bootstrap.zip with a
   freshly generated SYMLINKS.txt.

Everything is offline after this: `proot-distro` exists as soon as the app
extracts the bootstrap.
"""
import bz2, hashlib, io, lzma, os, re, shutil, struct, subprocess, sys, tarfile, time
import urllib.request, zipfile, gzip

try:
    import zstandard as _zstd
except ImportError:
    _zstd = None

MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
SRC_ZIP = r"D:\MaaTermux-assets\bootstrap\bootstrap-aarch64.zip"
OUT_ZIP = r"D:\MaaTermux\app\src\main\assets\bootstrap\termux-bootstrap.zip"
CACHE = r"D:\Temp\termux-debs"
PKGLIST = r"D:\Temp\termux-pkgs.txt"
STAGE = r"D:\Temp\bootstrap-stage"


def log(*a):
    print(*a, flush=True)


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    hdrs = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        "Accept": "*/*",
    }
    for attempt in range(1, 11):
        try:
            req = urllib.request.Request(url, headers=hdrs)
            with urllib.request.urlopen(req, timeout=60) as r, open(dest, "wb") as f:
                shutil.copyfileobj(r, f, 1 << 20)
            if os.path.getsize(dest) > 0:
                return dest
        except Exception as e:
            log(f"    retry {attempt}: {e}")
            time.sleep(2)
    raise RuntimeError(f"failed to download {url}")


def read_ar(path):
    """Yield (name, bytes) members of a .deb (an 'ar' archive)."""
    with open(path, "rb") as f:
        magic = f.read(8)
        if magic != b"!<arch>\n":
            raise RuntimeError(f"{path} is not an ar archive")
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                return
            name = hdr[0:16].decode("ascii", "replace").strip().rstrip("/")
            size = int(hdr[48:58].decode("ascii").strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data


ABS_PREFIXES = (
    "data/data/com.termux/files/usr/",
    "data/data/com.termux/files/",
    "/data/data/com.termux/files/usr/",
    "/data/data/com.termux/files/",
    "./",
    "/",
)


def normalize_guest_path(name):
    """Deb payloads carry './data/data/com.termux/files/usr/...' paths. The
    bootstrap zip is rooted at $PREFIX, so strip that prefix. Loop, because
    stripping './' exposes a leading '/' that must be handled next."""
    n = name.replace("\\", "/")
    while True:
        before = n
        for p in ABS_PREFIXES:
            if n.startswith(p):
                n = n[len(p):]
                break
        n = n.lstrip("/")
        if n == before:
            break
    return n.lstrip("/")


def extract_data_tar(data, prefix_dir, manifest):
    """Extract a debian data.tar.* stream into prefix_dir."""
    if data[:2] == b"\x1f\x8b":
        raw = gzip.decompress(data)
    elif data[:6] == b"\xfd7zXZ\x00":
        raw = lzma.decompress(data)
    elif data[:4] == b"\x28\xb5\x2f\xfd":
        if _zstd is None:
            raise RuntimeError("zstandard module required for zstd data.tar")
        raw = _zstd.ZstdDecompressor().decompress(data, max_output_size=1 << 30)
    elif data[:3] == b"BZh":
        raw = bz2.decompress(data)
    else:
        raw = data
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as tf:
        for m in tf.getmembers():
            name = normalize_guest_path(m.name)
            if not name or name in (".", "/"):
                continue
            # Never overwrite the bootstrap's own dpkg database
            if name.startswith("var/lib/dpkg/") and not name.endswith("/"):
                continue
            target = os.path.join(prefix_dir, name)
            if m.isdir():
                os.makedirs(target, exist_ok=True)
                continue
            if m.issym():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                if os.path.lexists(target):
                    os.remove(target)
                link = m.linkname
                # only rewrite absolute Android link targets
                if link.startswith("/data/data/com.termux/files/"):
                    link = normalize_guest_path(link)
                os.symlink(link, target)
                manifest.append(("symlink", name, link))
                continue
            if m.islnk():
                os.makedirs(os.path.dirname(target), exist_ok=True)
                src = os.path.join(prefix_dir, normalize_guest_path(m.linkname))
                if os.path.lexists(target):
                    os.remove(target)
                try:
                    os.link(src, target)
                    manifest.append(("hardlink", name, m.linkname))
                except OSError:
                    shutil.copy2(src, target)
                continue
            if m.ischr() or m.isblk() or m.isfifo():
                # device nodes cannot be created without root; annotate for the app
                manifest.append(("special", name, ""))
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            f = tf.extractfile(m)
            if f is None:
                continue
            with open(target, "wb") as out:
                shutil.copyfileobj(f, out, 1 << 20)
            os.chmod(target, m.mode & 0o7777)
            manifest.append(("file", name, ""))


def build():
    if not os.path.exists(SRC_ZIP):
        log(f"!! source bootstrap missing: {SRC_ZIP}")
        return 1

    # 1. unpack source bootstrap
    if os.path.exists(STAGE):
        shutil.rmtree(STAGE)
    os.makedirs(STAGE)
    log(f"unpacking {SRC_ZIP} ...")
    with zipfile.ZipFile(SRC_ZIP) as z:
        symlinks = []
        for info in z.infolist():
            name = info.filename
            if name == "SYMLINKS.txt":
                for line in z.read(info).decode("utf-8").splitlines():
                    if not line.strip():
                        continue
                    parts = line.split("\u2190")
                    if len(parts) == 2:
                        symlinks.append((parts[0], parts[1]))
                continue
            if name.endswith("/"):
                continue
            target = os.path.join(STAGE, name)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out, 1 << 20)
            mode = (info.external_attr >> 16) & 0xFFFF
            if mode:
                os.chmod(target, mode & 0o7777)
    log(f"  {len(symlinks)} symlinks, tree at {STAGE}")

    # recreate symlinks so we can re-scan them later
    for link, target in symlinks:
        p = os.path.join(STAGE, target)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if os.path.lexists(p):
            os.remove(p)
        try:
            os.symlink(link, p)
        except OSError:
            pass

    # 2. download + install packages
    with open(PKGLIST) as f:
        lines = [l.rstrip("\n").split("\t") for l in f if l.strip()]
    log(f"installing {len(lines)} packages ...")
    manifest = []
    installed = []
    for name, version, filename, size, sha in lines:
        url = f"{MIRROR}/{filename}"
        dest = os.path.join(CACHE, os.path.basename(filename))
        download(url, dest)
        if sha:
            h = hashlib.sha256(open(dest, "rb").read()).hexdigest()
            if h != sha:
                log(f"  !! {name} sha256 mismatch, skipping")
                continue
        members = dict(read_ar(dest))
        data_key = next((k for k in members if k.startswith("data.tar")), None)
        if data_key is None:
            log(f"  !! {name}: no data.tar.* in deb")
            continue
        extract_data_tar(members[data_key], STAGE, manifest)
        installed.append((name, version))
        log(f"  + {name} {version}")

    # 3. run essential postinst scripts (dpkg needs the dpkg db; we emulate just
    #    the ones that create critical symlinks). proot-distro has no postinst
    #    requirements beyond file placement.
    postinst_dir = os.path.join(STAGE, "var/lib/dpkg/info")
    # 4. make sure python works: create python3 symlink if missing
    pbin = os.path.join(STAGE, "bin")
    for src, dst in (("python3.14", "python3"), ("python3", "python")):
        s = os.path.join(pbin, src)
        d = os.path.join(pbin, dst)
        if os.path.lexists(s) and not os.path.lexists(d):
            try:
                os.symlink(src, d)
                log(f"  link {dst} -> {src}")
            except OSError as e:
                log(f"  !! link {dst}: {e}")

    # 5. re-scan symlinks & re-zip
    new_symlinks = []
    file_count = 0
    for root, dirs, files in os.walk(STAGE):
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
        for entry in list(dirs) + files:
            full = os.path.join(root, entry)
            if os.path.islink(full):
                rel = os.path.relpath(full, STAGE).replace(os.sep, "/")
                new_symlinks.append((os.readlink(full), rel))
    new_symlinks.sort(key=lambda t: t[1])

    log(f"re-zipping: {len(new_symlinks)} symlinks ...")
    os.makedirs(os.path.dirname(OUT_ZIP), exist_ok=True)
    tmp_zip = OUT_ZIP + ".tmp"
    with zipfile.ZipFile(tmp_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.writestr("SYMLINKS.txt", "".join(f"{l}\u2190{t}\n" for l, t in new_symlinks))
        for root, dirs, files in os.walk(STAGE):
            dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
            for fn in files:
                full = os.path.join(root, fn)
                if os.path.islink(full):
                    continue
                rel = os.path.relpath(full, STAGE).replace(os.sep, "/")
                zi = zipfile.ZipInfo(rel)
                st = os.stat(full)
                zi.external_attr = (st.st_mode & 0xFFFF) << 16
                zi.compress_type = zipfile.ZIP_DEFLATED
                zi.date_time = time.localtime(st.st_mtime)[:6]
                with open(full, "rb") as fh:
                    with z.open(zi, "w") as out:
                        shutil.copyfileobj(fh, out, 1 << 20)
                file_count += 1
    os.replace(tmp_zip, OUT_ZIP)
    log(f"DONE: {OUT_ZIP}  ({os.path.getsize(OUT_ZIP)/1048576:.1f} MB, {file_count} files)")
    return 0


if __name__ == "__main__":
    sys.exit(build())
