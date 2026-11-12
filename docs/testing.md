# 验证指南

[文档索引](README.md) · [开发环境](development.md) · [历史证据](validation/README.md)

## 按改动选检查

| 改动 | 最小检查范围 |
| --- | --- |
| 纯文档 | `python3 scripts/check-docs.py`、`git diff --check` |
| 纯协议 / 配置 / 键盘状态 | 对应 JVM 测试，构建与 lint；行为影响到设备时补对应专项 |
| UI、语言、弹窗、键盘、横屏 | 构建与 lint，实际 AVD 专项，检查中英文和竖屏 / 左右横屏截图 |
| ADB、USB、关闭或服务生命周期 | JVM、真实 TCP 视频 / 触控、生命周期；USB 另需实机链路 |
| 声音 / 麦克风 / 运行时开关 | 对应声音专项、权限拒绝与清理，不以开关外观代替实际音频测量 |
| 方向同步 / 保持唤醒 | 方向与运行时专项，检查关闭、断开、强制结束后的远端设置 |

通用构建命令见 [开发指南](development.md)。JVM 测试覆盖控制协议、ADB 流、USB 分包和取消、配置兼容及键盘 / 手势状态。界面行为需用实际 UI 验证，单元测试不能证明已经操作到远端。

## 设备归属和副作用

[scripts/verify-avd.py](../scripts/verify-avd.py) 会在主控 AVD 安装 / 启动主应用，在远端安装一次性探针，修改测试配置、语言和旋转，部分阶段还改变屏幕尺寸、声音或前台应用。使用本任务拥有、可接受这些改动的设备；所有 adb 操作指定序列号。连接真实手机须有本轮或既有授权，不占用其他任务正在使用的机器。

每个设备组合同时只跑一轮 UI 验证。并发运行会互相点击、清理探针、改变方向，产生难以解释的失败。使用独立 adb server 时，Python 脚本的所有子进程继承 `ADB_SERVER_SOCKET`；直接 adb 命令也用同一环境。

脚本 `finally` 会尝试恢复捕获的旋转值，适用阶段重置屏幕尺寸，并停止 / 卸载探针。它不是完整设备快照，不能假设原有应用配置或用户数据完全恢复。崩溃 / 离线后检查残留状态，只清理本轮创建的资源。

## 两台 AVD 的准备示例

准备 Android 14、1080×2340 的专用 AVD，一台运行客户端，一台作为 ADB TCP 被控端。脚本针对这一屏幕规格编写，其他规格需检查坐标与控件可达性。AVD 名称、序列号和端口由本任务分配，不照搬历史设备。

[start-avd.sh](../scripts/start-avd.sh) 会去掉继承的宿主机代理，避免代理影响原始 ADB TCP。启动命令保持运行，终端会占用，必要时在自己的进程管理工具中后台运行：

```bash
scripts/start-avd.sh '<avd-name>' '<console-port>'
```

为两台 AVD 分别启动进程。设置序列号等变量，替换尖括号内容；以下 5039、15555 是示例端口，使用前检查空闲与归属：

```bash
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5039
export SCRCPY_CLIENT_SERIAL='<client-avd-serial>'
export SCRCPY_TARGET_SERIAL='<target-avd-serial>'
adb start-server
adb devices
adb -s "$SCRCPY_TARGET_SERIAL" tcpip 5555
adb -s "$SCRCPY_TARGET_SERIAL" forward tcp:15555 tcp:5555
```

标准 Android Emulator 中，客户端通过 `10.0.2.2` 访问宿主机，再经宿主机 15555 转发到被控 AVD 的 adbd。该地址是模拟器的特殊网关地址；真实手机应填写它实际可访问的目标地址。主机已授权目标 adb 不等于客户端应用已授权，首次仍需在被控端批准应用生成的密钥。

若 AVD 重启后独立 adb server 没有自动发现它，可用 `adb connect '127.0.0.1:<target-adb-port>'` 连接本任务模拟器的本机 ADB 桥接端口，再将 `adb devices` 返回的地址作为 `--target`。桥接端口通常为该 AVD 控制台端口加一，操作前核对分配；无需重启影响其他设备的 adb server。

## 执行和证据

先构建主应用与探针，再从工作树根目录执行：

