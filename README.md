# scrcpy APK

在 Android 上查看并控制另一台 Android 设备。通过设备 IP 或主机名连接 ADB TCP，无需电脑中转视频，也不需要额外开放投屏端口。

- 紧凑配置界面、底部固定连接按钮、实时视频、单指和多指触控。
- 远控页隐藏本机系统栏，顶部留出完整状态栏高度的空白，底部同一行放置连接状态、居中的返回 / Home / 最近任务三键，以及键盘和断开按钮，并避开系统安全区。
- 从远控画面四边向内滑动：左右边返回、底边回桌面、顶边打开控制中心。也可从画面旁的黑边滑入；不依赖远端使用手势导航还是三键导航。物理屏幕的系统保留区域仍由本机处理。
- 「展示虚拟按键」默认勾选，取消后可通过边缘手势操作；完整键盘仍可单独展开。
- 完整虚拟键盘提供字母、数字、符号、Ctrl / Alt / Shift、方向键、Caps Lock 和 Fn 层。支持中文文字发送与主动粘贴；横屏时键盘放在画面右侧。
- 省流、均衡、高清三档快捷参数；最长边、码率、帧率均可修改；视频编码通过下拉菜单选择 H.264 / H.265。
- 保存多台主机的完整连接配置，包含名称、地址、端口、画面参数和全部控制开关；列表显示名称和地址，点选即可切换。支持同名更新、删除和记住上次使用的配置，兼容旧版已保存数据。
- 仅观看模式、充电时保持远端唤醒。切到后台或点击断开后结束连接。
- 使用固定版本的官方 scrcpy 5.0.1 server。当前不转发声音。

## 使用

1. 客户端需要 Android 8.0+，远端需要支持所选编码的 Android 设备。
2. 保证客户端能够访问远端 IP 和 ADB 端口，例如两端在同一局域网中。
3. 远端开启 ADB TCP，例如 `adb tcpip 5555`。支持传统 ADB TCP 授权，暂不支持 Android 无线调试的配对码/TLS 模式。
4. 输入远端 IP 与端口，选择或修改参数，点击连接。可用地址上方的「保存配置」为每台主机命名，以后通过「切换配置」点选主机，再点底部连接按钮。修改后再次保存，同名配置会更新；画质快捷档不会改动配置名称。首次连接需要在远端确认调试授权，可以勾选始终允许。
5. 直接触控远控画面；本机返回键和返回手势发送到远端。使用底部「断开」结束会话。底部键盘图标可展开完整虚拟键盘。

键盘的 Ctrl / Alt / Shift / Fn 点按后对下一个键生效，长按可锁定，再次点按取消；收起键盘、旋转屏幕或断开时会清除修饰状态。Fn + 数字行提供 F1–F10，右上方提供 F11 / F12；Fn + 方向键提供 Home / PgDn / PgUp / End，Fn + Esc 是 Menu。键盘中的 Home 是编辑导航键，底部房子图标用于返回 Android 桌面。长按退格或方向键可连续操作。

「文字」弹窗使用本机输入法输入任意文字，再发送到远端；「粘贴」只在主动点击时读取本机剪贴板。这两项通过远端剪贴板粘贴，单次最多 64 KiB UTF-8，会覆盖远端剪贴板内容。

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

验收脚本会在指定 AVD 安装客户端，在指定远端安装一次性验证应用，以确认触控、按键和文字的实际接收结果，结束后卸载验证应用。远端需已授权本机 adb，首次客户端授权仍需要在远端确认。

```bash
./gradlew :app:assembleDebug :validation:probe:assembleDebug
python3 scripts/verify-avd.py \
  --client <avd-serial> --target <target-adb-serial> \
  --host <target-ip> --port 5555 \
  --output /path/outside/repository
```

脚本适配专用 1080×2340 AVD，覆盖预设保存与进程重启恢复、真实视频、触控、虚拟键、客户端旋转、H.265、仅观看、参数校验和后台断开。结果和截图写入 `--output`，不会进入 Git。设备中断后，可用 `--phase lifecycle` 单独补跑后台断开与触点释放、取消后重连、远端进程退出；请为每次运行使用独立输出目录。多主机保存、切换和顶部留白专项用 `--phase configs`，见 [0.4 验证记录](docs/VALIDATION-0.4.md)。配置页和手势专项可用 `--phase ui` 运行；三键布局、四边动作、完整键盘和横屏专项用 `--phase controls`，结果见 [0.3 验证记录](docs/VALIDATION-0.3.md)。首次全屏提示需要确认。使用 `scripts/start-avd.sh <avd-name> <console-port>` 可避免 AVD 继承宿主机代理环境变量。ADB 收包、控制消息和关闭竞争另有 JVM 回归测试。

## 许可证

应用为 GPL-3.0。ADB 传输来自 BSD 许可的 adblib，官方 scrcpy server 为 Apache-2.0。详见 [third_party](third_party/README.md)。参考项目：[scrcpy-android](https://github.com/huage2580/scrcpy-android)。
