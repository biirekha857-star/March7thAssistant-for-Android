<div align="center">

# March7thAssistant 手机版

**把 Termux 直接内置进 APK 的「三月七小助手」安卓版**

不需要安装 Termux，不需要电脑，不需要 adb —— 装一个 APK，点一次「一键部署」，就能在手机上挂机跑 [March7thAssistant](https://github.com/moesnow/March7thAssistant)。

> ⚠️ **本项目是非官方的社区安卓封装，与上游 [March7thAssistant](https://github.com/moesnow/March7thAssistant) 项目及其作者没有隶属关系。**
> 应用显示名称与上游相同，但它只做**打包 + 图形化**，不修改任何自动化逻辑。
> 自动化逻辑本身的问题请反馈给上游；**安装、部署、界面相关的问题请反馈到本仓库(如果有精力我也会尝试打个补丁）**。

[![Release](https://img.shields.io/github/v/release/biirekha857-star/March7thAssistant-for-Android?style=flat-square&label=Release)](https://github.com/biirekha857-star/March7thAssistant-for-Android/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=flat-square&logo=android)](https://www.android.com/)

[功能](#功能) · [快速开始](#快速开始) · [常见问题](#常见问题) · [构建](#从源码构建) · [许可证](#许可证与致谢)

</div>

---

## 这是什么

[MAA Meow](https://github.com/AliothMoon/MAA-Meow) 的 Material 3 界面语言 + **真正内置**的 [Termux](https://github.com/termux/termux-app) 运行时 + [March7thAssistant](https://github.com/moesnow/March7thAssistant) 的自动化能力。

三样东西合在一起：**Termux 的终端模拟器、根文件系统、`proot-distro` 和完整的 Debian 容器镜像，全部打包在这个 APK 里。**

> **关于名字**：本项目的 Gradle 工程名与旧称是 **MaaTermux**。
> 代码里的 `Maa*` 类名（`MaaCard` / `MaaDesignTokens` 等）指的是取自
> **MAA Meow** 的界面设计语言，同时也是对其许可与出处的标注，故保留不改。

### 和手动部署相比

原本按[官方 Termux 部署教程](https://m7a.top/#/assets/docs/Termux)要手工做十几步：

| 教程里的手工操作 | 本应用 |
|---|---|
| 去 GitHub 下载安装 Termux | ❌ 不需要，终端已内置 |
| `termux-change-repo` 换源 | 部署页选源，自动写配置 |
| `pkg install proot-distro` | ❌ 不需要，已预装进 bootstrap |
| `proot-distro install ghcr.io/...`（**联网 1–3GB**） | ❌ 不需要，**镜像已内置，全程离线** |
| `git clone` + `uv sync` | ❌ 不需要，镜像内项目与依赖已就绪 |
| 手写 8 条 `export` 环境变量 | 设置页图形化，自动生成 |
| `uv run main.py daily` | 任务页一键运行 + 实时日志 |
| 二维码登录看不到图 | 二维码**直接显示在 App 里**，点开放大扫 |
| 翻文档排错 | 内置终端 + 实时日志 + CDP 画面监看 |

**首次部署完全不需要网络。**

---

## 功能

### 🚀 一键部署（离线）
点一次按钮，自动完成：解压内置 Termux 运行时 → 释放内置容器镜像 → 配置环境 → 安装 proot 容器 → 容器内自检 → 写入配置。
每步都有实时进度和输出，失败会**显示真实报错**而不是只给一个退出码。

### 📱 扫码登录
March7thAssistant 首次运行需要扫码。二维码由 App 从容器里取回并**直接显示在界面上**，点一下可全屏放大，用「米游社」App 扫即可。二维码会自动刷新，登录态保存在容器内，**之后不用再扫**。

### ▶️ 一键运行任务
预置常用任务：完整运行 / 例行任务 / 每日实训 / 清体力 / 货币战争 / 差分宇宙 / 模拟宇宙（由于官方限制，模拟宇宙（暂知）无法使用，会显示仅支持Windows,后续会尽可能尝试通过打补丁解决） / 忘却之庭 / 虚构叙事 / 末日幻影 / 游戏更新 / 测试推送 / 任务列表。

### 🎛️ 任务开关（28 项）##实验功能稳定性未知
不想让「完整运行」每次都跑全部内容？设置页可按组开关：

- **领取奖励** — 总开关、委托、邮件、支援、每日实训、无名勋礼、兑换码、成就、短信
- **日常任务** — 总开关、合成材料、姬子试用、回忆一
- **活动** — 活动总开关、每日签到
- **清体力** — 总开关、历战余响、后备开拓力、燃料、合成沉浸器、支援角色、自动切队伍
- **自动剧情与战斗** — 跳过对话、选择选项、自动战斗检测、处理短信页
- **运行行为** — 检查更新、成功后暂停、失败后退出

> 修改是**增量写入** `config.yaml`，不会覆盖程序记录的时间戳等状态。

### 🖥️ 实时监看画面
把云游戏的**实时画面**投到手机上，像监视器一样看自动化在做什么。基于 Chrome DevTools Protocol 的 screencast 实现。

### 💻 内置终端
完整的 Termux 终端（真实 PTY，非模拟），可以进容器里手动排查。支持双指缩放字号。

### 🔋 后台保活
任务运行时启动前台服务 + `WakeLock`，并在通知栏常驻：

- 退回桌面 / 息屏，任务继续跑
- **从最近任务划掉 App 也不中断**
- 通知栏可直接「停止任务」
- 设置页可一键申请电池优化白名单

### 📊 实时日志
按级别着色与过滤（默认隐藏 DEBUG 刷屏），自动滚动到底部。

### 🎨 外观
主题：跟随系统 / 浅色 / 深色 / 纯黑；强调色：三月七粉 / MAA 蓝。界面令牌取自 MAA Meow。

---

## 截图

<!-- 建议把截图放在 docs/screenshots/ 下，然后取消下面的注释 -->

<!--
| 首页 | 部署 | 任务 | 实时监看 |
|---|---|---|---|
| ![](docs/screenshots/home.png) | ![](docs/screenshots/deploy.png) | ![](docs/screenshots/tasks.png) | ![](docs/screenshots/monitor.png) |

| 扫码登录 | 终端 | 日志 | 任务开关 |
|---|---|---|---|
| ![](docs/screenshots/qrcode.png) | ![](docs/screenshots/terminal.png) | ![](docs/screenshots/log.png) | ![](docs/screenshots/toggles.png) |
-->

> 截图待补充。

---

## 快速开始

### 1. 安装

从 [Releases](https://github.com/biirekha857-star/March7thAssistant-for-Android/releases/latest) 下载 APK 安装。

> ⚠️ **本应用的包名是 `com.m7ahsr`。**
> 如果你装过包名为 `com.termux` 的早期测试版，两者**不是同一个应用**，
> 需要先卸载旧版（旧版的容器数据不会自动迁移，得重新部署）。

### 2. 一键部署

打开 App → **部署** 页 → 点「开始部署」。

全程离线，不需要网络。首次需要约 1–2 分钟释放内置镜像（500MB）。

> 建议先到 **设置 → 后台保活** 申请电池优化白名单，避免任务被系统中途回收。

### 3. 扫码登录

部署完成后到 **任务** 页点「一键运行 完整运行」。

首次会走到登录步骤，**二维码会显示在卡片里**，用「米游社」App 扫描即可。

### 4. 开始挂机

登录后任务会自动继续。之后想再跑，直接点「一键运行」或「一键运行任务」。

需要实时看画面就点 **实时监看画面**（需任务已在运行）。

---

## 常见问题

<details>
<summary><b>部署失败提示「容器安装失败」怎么办？</b></summary>

先看部署页那行红字下面的「最近输出」—— 部署流程会把真实报错回显出来。
最常见的是存储空间不足：`/data` 至少需要 **2.5GB** 余量。
也可以到 **设置 → 重置运行时** 清空后重来。
</details>

<details>
<summary><b>日志里出现 <code>GPU device discovery failed ... /sys/class/drm</code> 要紧吗？</b></summary>

**不要紧，可以忽略。**

这是 onnxruntime 启动时找不到 GPU 加速器（proot 里没有 DRM 设备）后**回落到 CPU** 的正常提示，以 `[W:` 开头是警告不是错误。而且我们本来就只要 CPU。

真正需要留意的是 `[E:` 或 `| ERROR |` 开头的行，以及 `Traceback`。
</details>

<details>
<summary><b>出现 <code>free(): invalid next size</code> 然后崩溃？</b></summary>

proot 无法使用 swap，安卓可用内存有限时 glibc 堆检测会触发（`signal 6`）。
**唯一的解法是关掉后台应用腾内存**，代码层面无法规避。
</details>

<details>
<summary><b>OCR 初始化失败 / 报 OpenVINO 错误？</b></summary>

程序会自动回退到 ONNXRuntime，**不影响正常运行**。
</details>

<details>
<summary><b>云游戏卡在「等待时间较长」弹窗、一直「正在努力重连」？</b></summary>

这是 [上游已知问题](https://github.com/moesnow/March7thAssistant/issues)，疑似与安卓版本 / 内核 / 手机型号有关，新款手机更容易出现。只能换个设备试试。
</details>

<details>
<summary><b>任务跑一会儿莫名中断，日志没有任何报错？</b></summary>

大概率是系统回收了进程。依次检查：

1. **设置 → 后台保活** → 申请加入电池优化白名单
2. 系统设置里允许本应用**自启动**
3. 省电策略设为**无限制**

Android 12+ 会回收应用派生的「幽灵进程」，而 Chromium 自己就会派生几十个进程。若仍有问题，可在有 root / Shizuku 的设备上执行
`settings put global settings_enable_monitor_phantom_procs false`。
</details>

<details>
<summary><b>「实时监看」一直显示「未找到调试端口」？</b></summary>

**必须先启动任务**，等浏览器真的跑起来（日志里出现「正在启动 chromium 浏览器」）之后，再点开始监看。
监看页下方的「诊断输出」会说明卡在哪一步。
</details>

<details>
<summary><b>终端打不开 / 提示版本冲突？</b></summary>

本应用包名是 `com.m7ahsr`，与官方 Termux（`com.termux`）**不冲突**，理论上可以共存。

但如果你装的是**早期测试版**（那时包名还是 `com.termux`），二者不是同一个应用：
要先卸载旧版再装新版，旧版里已部署好的容器不会迁移，需重新部署。
</details>

<details>
<summary><b>能改包名吗？</b></summary>

**能改，但要守一个硬约束：包名不能超过 10 字符。**

内置 bootstrap 的二进制把 `/data/data/<包名>/files/usr` 作为 `$PREFIX`
**硬编码**在 ELF 的 `.dynstr` 里（`DT_RUNPATH` 指向它）。字符串表里所有
动态符号按**偏移量**引用，所以这个字符串**只能改短、不能改长** —— 改长会让
后面全部错位，整张符号表作废：

```
实际路径 = "/data/data/" + 包名 + "/files/usr" = 21 + len(包名) 字节
原前缀（com.termux）                            = 31 字节
                                        =>  len(包名) <= 10
```

本项目用的 `com.m7ahsr` 正好 **10 字符**，所以新旧前缀**完全等长**，
可以做成纯字节替换。做法见 [`scripts/reprefix_bootstrap.py`](scripts/reprefix_bootstrap.py)：

```bash
python scripts/reprefix_bootstrap.py \
    app/src/main/assets/bootstrap/termux-bootstrap.zip \
    /tmp/bootstrap-new.zip \
    --old com.termux --new <你的10字符包名>
```

然后**三处一起改**（漏一处就是「点了没反应」）：

1. `app/build.gradle.kts` 的 `applicationId`
2. 用上面的脚本重新生成 `assets/bootstrap/termux-bootstrap.zip`
3. `TermuxPaths.APP_PACKAGE`

> 有 `TermuxPrefixInvariantTest` 守着这个一致性，改漏了测试会直接失败。

> 想用**超过 10 字符**的包名，就只能用 termux-packages 以新前缀
> 重新构建整套 bootstrap（需要 Linux + Docker，耗时以小时计）。
</details>

---

## 系统要求

| 项 | 要求 |
|---|---|
| 系统版本 | Android 7.0+（`minSdk 24`），建议 Android 10+ |
| 架构 | **arm64-v8a**（暂不支持 x86_64 / armeabi-v7a） |
| 内存 | 建议 4GB+（proot 无法使用 swap，2GB 会比较紧张） |
| 存储 | `/data` 至少 **2.5GB** 可用 |
| 网络 | 首次部署**不需要**；之后跑云游戏任务需要联网 |

---

## 从源码构建

### 环境

| 项 | 版本 |
|---|---|
| JDK | 17+ |
| Android SDK | `compileSdk 37`（`android-37.0`） |
| NDK | `29.0.14206865` |
| CMake | `3.22.1` |
| Gradle | 9.6.0（wrapper 已含） |
| AGP | 9.2.1 |

```bash
sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（约 553MB）
```

### 两个大资产

仓库**不包含**这两个文件（合计约 537MB），需按下面步骤生成。

**① Termux bootstrap（约 40MB）**

1. 从 Termux 官方 APK 取出 `libtermux-bootstrap.so`，其中 `.incbin` 的 zip 位于 ELF 头之后，
   扫描 `PK\x03\x04` / `PK\x05\x06` 可定位并裁出完整 zip
2. 从 `https://packages-cf.termux.dev/apt/termux-main` 下载 `proot`、`proot-distro`
   及其依赖闭包（共 16 个 `.deb`，约 7.7MB）
3. 解 `.deb` 时注意包内路径是**绝对**的 `./data/data/com.termux/files/usr/...`，需剥掉前缀
   （若已改包名，用 `scripts/reprefix_bootstrap.py` 处理，不要手工替换）
4. 重新生成 `SYMLINKS.txt`（zip 存不了软链）并重新打包

**② 离线容器镜像（约 497MB）**

```bash
# 直连 ghcr.io 在国内会卡住，建议走镜像
REG_MIRROR=ghcr.nju.edu.cn python pull_mirror.py
python assemble_rootfs.py
```

镜像 arm64 子清单摘要（可复现校验）：

```
sha256:58341961e8616a33172df0d55455becf85841eb3e6d7625155c91f69423118fa
```

> ⚠️ 重新打包 rootfs 时**必须从原始 layer 元数据恢复文件权限**。
> 在 Windows 上 `os.stat()` 没有可执行位，`tarfile.add()` 会把所有 `0755` 写成 `0666`，
> 结果是 proot 报 `'/bin/bash' is not executable` 而无法启动。

开发过程中踩过的坑与实现细节见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

---

## 已知限制

诚实列一下：

- **仅支持 arm64-v8a。** 内置 bootstrap 与容器镜像是 aarch64 的，其他架构需各自重建资产。
- **APK 约 553MB。** 内置了 40MB bootstrap + 497MB 离线镜像。不适合 Google Play，适合 GitHub Releases。
- **包名 `com.m7ahsr`。** 想换成别的名字要注意 10 字符上限（见上）。
- **`targetSdk` 保持 28。** 这是允许从应用私有目录 `exec` 二进制、并沿用旧版 `/sdcard` 存储模型的前提（Termux 上游同样如此）。
- **实时监看依赖 Chromium 的调试端口。** 若浏览器未带 `--remote-debugging-port` 启动则会失败（页面内会给出诊断输出）。
- **后台保活的最终效果取决于 ROM。** 国产 ROM 的额外限制无法通过 API 绕过，只能引导用户手动放行。
- **只在少量设备上验证过。** 不同厂商 ROM 对私有目录 `exec`、ptrace 的限制不同，遇到问题欢迎提 Issue 并附上「日志」页的输出。

---

## 许可证与致谢

本项目是三个开源项目的整合与图形化封装，**以 [AGPL-3.0](LICENSE) 发布**。

### 上游项目

| 项目 | 用途 | 许可证 |
|---|---|---|
| [Termux](https://github.com/termux/termux-app) | 终端模拟器（本仓库使用其 `terminal-emulator` / `terminal-view` 模块） | **Apache-2.0**（这两个模块，源自 Android-Terminal-Emulator） |
| [Termux packages](https://github.com/termux/termux-packages) | bootstrap 与 `proot` / `proot-distro` 等包 | 各包各自许可（`bash`/`coreutils` 等为 GPL-3.0） |
| [March7thAssistant](https://github.com/moesnow/March7thAssistant) | 自动化逻辑本体与容器镜像 | **GPL-3.0** |
| [MAA Meow](https://github.com/AliothMoon/MAA-Meow) | Material 3 界面设计语言（设计令牌 / 配色 / 排版） | **AGPL-3.0** |

许可证全文见 [`licenses/`](licenses/) 目录，第三方组件与修改说明见 [THIRD-PARTY.md](THIRD-PARTY.md)。

### 本项目对上游代码的修改

- `terminal-emulator` / `terminal-view`：从 Termux 上游的 `ndk-build` 改用 **CMake**，以适配 AGP 9；
  移除上游的 maven-publish 配置；Java 目标版本提升至 17。
- 其余为新增代码（Termux 运行时集成、部署流程、界面、监看等），未修改上游项目本体。

### 关于内置镜像

APK 内置的 497MB 离线镜像是对上游容器镜像
`ghcr.io/moesnow/march7thassistant:latest`（arm64）的**原样重新打包**，
其中包含 March7thAssistant 完整源代码、Debian 用户空间、Python 运行时与 Chromium。
完整源代码可从上游仓库及其镜像获取；构建脚本见本仓库。

---

## 免责声明

- 本项目**仅供学习与技术交流**使用，请勿用于任何商业用途。
- 本项目与米哈游 / HoYoverse 无任何关联。
- 自动化操作可能违反游戏用户协议，**使用风险由使用者自行承担**。
- 请遵守所在地区的法律法规以及相关服务条款。