```bash
export SCRCPY_ARTIFACTS="$HOME/tmp/scrcpy-apk/$(date +%Y%m%d-%H%M%S)-app-settings"
mkdir -p "$SCRCPY_ARTIFACTS"
python3 scripts/verify-avd.py \
  --client "$SCRCPY_CLIENT_SERIAL" --target "$SCRCPY_TARGET_SERIAL" \
  --host 10.0.2.2 --port 15555 --phase app-settings \
  --output "$SCRCPY_ARTIFACTS"
```

指定其他构建可加 `--apk app/build/outputs/apk/release/app-release.apk`，验证实际交付的 release 包。部分配置专项通过 `run-as` 读取应用私有数据，需要可调试构建；release 包可运行 `app-settings`、`language-live`、`controls`、`audio-runtime` 等不依赖主应用 `run-as` 的阶段。

每次使用新输出目录；它包含 `result.json`、截图以及失败时可用的日志。记录完整命令参数的泛化形式、退出码、通过项和未覆盖边界。`result.json` 中已有通过项不意味着整轮通过：后续仍可能失败，必须看进程退出状态。临时文件和原始日志不提交 Git。

首次系统全屏提示、ADB 授权及录音授权可能需要处理。录音权限相关分支由声音阶段检查。遇到失败先查看截图 / 日志，确认当前设备、窗口和服务状态，再选择补跑阶段；不降低测试阈值掩盖失败。

## 阶段索引

`--phase all` 是核心流程，包含配置持久化、真实视频与控制、H.265、仅观看、参数校验和生命周期检查；它不调用下表全部专项。参数来源以脚本 `--help` 和分支实现为准。

| 阶段 | 主要覆盖 |
| --- | --- |
| `--phase all` | 核心端到端流程 |
| `--phase lifecycle` | 后台断开、释放触点、取消后重连、远端服务退出 |
| `--phase ui` | 配置页与边缘手势 |
| `--phase controls` | 虚拟导航、四边动作、完整键盘与横屏 |
| `--phase keyboard` / `--phase keyboard-layout` | 六行键盘、上下滑输入、修饰键 / 文字；layout 为布局侧检查分支 |
| `--phase manual-rotation` / `--phase manual-rotation-controls` | 手动旋转、半透明设置、Win 组合键与修饰状态清理 |
| `--phase configs` | 多主机保存、切换与配置隔离 |
| `--phase insets` | 顶部留白、系统安全区与画面边缘 |
| `--phase appearance` | 图标、配置选择 / 取消、二次删除确认 |
| `--phase dialogs` / `--phase text-dialogs` | 保存 / 文字弹窗、输入校验、IME 与固定底部按钮 |
| `--phase landscape` | 左右横屏侧栏、控制键与圆角 / 中置摄像头避让 |
| `--phase rotation` | 自动方向与断开 / 强制结束后的远端设置恢复 |
| `--phase audio` / `--phase audio-runtime` | 播放 / 麦克风四种组合、真实音频测量、录音权限；runtime 保持同一视频连接 |
| `--phase runtime` | 运行时开关与配置记忆、服务停止、仅观看限制 |
| `--phase language` / `--phase language-live` | 中英文、跟随系统、表单保留与滑动提示；live 验证视频会话保持 |
| `--phase usb-ui` | USB / TCP 配置、权限前的界面边界、重启恢复、TCP 视频 / 触控回归 |
| `--phase app-settings` | 应用设置、版本 / 许可 / 仓库入口、语言保留、左右横屏和 Back |

## 声音与 USB 的证据边界

声音测试用不同测试音测量本机真实输出与远端普通录音结果，覆盖播放 / 麦克风四种组合。音频路由建立后需重新固定媒体音量，结束时恢复原值，避免设备切换后继承旧音量；测量细节见 [0.11 记录](validation/0.11.md)。

AVD 之间的 TCP 连接可以验证共用会话功能，JVM 可以模拟 USB 包、授权与取消，但两者都不能代替 USB Host / OTG 实机链路。USB 实机关注角色协商、本机访问 / 远端授权、持续画面与操作、声音、拔线退出等。当前 USB 功能已经用户实机验收通过，型号和逐项明细未单独记录；不要把它扩写成所有设备均已通过。

