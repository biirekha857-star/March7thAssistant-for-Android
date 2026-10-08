# 构建脚本

这些脚本用来**生成仓库里没有的两个大资产**（合计约 537MB）：

| 产物 | 大小 | 生成脚本 |
|---|---|---|
| `app/src/main/assets/bootstrap/termux-bootstrap.zip` | ~40MB | `build_bootstrap.py` |
| `app/src/main/assets/rootfs/m7a-rootfs.bin` | ~497MB | `pull_image.py` + `assemble_rootfs.py` |

> ⚠️ **脚本里的路径是按作者环境写死的**（Windows，`D:\Temp\...`）。
> 换机器前请先改每个脚本顶部的常量，或按下面说明设置环境变量。

---

## 前置条件

- Python 3.11+
- `pip install zstandard`（解 `.deb` / OCI layer 时需要）
- 能访问 `packages-cf.termux.dev`（bootstrap 依赖）与容器镜像仓库

---

## ① 生成 Termux bootstrap

### 取 base bootstrap

bootstrap 藏在 Termux 官方 APK 的 `lib/arm64-v8a/libtermux-bootstrap.so` 里：
它是用 `.incbin` 内联进去的一个 zip，位于 ELF 头之后。

先扫描定位再裁出来（`PK\x03\x04` 是第一条本地头，`PK\x05\x06` 是 EOCD）：

```python
data = open("libtermux-bootstrap.so", "rb").read()
# 中央目录的第一条记录位置 - EOCD 里记录的 cd_offset = blob 起始偏移
cd = data.find(b"PK\x01\x02")
eocd = data.rfind(b"PK\x05\x06")
cd_offset = struct.unpack_from("<I", data, eocd + 16)[0]
blob = data[cd - cd_offset : eocd + 22]
open("bootstrap-aarch64.zip", "wb").write(blob)
```

### 解析 proot-distro 依赖闭包并打进去

```bash
# 1. 下载 Termux 仓库索引
curl -o Packages.bz2 \
  https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages.bz2

# 2. 解析 proot-distro 的依赖闭包（输出 scripts 用的清单）
python resolve_termux_deps.py     # 产出 termux-pkgs.txt

# 3. 下载 .deb、解包、并入 bootstrap、重新打包
python build_bootstrap.py
```

**两个坑**：

- `.deb` 里的路径是**绝对**的 `./data/data/com.termux/files/usr/...`，
  必须剥掉前缀再并入 —— 因为 bootstrap zip 是**相对 `$PREFIX`** 的。
- zip **存不了符号链接**。bootstrap 里 `bin/coreutils` 是一个 multicall 二进制，
  `mkdir`/`rm`/`cat` 等上百个命令都靠软链指向它，所以必须单独生成
  `SYMLINKS.txt`（格式 `<目标>←<路径>`）。漏了它，解压出来的系统等于不能用。

---

## ①.5 改应用包名（`reprefix_bootstrap.py`）

bootstrap 里的二进制把 `/data/data/<包名>/files/usr` 作为 `$PREFIX`
**硬编码**在 ELF 的 `.dynstr` 里（`DT_RUNPATH` 指向它）。换包名就得改它。

**硬约束：这个字符串只能改短，不能改长。**

```
实际路径 = "/data/data/" + 包名 + "/files/usr" = 21 + len(包名) 字节
原前缀（com.termux）                          = 31 字节
                                      =>  len(包名) <= 10
```

原因：`.dynstr` 是连续的、以 `\0` 分隔的字符串表，所有动态符号按**偏移量**引用。
改长会让后面全部错位，整张符号表作废。

本仓库用的是 `com.m7ahsr`（正好 10 字符），所以新旧前缀**完全等长**，
可以做**纯字节替换** —— 不需要解析 ELF、不需要重定位。

```bash
python reprefix_bootstrap.py \
    ../app/src/main/assets/bootstrap/termux-bootstrap.zip \
    /tmp/bootstrap-new.zip \
    --old com.termux --new com.m7ahsr
```

脚本会自己校验等长、拒绝 > 10 字符的包名，并在写完后**重新打开输出文件**
确认已无旧前缀残留。

实测：777 个文件、10206 处替换、体积一字未变（39.4MB → 39.4MB）。

> 注意替换的 needle 是 `data/data/com.termux`（**不带前导斜杠**）。
> 因为 dpkg 的 `*.md5sums` 用的是这种写法，而 `*.list` 用带斜杠的写法 ——
> 不带斜杠的 needle 一次覆盖两种。

