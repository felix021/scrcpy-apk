# 0.3.0 验证记录

验证日期：2026-11-06。APK SHA-256：`5ff7b280f2304d2b9de7e4e789bd07e48c953ac2bb7bdcd476baccaba866ddd0`。

使用两台独立 Android 14 AVD，一台运行客户端，一台通过真实 ADB TCP 运行官方 scrcpy 5.0.1 server。未模拟视频或控制协议。

## 本轮改动

- 连接状态、居中的返回 / Home / 最近任务三键、键盘开关和断开放在底部同一行。Home 使用房子轮廓图标。
- 透明触控层覆盖视频及周围黑边。从左右滑入发送 Back，从底部滑入发送 Android Home，从顶部滑入调用 scrcpy 的展开快捷设置操作。
- 边缘动作识别成功时不会同时发送原始触控，避免远端先点击或拖动，再执行导航。普通拖动、多指和取消事件继续按真实坐标发送；黑边静止点击忽略。静止边缘长按转为普通触控，已移动的慢速内滑保留识别资格。
- 完整键盘提供字母、符号、Ctrl / Alt / Shift、Caps、方向键和 Fn 层。修饰键支持一次生效和长按锁定。收起、旋转和断开均重置状态。
- 文字弹窗和主动粘贴使用远端剪贴板；较长文本拆成多个 ADB 包，保持控制消息连续。横屏展开键盘时，画面和键盘左右排列。
- 主动断开先排空触点释放消息，并等待最终 ADB 确认；总期限避免设备不可达时卡住关闭。

## 验证方法

`scripts/verify-avd.py --phase controls` 检查底部布局、四边导航、普通触控、黑边点击、键盘、中文长文本和横屏。可用 `--phase keyboard` 单独重跑键盘专项。测试先把远端切换为三键导航，结束后恢复原导航模式，以确认边缘动作不依赖远端启用手势导航。

手势必须同时满足：客户端记录正确的 `EDGE_ACTION`；本机 Back 回调与触摸取消计数不增加；远端实际切换到预期页面。顶部操作另外检查展开后的快捷设置控件，避免把通知栏误当成控制中心。旋转测试等待实际视频宽高比变化，不只等待固定时间。

文字验收直接读取远端输入框结果，并检查目标应用收到的真实按键和 meta 状态。中文和超过单个 ADB 包的文本通过客户端文字弹窗发送。所有截图、XML、原始日志和测试 APK 均保存在仓库之外。

已通过的双 AVD 验收包括：

- 远端三键模式下，左右黑边滑入返回，上下黑边滑入分别打开快捷设置和回桌面；慢速侧滑保持有效。普通拖动正常，静止黑边点击不发送触控。
- 字母、Shift、Caps + Shift、Ctrl + A、Alt 和 Fn 的真实按键及输入结果正确。Ctrl 长按锁定后可连续组合，再点按解锁。
- Shift 标点与标签一致；中文、emoji 和超过 6 KB 的文本经文字弹窗发送，远端内容逐字相同。
- 退格长按连续删除，松手后内容稳定。
- 客户端横屏时键盘在画面右侧，两者不重叠，旋转后仍能输入；两端同时横屏保持画面比例和底部三键布局。
- 隐藏三键后仍可展开完整键盘和使用侧边返回。
- `--phase lifecycle` 确认离开客户端前发送活动触点的 UP，后台断开后能重新打开，取消待连接后能重连，断开后远端 server 进程退出。

## 构建与复核

JDK 11 构建、17 项 JVM 测试、Android lint 通过。JVM 测试覆盖四边方向和反向取消、普通坐标映射、修饰键与大小写、标点、组合键次序和释放、UTF-8 长度、粘贴上限、ADB 数据收尾和最终 ACK 等待。

`gpt-6-astra`（xhigh）完成静态复核，未发现剩余 P1 / P2。

## 复现

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :validation:probe:assembleDebug
python3 scripts/verify-avd.py --phase controls \
  --client <client-avd-serial> --target <target-adb-serial> \
  --host <reachable-target-ip> --port <adb-port> \
  --output <directory-outside-repository>
```

验收脚本适用于专用 1080×2340 AVD。首次全屏提示需确认，客户端首次 ADB 授权需在远端同意。可设置 `ADB_SERVER_SOCKET` 使用独立 ADB 服务，避免其他会话重启共享服务时干扰验证。
