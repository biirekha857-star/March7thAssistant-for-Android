#!/usr/bin/env python3
"""Pull the March7thAssistant arm64 image through a registry mirror.

Uses the NJU ghcr mirror (ghcr.nju.edu.cn), which is reachable and fast from
CN networks where a direct ghcr.io pull stalls.
"""
import json, os, sys, time, urllib.request, hashlib

MIRROR = os.environ.get("REG_MIRROR", "ghcr.nju.edu.cn")
REPO = os.environ.get("IMG_REPO", "moesnow/march7thassistant")
OUT = os.environ.get("IMG_OUT", r"D:\Temp\m7a-rootfs")
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
ACCEPT_IDX = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])


def http(url, headers=None, timeout=60):
    h = {"User-Agent": UA}
    h.update(headers or {})
    return urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout)


def get_json(url, accept):
    with http(url, {"Accept": accept}) as r:
        return json.load(r)


def download(url, dest, size, headers=None):
    """Resumable download with retries."""
    for attempt in range(1, 41):
        have = os.path.getsize(dest) if os.path.exists(dest) else 0
        if have >= size:
            return True
        h = {"User-Agent": UA}
        h.update(headers or {})
        if have:
            h["Range"] = f"bytes={have}-"
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=60) as r:
                mode = "ab" if (have and r.status == 206) else "wb"
                if mode == "wb":
                    have = 0
                with open(dest, mode) as f:
                    while True:
                        b = r.read(1 << 20)
                        if not b:
                            break
                        f.write(b)
        except Exception as e:
            if attempt % 5 == 0:
                got = os.path.getsize(dest) if os.path.exists(dest) else 0
                print(f"      retry {attempt}: {type(e).__name__} at {got/1048576:.1f}MB", flush=True)
            time.sleep(2)
            continue
        if os.path.getsize(dest) >= size:
            return True
    return os.path.getsize(dest) >= size


def main():
    os.makedirs(OUT, exist_ok=True)
    base = f"https://{MIRROR}/v2/{REPO}"
    print(f"mirror={MIRROR} repo={REPO}", flush=True)

    m = get_json(f"{base}/manifests/latest", ACCEPT_IDX)
    if "manifests" in m:
        pick = next((e for e in m["manifests"]
                     if e["platform"]["architecture"] == "arm64" and e["platform"]["os"] == "linux"), None)
        if pick is None:
            print("no linux/arm64 manifest", flush=True)
            return 1
        print(f"arm64 -> {pick['digest']}", flush=True)
        m = get_json(f"{base}/manifests/{pick['digest']}",
                     "application/vnd.oci.image.manifest.v1+json, "
                     "application/vnd.docker.distribution.manifest.v2+json")
    layers = m["layers"]
    total = sum(l["size"] for l in layers)
    print(f"layers={len(layers)} total={total/1048576:.0f} MB", flush=True)

    # config blob
    cfg = m.get("config")
    if cfg:
        cdest = os.path.join(OUT, "config.json")
        if not (os.path.exists(cdest) and os.path.getsize(cdest) == cfg["size"]):
            print("  config", flush=True)
            download(f"{base}/blobs/{cfg['digest']}", cdest, cfg["size"],
                     {"Accept": cfg["mediaType"]})

    for i, l in enumerate(layers):
        dest = os.path.join(OUT, f"layer{i:02d}.tar.gz")
        if os.path.exists(dest) and os.path.getsize(dest) == l["size"]:
            print(f"  [{i:02d}] cached ({l['size']/1048576:.1f} MB)", flush=True)
            continue
        print(f"  [{i:02d}] {l['size']/1048576:.1f} MB", flush=True)
        t0 = time.time()
        ok = download(f"{base}/blobs/{l['digest']}", dest, l["size"],
                      {"Accept": l["mediaType"]})
        got = os.path.getsize(dest) if os.path.exists(dest) else 0
        spd = got / max(time.time() - t0, 0.01) / 1048576
        print(f"  [{i:02d}] {'OK' if ok else 'INCOMPLETE'} {got/1048576:.1f} MB "
              f"in {time.time()-t0:.0f}s ({spd:.1f} MB/s)", flush=True)
        if not ok:
            return 1
    print("ALL LAYERS OK", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
