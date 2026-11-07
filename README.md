# scrcpy APK

**简体中文** · [English](README.en.md)

在一台 Android 手机上查看并控制另一台 Android 设备，支持网络 ADB 和数据线 USB ADB。客户端独立完成连接、视频解码和控制，无需电脑中转，也无需在被控端安装常驻应用。

- 保存并切换多台设备的完整连接配置，支持 H.264 / H.265 和画质快捷档。
- 触控、多指、返回 / Home / 最近任务、四边手势，以及六行 QWERTY 虚拟键盘。
- 远端系统声音 → 本机、本机麦克风 → 远端两个独立开关，默认关闭麦克风转发。
- 竖屏与横屏布局、远端方向同步、手动旋转、仅观看和保持唤醒。
- 深色设置界面，支持中文 / 英文及跟随系统语言，内含关于、版本和代码仓库入口。

## 快速上手

客户端需要 Android 8.0+；声音播放需要远端 Android 11+，其他能力与远端系统、编码器及厂商权限有关。

**网络连接：** 远端开启传统 ADB TCP（例如先通过已授权的 adb 执行 `adb -s '<target-serial>' tcpip 5555`），在客户端填写可访问的 IP / 主机名和端口，点击连接，首次在远端确认调试授权。暂不支持无线调试的配对码 / TLS 模式。

**USB 连接：** 主控手机开启 USB Host / OTG，被控手机开启 USB 调试，用支持数据传输的线连接。选择「USB ADB」和设备，点击连接，分别确认本机 USB 访问和远端调试授权。USB ADB 已通过用户实机验收。

连接后可以直接触控。键盘图标展开虚拟键盘，齿轮打开远控设置；断开连接需要确认。配置页右上角的齿轮提供应用语言与关于信息。完整操作、声音和系统手势的限制见 [使用说明](docs/usage.md)，遇到问题见 [排障指南](docs/troubleshooting.md)。

## 构建

准备 JDK 11 和 Android SDK 31，设置 `JAVA_HOME`、`ANDROID_HOME`，然后运行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

首次构建会下载并校验固定版本的官方 scrcpy server。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `cn.felix021.scrcpy`。编译产物和本机配置不提交。环境设置与离线构建见 [开发指南](docs/development.md)。

## 文档与贡献

[文档索引](docs/README.md)按使用、架构、配置、开发、验证、发布和排障组织。AI 开发先读 [AGENTS.md](AGENTS.md)，按任务加载主题；历史结果保存在 [验证记录](docs/validation/README.md)。

中英文 README 同步维护；详细技术文档目前使用中文。文档改动可运行 `python3 scripts/check-docs.py` 检查本地链接和索引。

## 许可证

应用使用 [GPL-3.0](LICENSE)。ADB 库使用 BSD 许可，官方 scrcpy server 使用 Apache-2.0；来源与本地修改见 [第三方说明](third_party/README.md)。参考项目：[scrcpy-android](https://github.com/huage2580/scrcpy-android)。
