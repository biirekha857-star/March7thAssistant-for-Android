# 第三方组件与修改说明

本文件说明 MaaTermux 使用了哪些第三方项目、各自的许可证，以及本仓库对其所做的修改。

本仓库以 **AGPL-3.0** 发布（见 [LICENSE](../LICENSE)）。许可证全文见 [`licenses/`](../licenses/) 目录。

---

## 1. 直接使用的上游代码

### Termux — 终端模拟器模块

- 仓库：https://github.com/termux/termux-app
- 使用的模块：**`terminal-emulator`**、**`terminal-view`**
- 许可证：**Apache-2.0**
- 全文：[`licenses/Apache-2.0.txt`](../licenses/Apache-2.0.txt)

> 注意：`termux-app` 仓库整体是 **GPL-3.0 only**，但其 `LICENSE.md` 明确列出例外 ——
> `terminal-view` 与 `terminal-emulator` 来自
> [Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)，
> 以 **Apache-2.0** 发布。本仓库只使用了这两个 Apache-2.0 模块，
> 未使用 `termux-shared` 与 `app` 模块。

**本仓库所做的修改**（依 Apache-2.0 §4 要求声明）：

1. 原生构建从上游的 `ndk-build`（`Android.mk`）改为 **CMake**，以适配 AGP 9；
   源文件 `termux.c` 内容未改动，仅调整编译参数。
2. 移除上游 `build.gradle` 中的 `maven-publish` 发布配置。
3. Java 编译目标从 1.8 提升至 17。
4. 模块名与包名保持与上游一致（`com.termux.terminal` / `com.termux.view`），未做重命名。

### March7thAssistant — 自动化逻辑与容器镜像

- 仓库：https://github.com/moesnow/March7thAssistant
- 许可证：**GPL-3.0**
- 全文：[`licenses/GPL-3.0.txt`](../licenses/GPL-3.0.txt)

**使用方式**：**未修改其源代码**。APK 内置的离线容器镜像是对上游官方容器镜像
`ghcr.io/moesnow/march7thassistant:latest`（arm64）的**原样重新打包**，
其中已包含该项目完整源代码（位于镜像的 `/m7a`）。

镜像 arm64 子清单摘要（可用于校验一致性）：

```
sha256:58341961e8616a33172df0d55455becf85841eb3e6d7625155c91f69423118fa
```

打平 OCI layer 的脚本见 [`scripts/assemble_rootfs.py`](../scripts/assemble_rootfs.py)，
它只做两件事：应用 OCI whiteout 规则把 15 个 layer 展开成一个完整 rootfs，
再打成 proot-distro 可安装的 tar.gz。**不改动任何文件内容**。

### MAA Meow — 界面设计语言

- 仓库：https://github.com/AliothMoon/MAA-Meow
- 许可证：**AGPL-3.0**
- 全文：[`licenses/AGPL-3.0.txt`](../licenses/AGPL-3.0.txt)

**本仓库所做的修改**：取其 Material 3 设计令牌（圆角 / 间距 / 阴影 / 分隔线规格）、
中性色板与排版定义，改写为 `ui/theme/` 下的 Compose 实现
（`DesignTokens.kt` / `Theme.kt` / `Type.kt`），并新增「三月七粉」强调色方案。
未复制其业务逻辑代码。

---

## 2. 随 APK 分发的二进制

APK 内置的两个大资产中包含了大量第三方二进制，无法逐一声明。以下是来源说明。

### 2.1 Termux bootstrap 内的包

bootstrap 由 [termux-packages](https://github.com/termux/termux-packages) 构建。
本仓库额外注入了 `proot-distro` 的完整依赖闭包（16 个 `.deb`）：

| 包 | 用途 |
|---|---|
| `proot`、`libtalloc`、`libandroid-shmem` | 用户态容器运行时 |
| `proot-distro` | 容器管理 |
| `python`、`python-pip`、`libsqlite`、`libffi`、`libexpat`、`liblzma`、`libbz2`、`gdbm`、`libcrypt`、`zstd`、`ncurses-ui-libs`、`libandroid-posix-semaphore` | proot-distro 的运行时依赖 |

这些包各自的许可证**不在本仓库内**，而是随包存放在
bootstrap 的 `$PREFIX/share/doc/<包名>/` 下（例如
`share/doc/libandroid-support/LICENSE.txt`）。`bash`、`coreutils` 等为 GPL-3.0。

清单与下载地址由 [`scripts/resolve_termux_deps.py`](../scripts/resolve_termux_deps.py) 生成，
可复现。

### 2.2 离线容器镜像内的组件

镜像来自上游 `ghcr.io/moesnow/march7thassistant:latest`，其中包含：

- **Debian 12 (bookworm) 用户空间** —— 数百个 Debian 软件包，各自许可
  （主要位于镜像的 `/usr/share/doc/*/copyright`）
- **Python 3.14 运行时与依赖** —— 位于 `/opt/venv`，含 onnxruntime、OpenCV、
  rapidocr 等；各自许可位于对应 `dist-info` 目录
- **Chromium 与 chromedriver** —— BSD 系许可
- **March7thAssistant** —— GPL-3.0，源代码在 `/m7a`
- **3rdparty/** 下的第三方资源（Auto_Simulated_Universe、Fhoe-Rail 等）

完整源代码可从上游仓库与其镜像层获取：

```bash
REG_MIRROR=ghcr.nju.edu.cn python scripts/pull_image.py
python scripts/assemble_rootfs.py
```

---

## 3. 构建期依赖（不随 APK 分发）

| 组件 | 许可证 |
|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 |
| Kotlin / kotlinx.coroutines | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Android NDK / CMake toolchain | 各自许可 |

---

## 4. 合规提示（分发前请确认）

本项目聚合了 **GPL-3.0** 与 **AGPL-3.0** 的组件，因此：

1. **整体必须以 AGPL-3.0 分发**，且不得附加额外限制。
2. **必须提供完整对应源代码**。本项目通过「源码仓库 + 内置镜像内已含上游源码 +
   可复现的构建脚本」满足这一点。
3. **必须保留许可证与版权声明**。`licenses/` 目录与本文件为最低限度；
   若你在自己的 fork 中分发，请一并保留。
4. **修改需声明**。见上文第 1 节。
5. 建议在任何发布页面同时给出上游项目链接与许可证链接。

> 本文件为工程说明，**不构成法律意见**。正式分发前请自行核实许可证兼容性，
> 必要时咨询专业人士。
