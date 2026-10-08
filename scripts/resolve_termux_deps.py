#!/usr/bin/env python3
"""Resolve the full dependency closure of proot-distro in the Termux repo."""
import bz2, re, sys, collections, urllib.request, os, hashlib

MIRROR = "https://packages-cf.termux.dev/apt/termux-main"
ARCH = "aarch64"
CACHE = r"D:\Temp\termux-debs"

data = bz2.open(r"D:\Temp\Packages.bz2", "rt", encoding="utf-8", errors="replace").read()
pkgs = {}
for p in data.split("\n\n"):
    d = {}
    for line in p.splitlines():
        if ": " in line:
            k, v = line.split(": ", 1)
            d.setdefault(k, []).append(v)
    name = d.get("Package", [None])[0]
    if name:
        pkgs[name] = {
            "version": d.get("Version", ["?"])[0],
            "arch": d.get("Architecture", ["?"])[0],
            "depends": d.get("Depends", []),
            "filename": d.get("Filename", [None])[0],
            "size": int(d.get("Size", ["0"])[0]),
            "sha256": d.get("SHA256", [None])[0],
            "essential": d.get("Essential", ["no"])[0] == "yes",
        }

print(f"repo packages: {len(pkgs)}")


def parse_deps(spec_list):
    """Parse Depends field into candidate name lists (handles alternatives & versions)."""
    out = []
    for spec in spec_list:
        for part in spec.split(","):
            part = part.strip()
            if not part:
                continue
            alts = [a.strip() for a in part.split("|")]
            names = []
            for a in alts:
                n = re.split(r"[\s(]", a)[0].strip()
                if n:
                    names.append(n)
            if names:
                out.append(names)
    return out


ROOTS = ["proot-distro"]

seen = {}
order = []
queue = collections.deque(ROOTS)

# virtual/essential packages provided by the bootstrap itself -> skip
PROVIDED = {
    "sh", "bash", "coreutils", "dash", "libc", "shell", "awk", "sed", "grep",
    "findutils", "diffutils", "gzip", "tar", "bzip2", "xz-utils", "zlib",
    "libandroid-support", "termux-am", "termux-core", "termux-exec",
    "dpkg", "apt", "ca-certificates", "openssl", "libcurl", "libssl",
    "ncurses", "ncurses-utils", "readline", "libreadline",
}
PROVIDED = {p.lower() for p in PROVIDED}

missing = []
while queue:
    name = queue.popleft()
    if name in seen:
        continue
    if name.lower() in PROVIDED:
        seen[name] = "PROVIDED-BY-BOOTSTRAP"
        continue
    info = pkgs.get(name)
    if info is None:
        missing.append(name)
        seen[name] = "MISSING"
        continue
    if info["arch"] not in ("all", ARCH):
        seen[name] = f"WRONG-ARCH:{info['arch']}"
        continue
    seen[name] = info
    order.append(name)
    for alts in parse_deps(info["depends"]):
        # pick the first alternative that exists in the repo
        pick = next((a for a in alts if a in pkgs), None)
        if pick is None:
            for a in alts:
                if a.lower() not in PROVIDED:
                    missing.append(a)
        elif pick not in seen:
            queue.append(pick)

print(f"\nclosure: {len(order)} real packages")
print(f"missing (not in repo): {sorted(set(missing))}")

total = 0
os.makedirs(CACHE, exist_ok=True)
for name in sorted(order):
    i = seen[name]
    total += i["size"]
    print(f"  {name:36s} {i['version']:24s} {i['size']/1024:9.1f} KB  {i['filename']}")
print(f"\nTOTAL download: {total/1024/1024:.2f} MB")

with open(r"D:\Temp\termux-pkgs.txt", "w") as f:
    for name in sorted(order):
        i = seen[name]
        f.write(f"{name}\t{i['version']}\t{i['filename']}\t{i['size']}\t{i['sha256']}\n")
print("wrote D:\\Temp\\termux-pkgs.txt")