改完包名还要同步改两处，漏一处就是「点了没反应」：

1. `app/build.gradle.kts` 的 `applicationId`
2. `app/src/main/java/com/miguanm7a/hsr/termux/TermuxPaths.kt` 的 `APP_PACKAGE`

有 `TermuxPrefixInvariantTest` 守着（会直接解压真实资产核对）。

**容器镜像不用重跑** —— 它是 Debian 用户空间，路径都是 `/usr`、`/m7a`，
与 Termux 的 `$PREFIX` 无关。实测扫描 19563 个文件，`com.termux` 出现 0 次。

---

## ② 生成离线容器镜像

```bash
# 直连 ghcr.io 从国内会卡在 265MB 那一层；走镜像快很多
REG_MIRROR=ghcr.nju.edu.cn python pull_image.py

# 应用 OCI whiteout 规则展开成一个完整 rootfs，再打成 proot-distro 能装的 tar.gz
python assemble_rootfs.py
```

可选的 `--pack` 参数只重新打包、复用已解开的目录。

### ⚠️ 最关键的一点：必须保留可执行位

**这一步在 Windows 上做会出事。**

Windows 的 `os.stat()` **没有可执行位**，而 `tarfile.add()` 会把 `st_mode`
原样写进归档 —— 于是所有 `0755` 静默变成 `0666`。结果是容器里的
`/bin/bash` 不可执行，proot 直接拒绝启动：

```
proot error: '/bin/bash' is not executable
fatal error: see `proot --help`.
```

这个报错极具误导性（看起来像内核限制了 `ptrace`/`seccomp`），实际上纯是权限问题。

所以 `assemble_rootfs.py` 的做法是：**从原始 layer 的 tar 元数据里采集
`路径 → mode`**，打包时显式写回 `TarInfo.mode`，**绝不使用 `os.stat()`**。

### 校验

```bash
python audit_rootfs_permissions.py
```

它会检查 `/usr/bin` 下是否还有「非软链但不可执行」的条目、
`/opt/venv/bin/python` 的解析链是否可执行等。

---

## 产物放哪

```
app/src/main/assets/
├── bootstrap/termux-bootstrap.zip     ← ①
└── rootfs/m7a-rootfs.bin              ← ②
```

> 镜像资产的扩展名**故意用 `.bin` 而不是 `.tar.gz`**：
> AGP 对 `.gz` 会走「解压后存储」的打包路径，实测会把 497MB 的 gzip
> 展开成 **1.4GB** 塞进 APK。用 `.bin` 就是原样存储（内容仍是 gzip 压缩的 tar）。

放好后就能正常构建：

```bash
./gradlew :app:assembleDebug
```

---

## ⚠️ 这两个资产**不能**提交进 Git

`.gitignore` 已经把它们排除了，原因有两条：

1. **GitHub 单文件上限 100MB。** `m7a-rootfs.bin` 有 497MB，
   **永远无法**作为普通 git 对象推上去（push 会被服务端拒绝）。
   即使改用 Git LFS，LFS 也有带宽配额，对这个体积很不划算。
2. 本仓库源码本身只有几 MB，把 537MB 二进制塞进历史会让 clone 变得极其痛苦。

**因此发布时的推荐做法**：

- **普通用户**：直接下载 Releases 里的 APK，不需要自己构建。
- **想自己构建的人**：
  - 方式 A：按上面步骤自己生成两个资产（可复现，需要网络与时间）；
  - 方式 B：把生成好的两个资产作为 **Release 附件**上传，
    让别人下载后放进 `app/src/main/assets/` 对应目录即可编译。

> 建议维护者在 Release 里**同时上传 APK 与这两个资产**，
> 这样别人不用从零重建就能改代码。

另外：由于需要 537MB 的资产，**本仓库不适合挂 CI**（GitHub Actions 里
重建这两个资产会非常耗时且容易超时），建议本地构建后手动上传 Release。

---

## 相关单元测试

```bash
./gradlew :app:testDebugUnitTest
```

其中这几个测试直接读真实资产，用来防止上面那些坑回归：

| 测试 | 防止什么 |
|---|---|
| `BootstrapZipReadTest` | 读 `SYMLINKS.txt` 时误关 `ZipInputStream` |
| `RootfsPermissionsTest` | 镜像里的可执行位被抹掉 |
| `ConfigPatcherTest` | 覆盖 `config.yaml` 时清掉状态字段 |
| `TermuxPathsGuestTest` | `/data/user/0` 没规范成 `/data/data` |
