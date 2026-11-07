# 0.2.0 验证记录

验证日期：2026-11-06。APK SHA-256：`0d4fc8bf3ccc553d015fab6e950cd6f78615eadb5b1e0cb7e64718bc08280c23`。

本轮使用两台独立 Android 14 AVD，通过真实 ADB TCP 运行官方 scrcpy server，未模拟视频或控制协议。Ace 同期被另一会话占用，v0.2 的新增手势没有在 Ace 上完成复测。

## 专项验收

`scripts/verify-avd.py --phase ui` 共 11 项通过：

- 页面没有绑定特定组网软件；虚拟键选项可用。新安装及旧版预设升级时，虚拟键默认勾选。
- 编码下拉菜单实际显示 H.264 / H.265，并可切换。
- 缩短 AVD 屏幕以强制设置区滚动，连接按钮的位置保持固定。
- 关闭虚拟键后按键消失，竖屏视频占据超过 88% 的屏幕高度，底部保留安全区。
- 本机 Back 到达远端验证页面，并保持客户端连接。
- 画面顶部下滑展开远端通知栏。
- 画面底部上滑返回远端桌面。
- 画面左侧右滑、右侧左滑分别关闭远端验证页面。
- 虚拟键开关在结束客户端进程后仍保留，可重新开启。
- 横屏后重新计算安全区，保持连接。

四边滑动均要求画面收到对应方向的触控，且本机 Back 回调和触摸取消计数不增加；本机返回键专项正向验证了 Back 回调日志。这样可排除“本机拦截手势后代发 Back”的误判。`uiautomator` 必须确认当次成功生成界面树，避免读到转场前的旧 XML。

以上专项通过后，仅调整了状态条的文字顺序，让“已连接 / 仅观看”显示在设备名前，避免长设备名遮住状态。最终 APK 另行完成连接状态、本机 Back 不断开及安装包哈希一致性检查。

## 构建与 review

JDK 11 构建、9 项 JVM 测试、lint 均通过。新增测试覆盖四边识别、真实边界映射、移动距离保持、普通触控及反向/平行移动不误识别。

`gpt-6-astra`（xhigh）完成 review，无剩余 P1/P2。已处理多指映射相互独立、各方向系统安全区、挖孔避让、旋转后重新计算排除区及首次创建窗口的 Insets 初始化顺序。

## 复现

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :validation:probe:assembleDebug
python3 scripts/verify-avd.py --phase ui \
  --client <client-avd-serial> --target <target-adb-serial> \
  --host <reachable-target-ip> --port <adb-port> \
  --output <directory-outside-repository>
```

新 AVD 的首次全屏提示需要确认。可使用 `scripts/start-avd.sh <avd-name> <console-port>` 启动专用 AVD；该脚本不继承宿主机代理环境变量，避免原始 ADB TCP 被代理影响。截图和原始日志保存在本机临时目录，未提交到 Git。

物理屏幕顶部、底部的系统保留区域仍由本机处理；手势透传范围为实际显示的远控画面。
