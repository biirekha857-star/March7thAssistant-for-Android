#!/usr/bin/env python3
"""把 Termux bootstrap 里的 `$PREFIX` 前缀替换成另一个包名。

## 为什么这件事能安全地做

Termux 的二进制把 `/data/data/<包名>/files/usr` **编译进了 ELF**：

- 该字符串位于 `.dynstr`，`DT_RUNPATH` 指向它，动态链接器据此找 `.so`
- 脚本的 shebang（`#!/data/data/<包名>/files/usr/bin/python3.14`）是绝对路径
- `var/lib/dpkg/info/*.list` 与 `*.md5sums` 记录了每个文件的绝对路径

`.dynstr` 是一张以 `\\0` 分隔的连续字符串表，所有动态符号按**偏移量**引用。
所以：

- **字符串变长 → 会破坏一切**：后面所有字符串的偏移错位，整张符号表作废。
- **字符串等长 → 完全安全**：只是原地改写若干字节，所有偏移不变。

因此本脚本**要求新旧包名长度完全相同**，然后做纯字节替换。
这样不需要解析 ELF、不需要重定位，对文本与二进制一视同仁。

包名长度上限为 **10 字符**：
    len("/data/data/") + len(包名) + len("/files/usr") = 11 + N + 10 = 21 + N
    原前缀 31 字节  =>  N <= 10

## 注意

DPKG 的 `.md5sums` / `.list` 用的是**不带前导斜杠**的路径
（`data/data/com.termux/files/...`），所以这里替换的 needle 也去掉前导斜杠，
一次覆盖两种形式。

## 用法

    python reprefix_bootstrap.py termux-bootstrap.zip termux-bootstrap-new.zip \\
        --old com.termux --new com.m7ahsr
"""

from __future__ import annotations

import argparse
import os
import sys
import zipfile

# 前缀里包名两侧的固定部分
LEAD = "data/data/"
TAIL = "/files/"


def replace_bytes(data: bytes, old: bytes, new: bytes) -> tuple[bytes, int]:
    n = data.count(old)
    if n:
        data = data.replace(old, new)
    return data, n


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="输入 bootstrap zip")
    ap.add_argument("dst", help="输出 zip")
    ap.add_argument("--old", default="com.termux", help="旧包名")
    ap.add_argument("--new", required=True, help="新包名")
    args = ap.parse_args()

    if len(args.old) != len(args.new):
        print(f"错误：包名长度必须相同，否则会破坏 ELF 里的字符串表。\n"
              f"  {args.old!r} 长度 {len(args.old)}\n"
              f"  {args.new!r} 长度 {len(args.new)}", file=sys.stderr)
        return 2

    if len(args.new) > 10:
        print(f"错误：新包名 {args.new!r} 长度 {len(args.new)} > 10，"
              f"前缀会超出原长度。", file=sys.stderr)
        return 2

    # 去掉前导斜杠，一次覆盖 list 与 md5sums 两种写法
    old = f"{LEAD}{args.old}".encode()
    new = f"{LEAD}{args.new}".encode()
    old_abs = f"/{LEAD}{args.old}".encode()
    new_abs = f"/{LEAD}{args.new}".encode()
    assert len(old) == len(new) and len(old_abs) == len(new_abs)

    print(f"替换：{old.decode()}  ->  {new.decode()}   "
          f"(各 {len(old)} 字节，等长)")

    total_entries = 0
    touched = 0
    hits = 0
    with zipfile.ZipFile(args.src) as zin, \
            zipfile.ZipFile(args.dst, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as zout:
        for info in zin.infolist():
            total_entries += 1
            if info.is_dir():
                zout.writestr(info, b"")
                continue
            data = zin.read(info.filename)
            data, n1 = replace_bytes(data, old, new)
            data, n2 = replace_bytes(data, old_abs, new_abs)  # 理论上 n2==0
            n = n1 + n2
            if n:
                touched += 1
                hits += n
            zout.writestr(info, data)

    print(f"共 {total_entries} 个条目，其中 {touched} 个被修改，"
          f"替换 {hits} 处")

    # ---- 校验 ----
    print("\n校验输出文件…")
    bad = []
    remaining = 0
    with zipfile.ZipFile(args.dst) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            d = z.read(info.filename)
            c = d.count(old)
            if c:
                remaining += c
                bad.append((info.filename, c))
    if remaining:
        print(f"  ✘ 仍残留 {remaining} 处旧前缀，涉及 {len(bad)} 个文件：",
              file=sys.stderr)
        for fn, c in bad[:20]:
            print(f"      {c:5d}  {fn}", file=sys.stderr)
        return 1
    print("  ✓ 已无旧前缀残留")

    src_size = os.path.getsize(args.src)
    dst_size = os.path.getsize(args.dst)
    print(f"\n体积: {src_size/1024/1024:.1f} MB -> {dst_size/1024/1024:.1f} MB "
          f"(差 {(dst_size-src_size)/1024/1024:+.2f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
