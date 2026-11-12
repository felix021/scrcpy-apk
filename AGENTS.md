# AI 协作入口

本项目是 Android 控制 Android 的独立客户端，支持传统 / TLS 网络 ADB、无线配对和 USB Host ADB。主应用包名为 `cn.ohmyterm.scrcpy`。先读本文件，再按任务打开需要的主题文档；不必一开始读取全部历史记录。

## 从任务定位上下文

| 任务 | 先读 | 主要代码入口 |
| --- | --- | --- |
| 了解产品、操作方式与限制 | [使用说明](docs/usage.md) | `MainActivity`、`RemoteKeyboard` |
| 修改连接、视频、ADB 或关闭行为 | [架构与约束](docs/architecture.md) | `ScrcpySession`、`UsbAdb*`、`com/tananaev/adblib/` |
| 修改配置、默认值、语言或兼容旧数据 | [配置与数据](docs/configuration.md) | `Profile`、`MainActivity`、`UiText` |
| 修改导航、边缘手势、键盘、横屏布局 | [架构与约束](docs/architecture.md)、[验证指南](docs/testing.md) | `RemoteInputView`、`EdgeGesture`、`RemoteKeyboard`、`KeyboardState`、`SafeRail` |
| 修改声音、麦克风或远端旋转 | [架构与约束](docs/architecture.md)、[验证指南](docs/testing.md) | `AudioForwarder`、`MicForwarder`、`MicServer`、`RemoteDeviceSettings` |
| 准备开发环境、构建 | [开发指南](docs/development.md) | `app/build.gradle`、`scripts/prepare-server.sh` |
| 调查连接、设备、声音故障 | [排障指南](docs/troubleshooting.md) | 按日志标签定位 |
| Google Play 上架 | [Google Play 指南](docs/google-play.md)、[发布指南](docs/release.md) | `play/`、`app/build.gradle`、商店签名配置 |
| 交付 APK、签名、依赖升级 | [发布指南](docs/release.md)、[第三方说明](third_party/README.md) | 构建配置、下载脚本、许可文件 |
| 更新文档、查历史证据 | [文档索引与维护规则](docs/README.md)、[验证记录索引](docs/validation/README.md) | 当前主题文档与对应版本记录 |

应用 Java 文件位于 `app/src/main/java/cn/ohmyterm/scrcpy/`，ADB 库位于 `app/src/main/java/com/tananaev/adblib/`。文件用途详见架构文档。

## 开工与修改规则

1. 先检查工作区、当前分支和已有变更；在独立 worktree 中修改，保留其他任务的改动。目录可用 `$HOME/worktrees/scrcpy-apk/<task>`。
2. 用源码核对文档中的当前行为。构建版本以 `app/build.gradle` 为准；server 下载版本与哈希以 `scripts/prepare-server.sh` 为准。历史验证记录只证明当时的结果。
3. 改动聚焦任务，沿用原生 Java / Android View 和现有深色圆角样式。避免为小改动引入框架或无关重构。
4. 修改配置时保留旧 JSON 的缺省值兼容；修改界面时同时检查中英文、左右横屏、系统安全区和键盘遮挡。新增用户可见文案放入两套字符串资源。
5. 明确区分已完成、已验证、尚未验证和用户反馈。发现不确定先核对证据；无法核实时说明边界，不把推测写成事实。

## 必须保留的行为

- 配对只使用真实 AOSP QR / 六位码协议；保留 STLS 升级、配对取消与过期回调隔离。名称、TLS 和密钥规则见架构与配置文档；秘密不写日志。
- TCP、TLS 配对与 USB 共用 ADB 身份、逻辑通道和会话功能。USB 不自动回退 TCP，USB 权限的过期回调不能启动新会话。
- 音频播放与本机麦克风独立开关；麦克风默认关闭。仅观看不发送控制、麦克风或方向变更。
- 运行时开关和语言切换不重建视频连接。异步结果必须属于当前会话，不能覆盖后续连接的状态。
- 关闭会话要释放触点、修饰键、声音资源、传输和本会话的远端服务，并恢复临时改变的旋转与保持唤醒设置。
- 远端服务按会话标识管理，只终止自己启动的进程。禁止用广泛的进程匹配终止其他会话。
- 普通应用无法彻底接管本机系统手势；保留安全区，并在远控画面边缘识别远端动作。

实现原因和对应源码见 [架构与约束](docs/architecture.md)，不要只凭本列表修改协议。

## 验证与交付

在已经配置好 JDK 17+ 和 Android SDK 36 的环境中：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 scripts/check-docs.py
```

- 按改动选择 [验证阶段](docs/testing.md)，不把 `--phase all` 当成全部专项的合集。仅文档修改执行文档检查即可，无需构建 APK 或改应用版本。
- 设备验证只使用本任务拥有或明确获准使用的设备，所有 adb 命令明确指定序列号。验证脚本会安装应用并改变设备状态，先读验证指南；不要操作其他任务的模拟器或真机。
- APK、构建目录、日志、截图、server 下载文件、SDK 配置和密钥不进入 Git。临时材料放 `$HOME/tmp/`；记录使用占位地址，不提交私人网络信息或凭据。
- 发布遵循当前任务已有授权，保持仓库原有可见性；提交不添加 AI coauthor。APK 签名、版本与下载校验见发布指南。
- 功能改动同步更新对应主题文档；行为变化按需补充版本验证记录，并维护索引。README 中英文成对更新。交付说明包含改动、实际验证和未覆盖范围。
