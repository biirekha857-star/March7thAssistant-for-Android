#!/usr/bin/env python3
"""
Targeted executable-bit audit of the packed rootfs.

Most files legitimately have no execute bit; what matters is that files in the
directories that hold executables are runnable. Also reports which paths had to
fall back to the default mode (those are the risky ones).
"""
import json, os, tarfile, time

MINE = r"D:\MaaTermux\app\src\main\assets\rootfs\m7a-rootfs.bin"
MODE_MAP = r"D:\Temp\rootfs-modes.json"

# directories whose contents are expected to be executable
EXEC_DIRS = (
    "usr/bin/", "usr/sbin/", "usr/local/bin/", "usr/local/sbin/",
    "bin/", "sbin/", "opt/venv/bin/", "usr/libexec/",
)

# a file here is only exec if it is a real ELF/script; .py/.so may legitimately
# lack +x. These are the ones that MUST be executable.
MUST_EXEC = {
    "usr/bin/bash", "usr/bin/sh", "usr/bin/dash", "usr/bin/env", "usr/bin/ls",
    "usr/bin/cat", "usr/bin/chmod", "usr/bin/chown", "usr/bin/mkdir",
    "usr/bin/rm", "usr/bin/ln", "usr/bin/cp", "usr/bin/mv", "usr/bin/touch",
    "usr/bin/grep", "usr/bin/sed", "usr/bin/awk", "usr/bin/find", "usr/bin/xargs",
    "usr/bin/tar", "usr/bin/gzip", "usr/bin/uname", "usr/bin/id", "usr/bin/date",
    "usr/bin/head", "usr/bin/tail", "usr/bin/wc", "usr/bin/cut", "usr/bin/sort",
    "usr/bin/tr", "usr/bin/dirname", "usr/bin/basename", "usr/bin/readlink",
    "usr/bin/realpath", "usr/bin/which", "usr/bin/apt", "usr/bin/apt-get",
    "usr/bin/dpkg", "usr/bin/python3", "usr/bin/chromium", "usr/bin/chromedriver",
    "opt/venv/bin/python", "opt/venv/bin/python3", "opt/venv/bin/pip",
}

print("=== targeted audit of packed rootfs ===")
t0 = time.time()
modes = {}
n = 0
with tarfile.open(MINE, "r|gz") as tf:
    for m in tf:
        mode = m.mode & 0o7777
        modes[m.name.lstrip("./")] = (mode, m.issym(), m.linkname if m.issym() else "")
        n += 1
print(f"scanned {n} entries in {time.time()-t0:.0f}s")

print("\n--- MUST be executable ---")
bad = []
for p in sorted(MUST_EXEC):
    e = modes.get(p)
    if e is None:
        print(f"  MISSING   {p}")
        bad.append(p)
        continue
    mode, is_sym, target = e
    if is_sym:
        # symlink: check the final target
        resolved = modes.get(target if target.startswith(("usr/", "opt/", "bin/")) else p)
        print(f"  SYMLINK   {p:28s} -> {target}")
        continue
    ok = bool(mode & 0o111)
    if not ok:
        bad.append(p)
    print(f"  {'OK ' if ok else 'BAD'}       {p:28s} {oct(mode)}")

print("\n--- executables in exec dirs that lack +x (excluding libs/scripts) ---")
problem = []
for path, (mode, is_sym, target) in modes.items():
    if is_sym or not path.startswith(EXEC_DIRS):
        continue
    if mode & 0o111:
        continue
    ext = os.path.splitext(path)[1]
    if ext in (".so", ".py", ".pyc", ".h", ".json", ".txt", ".md", ".1", ".gz",
               ".cfg", ".ini", ".pth", ".dist-info", ".cmake", ".pc", ".a", ".whl"):
        continue
    if ".dist-info/" in path or "/site-packages/" in path or "/share/" in path:
        continue
    problem.append((path, mode))

print(f"  count: {len(problem)}")
for p, m in sorted(problem)[:40]:
    print(f"    {p:60s} {oct(m)}")

print("\n--- paths that had no mode recorded (defaulted to 0644) ---")
if os.path.exists(MODE_MAP):
    recorded = json.load(open(MODE_MAP))
else:
    recorded = {}
unrecorded = [p for p in modes if p not in recorded and not modes[p][1]]
print(f"  count: {len(unrecorded)}")
for p in sorted(unrecorded)[:40]:
    print(f"    {p:60s} {oct(modes[p][0])}")

print()
if bad or problem:
    print(f"RESULT: needs attention  must_exec_bad={bad}  exec_dir_problems={len(problem)}")
else:
    print("RESULT: PASS - all must-exec binaries have the execute bit")