归档新结果时使用 [验证记录约定](validation/README.md)，历史验收不自动证明当前改动没有回归。

## 无线配对专项

脚本先构建 `:app:assembleDebug :app:assembleDebugAndroidTest`，构建失败即停止，不安装旧 APK。无线专项使用自有 instrumentation runner，仅通过 [verify-wireless.py](../scripts/verify-wireless.py) 指定两台设备运行；不要让 Gradle 自动遍历所有已连接设备。

```bash
python3 scripts/verify-wireless.py \
  --client '<owned-client-serial>' --target '<owned-target-serial>' \
  --host '<host-address-reachable-from-client>' \
  --pair-forward '<unused-local-port>' --connect-forward '<unused-local-port>' \
  --bridge-port '<unused-local-port>' --output '<private-temporary-directory>'
```

目标使用可调试的 Android 16 AVD，预先启用并信任其无线调试 Wi-Fi。脚本会安装主控 / test APK、向目标临时复制 test APK 内的系统配对 fixture，分别执行错误码、正确六位码与二维码配对，进入真实 TLS 视频首帧，再打开设置、确认断开；最后回归传统 TCP 视频与断开（默认目标端口 5555，可用 `--classic-port` 指定）。它还核对 Android 保存的完整名称。`--modes code` 可只运行单项，`--apk <built-release.apk>` 可验证最终签名包；模拟器网关不可达时用 `--host 127.0.0.1 --reverse`，脚本只创建并清理自己成功绑定的端口，已占用的端口会拒绝覆盖；`ADB_SERVER_SOCKET` 传递给所有 adb 子进程。

二维码专项解码主控界面实际显示的图像，把内容交给目标系统配对入口；为跨 AVD NAT 的设备发现和网络流建立临时 NSD / TCP 代理。这个结果不能代替实体相机扫码验收。代理只在测试 APK 内，生产 APK 不包含测试入口。所有临时端口、注册和目标 fixture 在结束时清理。系统配对服务是单例，每次先取消旧服务，禁止并发运行。

目标 `dumpsys servicediscovery` 同时包含当前服务和历史日志；脚本只接受当前活跃 Advertiser ID 对应的端口，不能拿历史记录里的旧配对端口做新一轮测试。六位码与 QR 秘密不写公开报告，原始输出保存在仓库外。专项会改主控语言、本机名称和配置，使用可接受这些变更的专用设备。

中英文配对布局可在明确指定的主控上运行专项（不连接目标）：

```bash
adb -s '<owned-client-serial>' shell am instrument -w -r \
  -e mode layout -e language en \
  cn.ohmyterm.scrcpy.test/cn.ohmyterm.scrcpy.WirelessPairingTest
```

语言改为 `zh` 再运行一次。专项实际编辑本机名称，检查竖屏与左右横屏的二维码可解码、底部按钮完整可见；测试截图保存在专用主控的私有文件中，包含临时配对秘密，取出后仅存私有临时目录，不能提交或公开。

## Google Play 构建验证

使用 Play 上传证书验证时，设置私有签名环境并给 `verify-wireless.py` 传 `--variant play`，脚本会构建证书匹配的 `PlayAndroidTest`；默认仍为 debug。直接构建可用 `-PscrcpyTestBuildType=play :app:assemblePlayAndroidTest`。测试 APK 使用 `adb install -t` 安装，不能把测试 APK 当作商店交付包。

Android 16 专项检查配置页避开强制边到边的系统栏，并在远控时通过新系统返回回调把 Back 发到远端。无线回归会先把目标放在 Settings 首页，然后通过本机系统 Back 验证远端回到桌面且连接保持；不能只检查本机按钮外观。

配对 fixture 需要在明确归属本任务的可调试目标 AVD 上执行 `adb root`；脚本会先检查 UID，不能用于个人真机。首次全屏提示由测试只针对 System UI 的「Got it」按钮关闭。配对完成后才重置远端 Settings 首页，避免系统配对广播改变返回测试的起点。

`--screenshots --modes code` 可把实际配置页、远控、键盘和运行时设置截图保存到主控 App 的私有文件 `store-en-*.png`；提取到仓库外并人工检查后才用作商店素材。失败截图也仅保存在专用主控，禁止把二维码或配对秘密公开。
