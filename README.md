# scrcpy APK

在 Android 上查看并控制另一台 Android 设备。通过设备 IP 或主机名连接 ADB TCP，无需电脑中转视频，也不需要额外开放投屏端口。

- 紧凑配置界面、底部固定连接按钮、实时视频、单指和多指触控。
- 远控页隐藏本机系统栏，顶部紧凑状态栏提供连接状态和断开操作，底部保留安全区。
- 远控画面支持顶部下拉、底部上滑、左边右滑、右边左滑；物理屏幕顶部和底部的系统保留区域仍由本机处理。
- 可选的 Back / Home / Menu 虚拟按键，另提供最近任务键。Menu 对应 `KEYCODE_MENU`，应用未处理该键时可能没有可见变化。
- 省流、均衡、高清三档快捷参数；最长边、码率、帧率均可修改；视频编码通过下拉菜单选择 H.264 / H.265。
- 保存命名预设、同名覆盖、加载、删除；记住上次保存或连接的设置。
- 仅观看模式、充电时保持远端唤醒。切到后台或点击断开后结束连接。
- 使用固定版本的官方 scrcpy 5.0.1 server。当前不转发声音。

## 使用

1. 客户端需要 Android 8.0+，远端需要支持所选编码的 Android 设备。
2. 保证客户端能够访问远端 IP 和 ADB 端口，例如两端在同一局域网中。
3. 远端开启 ADB TCP，例如 `adb tcpip 5555`。支持传统 ADB TCP 授权，暂不支持 Android 无线调试的配对码/TLS 模式。
4. 输入远端 IP 与端口，选择或修改参数，点击连接。首次连接需要在远端确认调试授权，可以勾选始终允许。
5. 直接触控远控画面；本机返回键和返回手势发送到远端。使用顶部「断开」结束会话。配置页的「展示虚拟按键」默认勾选，可在顶部显示 Back / Home / Menu / 最近任务键。取消勾选后，可通过远控画面的边缘手势操作。

ADB 身份密钥保存在应用私有目录，正常重启和升级保留。应用不备份密钥。卸载或清除数据后，需要重新授权。

## 可选：跨网络连接

如果两台设备不在同一局域网，可以先使用 EasyTier、Tailscale 等 SDN（软件定义网络）工具建立可互访的网络，再填写远端的组网 IP。客户端只需要能访问远端的 ADB TCP 端口。

## 构建

需要 JDK 11、Android SDK 31、网络可访问 Google Maven / Maven Central / GitHub。

```bash
printf 'sdk.dir=/path/to/android-sdk\n' > local.properties
JAVA_HOME=/path/to/jdk-11 ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

`prepareServer` 首次构建下载官方 server 到 `app/build/generated/server/` 并校验 SHA-256。APK 在 `app/build/outputs/apk/debug/app-debug.apk`，使用本机标准 debug 签名。编译产物、server 二进制、SDK 本地配置、密钥和测试截图均不提交。

## 端到端验收

验收脚本会在指定 AVD 安装客户端，在指定远端安装一次性验证应用，以确认触控和 Menu/Back 的实际接收结果，结束后卸载验证应用。远端需已授权本机 adb，首次客户端授权仍需要在远端确认。

```bash
./gradlew :app:assembleDebug :validation:probe:assembleDebug
python3 scripts/verify-avd.py \
  --client <avd-serial> --target <target-adb-serial> \
  --host <target-ip> --port 5555 \
  --output /path/outside/repository
```

脚本适配专用 1080×2340 AVD，覆盖预设保存与进程重启恢复、真实视频、触控、虚拟键、客户端旋转、H.265、仅观看、参数校验和后台断开。结果和截图写入 `--output`，不会进入 Git。设备中断后，可用 `--phase lifecycle` 单独补跑后台断开、取消后重连、远端进程退出三项；请为每次运行使用独立输出目录。0.2.0 的布局、返回键和手势专项可用 `--phase ui` 运行，结果见 [验证记录](docs/VALIDATION-0.2.md)。首次全屏提示需要确认。使用 `scripts/start-avd.sh <avd-name> <console-port>` 可避免 AVD 继承宿主机代理环境变量。ADB 收包、控制消息和关闭竞争另有 JVM 回归测试。

## 许可证

应用为 GPL-3.0。ADB 传输来自 BSD 许可的 adblib，官方 scrcpy server 为 Apache-2.0。详见 [third_party](third_party/README.md)。参考项目：[scrcpy-android](https://github.com/huage2580/scrcpy-android)。
