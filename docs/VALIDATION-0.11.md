# 0.11 验证记录

新增 Android USB Host 的 ADB 传输路径。USB 与 TCP 共用 RSA 身份、ADB 逻辑通道、视频、控制、声音和麦克风逻辑。配置页可选择连接方式，USB 模式隐藏网络地址和端口，列出带双向 Bulk 端点的 ADB 接口；先获得本机 USB 权限，再建立远端 ADB 授权连接。USB 权限结果带独立请求标识，过期结果不启动连接；应用回到前台后才处理待完成的 USB 授权。

## 已验证的软件行为

JDK 11、Android SDK 31，离线构建应用及验证 APK。30 项 JVM 测试通过，app lint 无错误。USB 测试使用模拟 Bulk 接口，覆盖：

- 包头与数据分别传输，每次 Bulk 最多 16 KiB；输出数据长度恰为端点包长倍数时发送零长度结束包。
- 输入按 24 字节包头及剩余数据的准确长度读取，不依赖远端额外发送零长度包。收到零长度包不会当作断线。
- RSA 签名、公钥授权、ADB 连接握手、逻辑通道打开、70 KB 接收、50 KB 发送、确认及远端关闭。
- 分段写入的完整消息重组、损坏包拒绝、写失败取消两端、阻塞读取和队列背压期间取消及资源释放。
- 网络仍为默认连接方式；USB 不要求 IP 或端口，不同设备及不同连接方式的配置标识相互区分。

实现依据：[Android USB Host](https://developer.android.com/develop/connectivity/usb/host)、[UsbRequest](https://developer.android.com/reference/android/hardware/usb/UsbRequest) 和 [ADB 官方 USB 传输](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/usb_libusb_device.cpp)。

两个专用 Android 14 AVD 上的 `--phase usb-ui` 通过：USB 模式隐藏 IP / 端口，无设备时不启动 TCP 连接；USB 与网络配置可以分别保存、切换，中英文切换及进程重启后仍保留连接方式；切回网络后，实际视频和远端触控均正常。旧数据缺少连接方式字段时按原网络配置读取。USB 配置可以在插线前保存，连接时仍必须选择已接入设备。

同一对 AVD 经 TCP 的 `--phase audio-runtime` 回归通过四种播放 / 麦克风组合，视频 PID 不变。播放开启时本机 1499 Hz 测试音幅度为 288，关闭时为 0；麦克风开启时远端普通录音应用收到的 997 Hz 幅度为 12016，关闭时为 0。拒绝录音权限后视频保持连接，结束后服务退出。测试在音频路由建立后重新固定媒体音量，并在结束时恢复原值，避免异步切换输出设备时旧音量影响测量；没有降低验收阈值。

## 验证边界

初次开发验证时，开发机器未接入 USB 手机，两台 AVD 不能替代真实的 USB Host / OTG 链路。

后续用户反馈：USB ADB 功能已通过真实手机验收。此项为用户实测结论；未另行记录手机型号、线材及各场景的明细，开发侧的自动化证据仍以上述协议模拟和 AVD 检查为准。

USB 接口通过异步请求读取；空闲等待保留原请求，避免超时后重新排队丢失部分数据。关闭时先取消请求，读线程退出后释放资源；同步写有界等待，USB 接口由连接关闭释放。设备移除事件会结束对应会话，没有 USB 设备时不会回退到网络连接。
