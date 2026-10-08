# MaaTermux 开发笔记

> 面向**改这个项目的人**，不是面向使用者。用户文档见 [../README.md](../README.md)。
>
> 这里记录的是实现细节与**踩过的坑**。每一条都是真机上真实发生过的，
> 并且多数已经写成单测防止回归 —— 所以改动相关代码前请先读对应小节。

把 [MAA Meow](https://github.com/Aliothmoon/MAA-Meow) 的 Material 3 界面语言与
**真正内置**的 Termux 运行时合到一起的安卓应用：Termux 的终端模拟器、bootstrap
根文件系统、`proot-distro` 及 Debian 容器镜像**全部打包在 APK 里**，不依赖手机上
是否安装了 Termux，也不通过 Intent 调用第三方应用。

部署目标为 [March7thAssistant](https://github.com/moesnow/March7thAssistant)
（三月七小助手，崩坏：星穹铁道自动化），运行在 App 私有的 proot 容器中。

---

## 一、这个项目解决了什么

原教程（`新建文件夹/Termux 安卓部署.html`）要求用户：自己去 GitHub 下 Termux →
`termux-change-repo` 换源 → `pkg install proot-distro` → 联网拉 1–3GB 镜像 →
手写 8 条环境变量 → 敲命令跑 `main.py`。

本应用把这条链路全部内嵌：

| 教程里的手工步骤 | 本应用的做法 |
|---|---|
| 下载安装 Termux | ❌ 不需要。Termux 的 `terminal-emulator` / `terminal-view` 作为 Gradle module 编进 APK |
| `termux-change-repo` 换源 | 部署页选源，脚本自动写 `$PREFIX/etc/apt/sources.list` |
| `pkg install proot-distro` | ❌ 不需要。`proot` + `proot-distro` + Python 已预装进 bootstrap |
| `proot-distro install ghcr.io/...`（1–3GB 联网） | ❌ 不需要。容器 rootfs 已内置为 APK 资产，离线释放 |
| `git clone` + `uv sync` | ❌ 不需要。内置镜像里 `/m7a` 已是完整项目，`/opt/venv` 依赖齐全 |
| 手写 `export ...` 环境变量 | 设置页图形化配置，自动生成命令/脚本 |
| `uv run main.py daily` | 任务页「一键运行」，带实时日志看板 |
| 翻文档排错 | 实时日志 + 终端页，可直接进容器排查 |

---

## 二、关键设计决策（为什么这么做）

这几条是**硬约束**，改动前请先读。

### 1. 包名：代码包名随意，`applicationId` 受 ELF 字符串表硬约束

Termux 的 bootstrap 里，`bash`、`apt`、`dpkg`、`python3` 等二进制把
`/data/data/<包名>/files/usr` 作为 `$PREFIX` **硬编码**进了 ELF。

本项目最终用的是 **`com.m7ahsr`**，并且 `namespace` 与 `applicationId` 是**分开**的：

```kotlin
namespace     = "com.miguanm7a.hsr"   // 代码包名（R 类 / 清单相对名），可自由改
applicationId = "com.m7ahsr"           // 装机包名，受下面这条约束限制
```

#### 硬约束：这个字符串**只能改短，不能改长**

前缀出现在两类地方，可改性完全不同：

| 位置 | 能否改 |
|---|---|
| **脚本的 shebang**（`#!/data/data/<包名>/files/usr/bin/python3.14`） | ✅ 纯文本，随意改 |
| **ELF 的 `DT_RUNPATH`**：字符串位于 `.dynstr`，动态链接器据此找 `.so` | ❌ **不能变长** |
| **ELF 的其他字面量**（bash 的默认 PATH / sysconfig 路径等），按 RIP 相对寻址引用 | ❌ **不能变长** |

原因：`.dynstr` 是一张连续的、以 `\0` 分隔的字符串表，所有动态符号名都按
**偏移量**引用。把其中一个字符串改长，后面所有字符串的偏移全部错位，
**整张动态符号表就废了**。其他字面量同理。

而**改短是安全的**：提前写 `\0` 截断即可，后面的偏移不变。

#### 于是得到包名长度上限

```
实际路径 = "/data/data/" + 包名 + "/files/usr"   =>   21 + len(包名) 字节
原前缀（com.termux）                                       31 字节
                                                    =>   len(包名) <= 10
```

`com.m7ahsr` 正好 **10 字符**，所以新旧前缀**完全等长（31 字节）**，
可以做成**纯字节替换**：不需要解析 ELF、不需要重定位，对文本与二进制一视同仁。

#### 怎么做的

`scripts/reprefix_bootstrap.py`：

```bash
python scripts/reprefix_bootstrap.py \
    app/src/main/assets/bootstrap/termux-bootstrap.zip \
    /tmp/bootstrap-new.zip \
    --old com.termux --new com.m7ahsr
```

脚本会**强制校验新旧包名等长**（不等长直接拒绝运行），并拒绝 > 10 字符的包名。

实测结果：**777 个文件、10206 处**被替换，体积一字未变（39.4MB → 39.4MB）。

替换的是 `data/data/com.termux`（**不带前导斜杠**，20 字节）。
之所以不带斜杠，是因为 dpkg 的 `*.md5sums` 用的正是这种形式
（`data/data/com.termux/files/usr/bin/...`），而 `*.list` 用带斜杠的形式 ——
不带斜杠的 needle 一次覆盖两种。

#### 改包名的完整清单

改 `applicationId` 时**必须同时**做这三件事，漏一件就是「点了没反应」：

1. `app/build.gradle.kts` 的 `applicationId`
2. **重跑 `reprefix_bootstrap.py`** 更新 `assets/bootstrap/termux-bootstrap.zip`
3. `TermuxPaths.APP_PACKAGE`

第 3 条有测试守着：`TermuxPrefixInvariantTest` 会校验
`APP_PACKAGE == BuildConfig.APPLICATION_ID`、长度 ≤ 10、前缀恰好 31 字节，
并且**直接解压真实资产**确认里面已无旧前缀。

#### 容器镜像不用改

497MB 的 rootfs **不需要重新生成** —— 它是 Debian 用户空间，
里面的路径都是 `/usr`、`/m7a` 这类，与 Termux 的 `$PREFIX` 无关。
实测流式扫描 19563 个文件，`com.termux` 出现 **0 次**。

#### 有 13 个文件仍含裸 `com.termux`，这是**故意保留**的

| 文件 | 内容 | 为什么不动 |
|---|---|---|
| `bin/am`、`bin/termux-am`、`bin/termux-wake-lock`、`bin/termux-open`、`bin/termux-reset`、`bin/termux-info`、`bin/termux-setup-storage` 等 10 个 | 指向 **Termux 应用自身的 Android 组件**（`com.termux.app.TermuxService` 等） | 这些组件在本应用里**根本不存在**，改与不改都是死代码（Termux:API 系命令本来就依赖额外的 App） |
| `bin/pkg` | 只是 `--version` 横幅里的显示文字 | 功能正常，`install-prefix` 部分已正确更新 |
| `proot_distro/constants.py` | `os.environ.get("TERMUX_APP__PACKAGE_NAME", "com.termux")` 的兜底默认值 | 我们**始终**会设置该环境变量（`TermuxEnvironment` 里取自 `BuildConfig.APPLICATION_ID`），走不到默认值 |

副作用：本应用与官方 Termux **不能共存**（包名不再是 `com.termux`，
理论上可以共存了，但两者都会往 `/sdcard` 写 Termux 相关目录，仍不建议同时装）。

> **注意**：改用 `com.m7ahsr` 后，**升级安装会变成「新应用」** ——
> 与之前 `com.termux` 版本的 App 不是同一个包，`/data` 数据不共享。
> 从 `com.termux` 版本换过来时必须**卸载重装并重新部署**，
> 或者用 `run-as` / root 手动迁移 `/data/data/com.termux/files`。


### 2. `targetSdk` 保持 28

Termux 上游同样使用 `targetSdk 28`。这是 Android 10+ 上允许从应用私有目录
`exec` 二进制、以及沿用旧版 `/sdcard` 存储模型的前提。改成 29+ 会让容器内的
`/sdcard` 绑定失效，并可能触发 W^X 限制。

### 3. bootstrap 是 ZIP，不是 tar

Termux 的 bootstrap（`app/src/main/assets/bootstrap/termux-bootstrap.zip`）
是**相对 `$PREFIX` 的 zip**，并且**不含符号链接信息**（zip 格式存不了）。
软链单独记在 `SYMLINKS.txt` 里，用 `←` 分隔：

```
coreutils←bin/mkdir
coreutils←bin/rm
...
```

`BootstrapInstaller.kt` 因此必须做三件事，缺一个都会静默失败：

1. 解压到 `$FILES/usr-staging`
2. **按 `SYMLINKS.txt` 重建软链**（`bin/coreutils` 是个 multicall 二进制，
   `mkdir`/`rm`/`cat`/`ls` 等 100+ 个命令全靠软链指向它）
3. **恢复可执行位**（Android 的 `ZipEntry` 不暴露 unix mode，无法从 zip 里读；
   故按目录约定：`bin/`、`libexec/`、`lib/apt/{apt-helper,methods}` 置可执行）

#### 踩过的坑：读 `SYMLINKS.txt` 不能用 `bufferedReader()`

```kotlin
// ✗ 错：useLines 会关闭 BufferedReader，而它关闭时连带关闭 ZipInputStream，
//      于是下一个 zis.nextEntry 抛 IOException("Stream closed")
zis.bufferedReader().useLines { ... }

// ✓ 对：ZipInputStream.read() 在当前条目结束时返回 -1，正好读完一条
readEntryAsString(zis).lineSequence().forEach { ... }
```

因为 `SYMLINKS.txt` 是 zip 的**第一条**（重新打包时先写入它），这个错误会让
安装**第一步就失败**，报错正是 `Stream closed`。
`app/src/test/.../BootstrapZipReadTest.kt` 是这条的回归测试，
它跑真实资产并同时反证旧写法会失败。

### 4. 内置镜像的资产扩展名是 `.bin`，不是 `.tar.gz`

`app/src/main/assets/rootfs/m7a-rootfs.bin` 实际内容是 gzip 压缩的 tar。

**故意不用 `.tar.gz` 后缀**：AGP 对 `.gz` 会走「解压后存储」的打包路径，
实测会把 497MB 的 gzip 展开成 **1.4GB** 塞进 APK。改用 `.bin` 后 AGP 原样存储，
APK 从 1455MB 降到 553MB。

### 5. 容器内项目的路径是 `/m7a`，不是 `~/March7thAssistant`

内置镜像来自官方容器镜像 `ghcr.io/moesnow/march7thassistant:latest`，
其布局为：

| 路径 | 内容 |
|---|---|
| `/m7a` | March7thAssistant 项目本体（含 OCR 模型、`3rdparty/`） |
| `/opt/venv` | 依赖齐全的 Python 虚拟环境 |
| `/usr/bin/chromium`、`/usr/bin/chromedriver` | 云游戏无头浏览器 |

所以运行命令是 `/opt/venv/bin/python main.py`，**不需要 `uv`**。

### 5. 重新打包 rootfs 时必须从原始 layer 恢复权限位（Windows 上会丢）

这是**真机上踩过的第二个大坑**，症状极具误导性：

```
proot error: '/bin/bash' is not executable
fatal error: see `proot --help`.
```

看起来像内核限制 `ptrace` / `seccomp`，实际与内核无关，纯粹是**权限位被抹掉了**。

原因：镜像是在 **Windows** 上重新打包的。Windows 的 `os.stat()` **没有可执行位**，
而 Python `tarfile.add()` 会把 `st_mode` 原样写进归档。于是镜像里所有 `0755`
静默变成 `0666`：

| 条目 | 原始 Docker layer | 朴素重打包后 |
|---|---|---|
| `usr/bin/bash` | `0o755` | **`0o666`** ✗ |
| `usr/bin/ls` | `0o755` | **`0o666`** ✗ |
| `usr/bin/chromium` | `0o755` | **`0o666`** ✗ |

（实测 19542 个文件丢了可执行位。）`proot-distro` 会忠实应用归档里的 mode，
于是 `/bin/bash` 真的不可执行，proot 直接拒绝启动。

**正确做法**：解析每个 layer 的 tar 元数据，记录 `路径 → mode`，
打包时显式写回 `TarInfo.mode`，**绝不能用 `os.stat()`**。
另外 `norm()` 不能用 `str.lstrip('./')` —— 那会把 `.bashrc` 前导点也吃掉。

回归测试：`app/src/test/.../RootfsPermissionsTest.kt` 直接读归档，
断言 `usr/bin/bash` / `chromium` / `usr/local/bin/python3.14` 等带可执行位，
并断言 `/usr/bin` 下不存在「非软链但不可执行」的条目。

### 6. 交给容器的路径必须是 `/data/data` 形式

`Context.getFilesDir()` 返回的是 **`/data/user/0/<pkg>/files`**，而 `/data/data`
只是指向 `/data/user/0` 的**符号链接**。两者在宿主上等价，
但 **proot-distro 只把 `/data/data/<pkg>` 这一个路径绑定进容器**——
容器里根本不存在 `/data/user/0/...`。

所以「宿主写文件、容器读文件」的场景（例如把部署脚本写给容器执行）必须规范化：

```kotlin
TermuxPaths.guestPath(file)   // /data/user/0/... -> /data/data/...
```

否则容器里的 bash 会报 `No such file or directory`（退出码 127）。
官方 Termux 也是这么做的：`replaceAll("^/data/user/0/", "/data/data/")`。

回归测试：`app/src/test/.../TermuxPathsGuestTest.kt`。

### 7. 容器已就绪时要复用，不要每次重装

`proot-distro install` 一次约 30 秒，更严重的是**重装会抹掉浏览器登录态**
（`/m7a/3rdparty/WebBrowser/UserProfile`），意味着每次部署都要重新扫码登录。

所以安装脚本先 `proot-distro list --quiet` 判断容器是否存在，
再用 `test -f /m7a/main.py` 验证它是否可用：可用就复用，只有坏掉才重装。

### 8. config.yaml 必须写到 `/m7a/config.yaml`

内置镜像的项目在 **`/m7a`**（不是 `~/March7thAssistant`）。
配置写错地方，March7thAssistant 会按默认的**本地游戏**模式启动，
而 Termux/proot 环境只能走**云游戏**模式，结果就是任务跑不起来。

写法：配置先写到宿主与容器共享的 `$HOME/m7a-shared/config.yaml`
（`$HOME` 在默认模式下以同一路径绑定进容器），再由容器内脚本
`cp -f` 到 `/m7a/config.yaml` 并创建 `logs/`、
`3rdparty/WebBrowser/UserProfile/`。设置页改完点「生成脚本」会把配置重新推进容器。

### 9. 扫码登录：二维码必须绑定出来显示

March7thAssistant 首次运行**必须扫码登录**。二维码由程序写到项目目录下的
`logs/qrcode_login.png`（见 `module/game/cloud.py` 的 `_save_qr_img`，
`logs_dir = "logs"`，相对工作目录 `/m7a`）。

**问题**：如果 logs 留在容器里，用户在手机上根本看不到这个文件，
也就无法用米游社 App 扫码 —— 功能等于没做完。

**解法**：启动任务时把容器内的 logs 目录绑定到宿主共享目录：

```
proot-distro login --bind=<共享logs>:/m7a/logs <alias> -- ...
```

于是二维码直接落在 `$HOME/m7a-shared/logs/qrcode_login.png`，
App 用 `BitmapFactory.decodeFile` 读出来显示在首页/任务页，点开可放大扫。

注意 `--bind` 的两个约束（都来自 proot-distro 的 `proot_cmd.py`）：
源路径必须**绝对**（它内部会 `os.path.abspath`），目标也必须绝对；
且 argparse 要求选项出现在**容器名之前**。回归测试覆盖了这个顺序。

二维码会过期刷新，所以 App 在任务运行期间每 2 秒按 `mtime + size` 轮询，
变化就重新解码。上游还会把二维码内容（URL）打进日志，作为扫码之外的备选。

### 10. 日志显示：不要用「包含 ERROR 就标红」

真机上用户看到这样一行被涂成红色，来问「这个报错重要吗」：

```
[W:onnxruntime:Default, device_discovery.cc:332] GPU device discovery failed:
Error: std::error_code ... Permission denied, "/sys/class/drm"
```

它其实是**无害警告**（onnxruntime 找不到 GPU 就回落 CPU，而我们本来就要 CPU），
而且上游文档早已列为已知可忽略项。被误标成错误的原因有两个，都是显示层的问题：

1. **朴素级别判断**：`line.contains("ERROR")` 命中了行内的
   `Error: std::error_code`。正确做法是**信任上游自己的级别标记**：
   `| DEBUG |`/`| INFO |`/`| ERROR |`（March7thAssistant），
   `[W:`/`[E:`（onnxruntime）。
2. **ANSI 颜色码没剥离**：界面上出现 `[94m`、`[0m`、`[0;93m` 这类垃圾字符。

**写这个正则时踩了自己的坑**：一开始用 `\[[0-9;]*[A-Za-z]` 剥颜色码，
它会把 `[W`、`[E`、`[ERROR` 也一起吃掉 —— 正好毁掉要读的级别标记。
SGR 颜色码必定以 `m` 结尾，据此收紧为 `\[[0-9;]*m`。这是单测抓出来的。

另外日志默认按级别过滤（默认隐藏 DEBUG），否则每帧操作的 DEBUG 会淹没真正的信息。

回归测试：`app/src/test/.../LogParsingTest.kt`（真实日志样本）。

### 11. 终端崩溃：`mRenderer` 必须先初始化

真机上点「终端」直接崩溃。根因在 `TerminalView`：

```java
public TerminalRenderer mRenderer;        // 声明，**没有初始化**
...
public void setTextSize(int textSize) {
    mRenderer = new TerminalRenderer(...);   // 唯一赋值点
    updateSize();
}
```

而 `updateSize()` 里是 `viewWidth / mRenderer.mFontWidth`。

所以崩溃是**时序问题**：
1. `attachSession()` → `updateSize()`：此刻视图还没布局，宽高为 0，
   函数**提前 return**，所以当时不炸 —— 掩盖了问题；
2. 视图布局完成 → `onSizeChanged()` → `updateSize()`：宽高已非 0，
   而 `mRenderer` 仍是 `null` → **NullPointerException**。

官方 Termux 的 `TermuxActivity` 在 `onCreate` 里**一定会先调用
`setTextSize()`**，我漏了这行。修复后顺带支持了双指缩放字号
（官方也是这个行为），并加了一层兜底：终端初始化失败时在界面上
显示异常与堆栈，而不是让整个 App 崩掉（测试版便于反馈）。

> 教训：直接复用上游 View 时，「上游 Activity 一定会做的前置初始化」
> 是最容易漏的。这里漏一行就是一个必崩的 NPE。

### 12. 改 config.yaml 必须**增量**修改，绝不能整份覆盖

`module/config/config.py` 会把**整份合并后的配置**写回文件：

```python
def save_config(self):
    with os.fdopen(tmp_fd, 'w', encoding='utf-8') as file:
        self.yaml.dump(self.config, file)      # ← 整份 dump
```

而 `Config.__init__` 是 `self.config = _load_default_config(example_path)`
（先读 `config.example.yaml` 当默认值）再 `_load_config()` 合并用户文件。

所以**首次运行之后**，`config.yaml` 里就包含全部键，包括大量**状态字段**：

```yaml
echo_of_war_timestamp: 1730000000
weekly_relic_cleanup_timestamp: 1730000001
universe_timestamp: 0
daily_tasks: []
power_plan:
- stage: 拟造花萼（金）
  times: 6
```

**整份覆盖 = 把这些状态清零**，程序会以为「历战余响没打过」「每周遗器没清过」
而重复执行，用户手填的 `daily_tasks` 也会丢。

正确做法（`ConfigPatcher.upsertTopLevel`）：
1. 先用 `proot-distro login <alias> -- /bin/cat /m7a/config.yaml > <共享文件>`
   把容器内现有配置拉出来当基底；
2. 只替换我们管理的**顶层键**，其它行（注释、嵌套、状态字段）一个字节不动；
3. 再 `cp` 回容器。

`ConfigPatcherTest` 专门断言「状态字段与多行嵌套结构必须原样保留」。

### 13. 键名必须与上游 `config.example.yaml` 完全一致

`Config._update_config()` 只覆盖**已存在于默认配置里的键**：

```python
for key, value in new_config.items():
    if key in config:        # ← 未知键被静默忽略
        config[key] = value
```

拼错一个字母 = 等于没写，而且**不会报错**。

真机上踩到的实例：我早期生成的配置里写了 `run_daily_time`，
但上游**没有**这个键 —— 它是旧文档（`m7a.top` 的 Termux 部署页）里的写法。
当前版本真正的定时键是：

```yaml
loop_mode: scheduled      # 或 power（按体力计划循环）
scheduled_time: "04:00"
```

所以那个「每日运行时间」设置**一直没生效**。现已改为写真实键，
并且设置页可以选 `loop_mode`。

> 教训：跨版本抄配置键时，必须回到当前版本的 `config.example.yaml` 核对，
> 而不是相信文档或旧记忆。

### 14. 实时监看：CDP screencast

**目标**：在 App 里看到云游戏的实时画面（「监视器」）。

**可行性依据**（都是实测的，不是猜的）：

1. `module/game/cloud.py` 启动浏览器时**本来就带调试端口**：
   ```python
   options.add_argument(f"--remote-debugging-port={actual_port}")
   ```
   端口来自 `browser_debug_port` 配置（默认 **9222**）；若被占用则
   `_find_available_port(configured_port)` **递增**找空闲端口。
2. **proot 不隔离网络**，所以容器内 `127.0.0.1:<port>` 在 App 侧同样可达。
3. DevTools 支持多客户端连同一 target。Selenium 已经连着，我们再连一个，
   调 `Page.startScreencast` 收 JPEG 帧即可。

**实现**：`monitor/CdpMonitor.kt`
- 按 `9222 ~ 9242` 范围探测 `GET /json/version`（端口未开是立刻 ECONNREFUSED，很快）
- `GET /json/list` → 按规则选页面（只要 `type=page`、排除 `devtools://`、
  优先 URL 含 `mihoyo`/`cloud`/`sr.`）
- OkHttp WebSocket 连上 → `Page.enable` + `Page.startScreencast`
- 收到 `Page.screencastFrame` → base64 解码 → Bitmap 显示

**两个必须注意的点**：

- **每一帧都要 `Page.screencastFrameAck`**，即使我们因为限帧而不显示它。
  不 ack 的话 Chromium 会停止推流。代码里 ack 在限帧判断**之前**。
- **端口不能写死**：上游会递增。所以 `CdpDiscovery.portCandidates()`
  按范围探测（有单测覆盖）。

### 15. targetSdk 28 下连 `127.0.0.1` 也会被拦（明文流量）

这是个**会直接导致功能完全不可用**、但报错信息不直观的坑：

Android 9（API 28）起，`android:usesCleartextTraffic` 默认是 **false**，
而且**对回环地址同样生效**。于是在 App 里请求
`http://127.0.0.1:9222/json/version` 会抛：

```
java.net.UnknownServiceException: CLEARTEXT communication to 127.0.0.1
not permitted by network security policy
```

解决：加 `res/xml/network_security_config.xml` 并在 manifest 里引用
`android:networkSecurityConfig`。本应用只对回环地址用明文，
对 GitHub 等外部服务仍走 HTTPS。

> 验证方式：`aapt2 dump xmltree --file AndroidManifest.xml <apk>` 应看到
> `networkSecurityConfig=@0x7f...`；再 dump 那个 xml 资源应看到
> `cleartextTrafficPermitted=true`。

### 16. 后台保活：任务不能归 ViewModel 所有

**先说一个设计错误**（早期版本真实存在）：

```kotlin
// ✗ 错：ViewModel 持有任务进程，并在 onCleared 里销毁它
private var taskProcess: Process? = null
override fun onCleared() { taskProcess?.destroy() }
```

ViewModel 的存活范围 ≈ Activity。一旦 Activity 因旋转、内存压力或
「返回后台后被销毁」而回收，**正在挂机的任务就被自己人杀了**。

**改法**：任务进程归单例 `TaskRunner`（只要 App 进程活着就在），
ViewModel 只是观察者 —— 通过 `TaskRunner.state` / `TaskRunner.lines`
重新接上，重建多少遍都不影响任务。

### 17. 保活靠前台服务 + WakeLock

任务跑在 App 的**子进程链**里：

```
app → bash → proot-distro(python) → proot → bash → python → chromium
```

App 进程一死，整条链跟着死。所以 `TaskKeepAliveService`：

- **前台服务 + 常驻通知**：提升进程优先级，让系统不当成可随意回收的后台进程；
  通知里带「停止任务」按钮（`IMPORTANCE_LOW`，挂机时不响铃）
- **`PARTIAL_WAKE_LOCK`**：阻止 CPU 休眠。**不加这个，息屏后自动化就停住了**
- **`onTaskRemoved` 不停止服务** + 清单里 `stopWithTask="false"`：
  用户从最近任务划掉界面，任务继续跑
- `START_STICKY`；被系统拉起时若发现任务已不在（进程死过）就自行停止，
  不假装还在运行

### 18. 国产 ROM 的额外限制（无 API 可绕过）

前台服务能挡住 AOSP 的回收，但小米/华为/OPPO/vivo 还有自己的后台管理。
设置页「后台保活」里给了引导：

1. **电池优化加入白名单** —— 这是 AOSP 层面有明确接口的一项
   （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，需
   `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限），能直接弹系统对话框
2. 允许「自启动」/「后台弹出界面」—— 只能引导到应用详情页
3. 省电策略设为「无限制」—— 同上

设置页会实时显示「前台服务：运行中/未运行」「电池优化：已/未在白名单」。

> 另注：Android 12+ 会回收 App 派生的「幽灵进程」。
> 处于前台（含前台服务）的 App 额度更高，但 Chromium 自身就派生几十个进程，
> 仍可能触顶。若任务莫名中断，优先怀疑这里 —— 这也是上面第 1 条的价值所在。

### 19. 「安装容器成功」≠「proot 能跑」

`proot-distro install` **只是解压 tar，完全不会调用 `proot`**。
所以部署流程里必须有一步**真正启动 proot** 的冒烟测试，否则会把
「proot 起不来」误判成「容器内脚本有问题」。

部分设备/内核下 proot 因 seccomp / ptrace 限制无法启动，需要
`PROOT_NO_SECCOMP=1`。流程会自动探测：先按默认方式跑一次
`proot-distro login <alias> -- /bin/echo PROOT_SMOKE_OK`，
失败就带 `PROOT_NO_SECCOMP=1` 再试一次；成功的话把该变量写进
`$HOME/m7a-shared/proot-env`，后续所有容器操作（含任务运行、`start-m7a.sh`）
自动沿用，不必每次试错。

同时**刻意不设置** `PROOT_LOADER` / `PROOT_LOADER_32` / `PROOT_TMP_DIR`：
官方 Termux 与 proot-distro 都不设它们，proot 会用内置 loader 和与
`$PREFIX` 绑定的默认值。显式覆盖属于自作聪明——loader 文件与 proot
内嵌版本不匹配时会直接启动失败。

### 7. 失败信息必须带真实输出

只报「退出码 1」对排查毫无价值。`DeployEngine` 维护最近 60 行输出，
任何步骤失败时会把**末尾 3 行**拼进步骤说明，直接显示在部署页上。
这个改动正是定位上面权限问题的关键——`proot error: '/bin/bash' is
not executable` 一眼就指出了方向。

---

## 三、工程结构

```
MaaTermux/
├── app/                                  # 主应用（Compose UI + Termux 集成）
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── bootstrap/termux-bootstrap.zip   # 40MB Termux 根文件系统（含 proot-distro+python）
│   │   │   └── rootfs/m7a-rootfs.bin            # 497MB 离线 Debian 容器镜像
│   │   ├── java/com/miguanm7a/hsr/
│   │   │   ├── termux/
│   │   │   │   ├── TermuxPaths.kt           # $PREFIX / $HOME 定义与一致性校验
│   │   │   │   ├── TermuxEnvironment.kt     # 子进程环境变量（含 termux-exec preload）
│   │   │   │   ├── BootstrapInstaller.kt    # ★ zip 解压 + 软链重建 + 权限恢复
│   │   │   │   └── TermuxShell.kt           # 直接 fork bash 执行脚本
│   │   │   ├── deploy/
│   │   │   │   ├── DeployModels.kt          # 软件源/镜像源/任务枚举
│   │   │   │   ├── DeployScripts.kt         # ★ 手册 → 幂等 shell 脚本生成器
│   │   │   │   ├── DeployEngine.kt          # 一键部署状态机 + 进度协议解析
│   │   │   │   ├── RootfsInstaller.kt       # 把内置镜像释放到 Termux 侧
│   │   │   │   └── DeploySettings.kt        # DataStore 持久化
│   │   │   └── ui/                          # Compose（MAA Meow 设计语言）
│   │   │       ├── MainScreen.kt            # HorizontalPager + 底部导航
│   │   │       ├── screens/                 # 首页/部署/任务/日志/设置
│   │   │       └── terminal/TerminalActivity.kt  # 内嵌真实终端
│   │   └── AndroidManifest.xml
├── terminal-emulator/                    # Termux 终端模拟器（上游 v0.118.x，改写为 AGP 9 + CMake）
├── terminal-view/                        # Termux 终端 View
├── gradle/libs.versions.toml
└── build.gradle.kts
```

界面设计令牌（圆角、间距、阴影、配色）取自 MAA Meow 的
`MaaDesignTokens` / `Theme.kt`，强调色提供「三月七粉」与「MAA 蓝」两套。

---

## 四、构建

### 环境要求

| 项 | 版本 | 说明 |
|---|---|---|
| JDK | **17+**（实测 25.0.3） | Gradle daemon 用 |
| Android SDK | `compileSdk 37`（`android-37.0`）| AGP 9 支持带小版本号的平台目录 |
| NDK | **29.0.14206865**（r29） | 编 `libtermux.so` |
| CMake | **3.22.1** | 同上 |
| Gradle | 9.6.0（wrapper 已带） | |
| AGP | 9.2.1 | |

> NDK 与 CMake 可用 `sdkmanager` 安装：
> `sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"`

### 命令

```bash
# debug
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk  （约 553MB）

# release
./gradlew :app:assembleRelease
```

Release 签名通过 `local.properties` 或环境变量提供：
`KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。
未配置时 release 不打签名。

---

## 五、资产是怎么来的（可复现）

两个大资产不在 Git 里，需要按下面步骤生成。

### 5.1 bootstrap（40MB）

1. 从 Termux 官方 APK 里取出 bootstrap：`libtermux-bootstrap.so` 里的
   `blob`（`.incbin` 的 zip）位于 ELF 头之后，扫描 `PK\x03\x04` / `PK\x05\x06`
   即可定位并裁出完整 zip（v0.118.3 的偏移是 1328）。
2. 把 `proot` + `proot-distro` 及其依赖装进去：
   从 `https://packages-cf.termux.dev/apt/termux-main` 取 16 个 `.deb`
   （`proot`、`proot-distro`、`python`、`python-pip`、`libtalloc`、
   `libandroid-shmem`、`libsqlite`、`liblzma`、`libbz2`、`libexpat`、
   `libffi`、`gdbm`、`libcrypt`、`ncurses-ui-libs`、
   `libandroid-posix-semaphore`、`zstd`，合计 7.7MB）。
3. 解 `.deb`（`ar` → `data.tar.{xz,zst}`）时注意：**包内路径是绝对的**
   `./data/data/com.termux/files/usr/...`，必须剥掉前缀再并入 bootstrap。
4. 重新生成 `SYMLINKS.txt`（重新扫描软链）并重新打 zip。

### 5.2 离线容器镜像（497MB）

```bash
# 通过南京大学 ghcr 镜像拉取（直连 ghcr.io 在国内会卡在 265MB 那层）
REG_MIRROR=ghcr.nju.edu.cn python pull_mirror.py    # 15 层，515MB，约 90 秒
python assemble_rootfs.py                            # 应用 OCI whiteout 规则，重打 tar.gz
```

镜像的 arm64 子清单摘要：
`sha256:58341961e8616a33172df0d55455becf85841eb3e6d7625155c91f69423118fa`

> `assemble_rootfs.py` 会按 OCI 规范处理 `.wh.` / `.wh..wh..opq` whiteout，
> 否则上层删除的文件会留在 rootfs 里。

---

## 六、已知限制

诚实列一下没验证到的地方，避免误导。

1. **真机验证进度。** 已确认通过：应用安装、UI 渲染、**bootstrap 解压成功**、
   **能从私有目录 `exec` 出 `bash`**（W^X 风险已排除）、`proot-distro` 可运行、
   **本地离线镜像成功装成 proot 容器**。
   待确认：修复权限位后 `proot` 能否启动，以及容器内跑 `main.py` 的完整链路。
   > 历史修复轨迹（都是真机上撞出来的）：
   > - 1.0.0：`Stream closed` —— `bufferedReader().useLines()` 关闭了
   >   `ZipInputStream`（见二.3）
   > - 1.0.1：修复上条 + 加回归测试
   > - 1.0.2：新增 proot 冒烟测试；失败时回显真实输出；
   >   去掉非标准的 `PROOT_*` 环境变量覆盖
   > - 1.0.3：**修复 rootfs 可执行位丢失**（见二.5）——
   >   Windows 上重打包导致 `proot error: '/bin/bash' is not executable`
   > - 1.0.4：**修复宿主/容器路径不一致**（见二.6，`/data/user/0` 必须
   >   规范化为 `/data/data`）；config.yaml 改写到 `/m7a/config.yaml`（二.8）；
   >   容器已就绪时复用而非重装（二.7，保住登录态）
   > - 1.0.5：**扫码登录可用**（见二.9）—— 把容器 logs 绑定到共享目录，
   >   二维码在 App 内直接显示，可点开放大用米游社扫
   > - 1.0.6：**日志显示修正**（见二.10）—— 剥掉 ANSI 颜色码；
   >   级别判断改为信任上游标记，不再把 onnxruntime 的无害警告涂红；
   >   日志默认隐藏 DEBUG 刷屏
   > - 1.0.7-beta：**修复终端崩溃**（见二.11，`mRenderer` 未初始化导致
   >   `onSizeChanged` NPE）；终端支持双指缩放字号；终端初始化失败改为
   >   界面显示异常而非崩溃；版本号加 `-beta` 标记
   > - 1.0.8-beta：**任务开关可自定义**（见二.12/二.13）——设置页可配置
   >   28 个 `*_enable` 开关，决定「完整运行」跑哪几项；改用**增量**修改
   >   config.yaml，不再覆盖状态字段；修正失效的 `run_daily_time` 为真实键
   >   `loop_mode` + `scheduled_time`
   > - 1.0.9-beta：**新增实时监看**（见二.14）——通过 CDP screencast 显示
   >   云游戏画面；顺带解决 targetSdk 28 下回环地址明文流量被拦的问题（二.15）
   > - 1.0.10-beta：**后台保活**（见二.16~18）——任务进程改归
   >   `TaskRunner` 单例所有（不再随 ViewModel 被销毁）；新增前台服务
   >   `TaskKeepAliveService` + `PARTIAL_WAKE_LOCK`；支持从最近任务划掉后继续运行；
   >   设置页可申请电池优化白名单；清理了清单里两个**并不存在的** service 声明
   >
   > **1.0.9-beta 的实时监看尚未在真机验证。** 已确认的只有：
   > 编译通过、清单里 `networkSecurityConfig` 与 `MonitorActivity` 正确注册、
   > 编译后的网络配置确实含 `cleartextTrafficPermitted=true`、
   > 端口探测与 target 选择逻辑有 13 个单测。**未确认**的是运行时行为：
   > DevTools 是否接受第二个客户端同时 `startScreencast`。
   > 连不上的原因会全部打印在监看页的「诊断输出」里。

2. **只有 arm64-v8a。** `abiFilters` 只保留 `arm64-v8a`，因为内置 bootstrap
   与容器镜像是 aarch64 的。x86_64 需要各自重建资产。

3. **APK 体积 553MB。** 内置了 40MB bootstrap + 497MB 容器镜像。
   不适合上 Google Play，适合 GitHub Releases / 侧载。

4. **释放镜像需要额外空间。** 安装时会把镜像再复制一份到
   `/data/data/com.termux/files/home/m7a-shared/`，加上 proot-distro 解压后的
   容器，`/data` 至少需要 **2.5GB** 余量。

5. **与官方 Termux 冲突。** 包名 `com.termux` 相同，不能共存。

6. **联网镜像路径未实测。** 内置源已完整；`ghcr.io` / 南大镜像 / 官方 Debian
   这几条联网路径的脚本已按 proot-distro 5.9.0 的 CLI 写好，但没在真机跑过。

7. **March7thAssistant 的上游问题照旧。** 教程 FAQ 里那 4 个已知问题
   （云游戏「等待时间较长」弹窗、onnxruntime 警告、OpenVINO OCR 回退、
   proot 下 `free(): invalid next size` 崩溃）是 proot / 安卓内存限制导致的，
   本应用无法消除。

8. **许可证。** March7thAssistant 是 AGPL 系；Termux 各模块为 MIT。
   分发前请自行确认许可证兼容性。

---

## 七、致谢

- [Termux](https://github.com/termux/termux-app) — 终端模拟器与 bootstrap（MIT）
- [proot-distro](https://github.com/termux/proot-distro) / [proot](https://github.com/proot-me/proot) — 用户态容器
- [MAA Meow](https://github.com/Aliothmoon/MAA-Meow) — 界面设计语言来源
- [March7thAssistant](https://github.com/moesnow/March7thAssistant) — 自动化目标项目
