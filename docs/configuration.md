# 配置与数据

[文档索引](README.md) · [使用说明](usage.md)

## 标识与存储

| 内容 | 位置 / 约定 |
| --- | --- |
| 主应用 ID | `cn.felix021.scrcpy`，来源：[app/build.gradle](../app/build.gradle) |
| 一次性验证应用 ID | `cn.felix021.scrcpy.probe`，来源：[probe/build.gradle](../validation/probe/build.gradle) |
| 连接数据 | 应用私有 SharedPreferences `connections` |
| `last` | 最近使用配置的 JSON 对象字符串 |
| `profiles` | 已保存完整配置的 JSON 数组字符串 |
| `language` | 全局语言：`system` / `zh` / `en`，默认 `system`，不属于某台主机 |
| `localName` | 全局本机名称，默认系统设备名 / 机型；备注使用 1–120 字节 UTF-8，空白转下划线，不属于连接配置 |
| ADB 身份 | 应用私有文件 `adb-private` / `adb-public`，TCP、USB 和无线 TLS 配对共用 |

应用关闭系统数据备份。覆盖升级保留数据和 ADB 身份；卸载 / 清除数据会丢失配置和密钥。不要把应用私有数据、真实地址、USB 序列号或密钥写入仓库与公开证据。

实现依据：[Profile](../app/src/main/java/cn/felix021/scrcpy/Profile.java)、[MainActivity](../app/src/main/java/cn/felix021/scrcpy/MainActivity.java)、[UiText](../app/src/main/java/cn/felix021/scrcpy/UiText.java)、[Manifest](../app/src/main/AndroidManifest.xml)。

## Profile 字段

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `name` | 空 | 配置名称；同名保存更新原条目 |
| `transport` | `tcp` | `tcp` 或 `usb`；缺少字段的旧配置按网络连接读取 |
| `host` / `port` | 空 / `5555` | 网络主机与端口；USB 不校验这两项 |
| `usbLabel` / `usbSerial` | 空 / 空 | 获得的设备描述与序列号；序列号可能受 USB 权限限制 |
| `usbVendor` / `usbProduct` | `-1` / `-1` | USB VID / PID，未指定时为 `-1` |
| `maxSize` | `1600` | 最长边 px；`0` 为原尺寸，否则 `256–8192` |
| `bitrate` | `6` | Mbps，`1–100` |
| `fps` | `60` | 帧率上限，`1–120` |
| `codec` | `h264` | `h264` / `h265` |
| `showNavigation` | `true` | 展示远控虚拟导航键 |
| `viewOnly` | `false` | 仅观看，不发送控制、麦克风或方向变更 |
| `syncRotation` | `true` | 同步本机横竖屏到远端 |
| `forwardAudio` | `true` | 播放远端声音 |
| `forwardMic` | `false` | 转发本机麦克风 |
| `stayAwake` | `true` | 充电时保持远端唤醒 |

`usbDeviceName` 只在进程内指向当前 USB 接口，不写入 JSON；连接前必须重新匹配当前接入设备。未插线时可以保存 USB 画质与控制配置。

配置身份同时包含连接方式：TCP 比较 host / port；USB 比较 VID / PID，双方都有序列号时再比较序列号。编辑地址或端口不能把旧主机名称带到不同端点。不要把显示名当作唯一设备身份。

## 画质快捷档

| 档位 | 最长边 | 码率 | 帧率上限 | 编码 |
| --- | --- | --- | --- | --- |
| 省流 | 1024 px | 2 Mbps | 30 | H.264 |
| 均衡 | 1600 px | 6 Mbps | 60 | H.264 |
| 高清 | 2400 px | 12 Mbps | 60 | H.264 |

快捷档一次设置四个画面参数，不改配置名称；手动调整后可显示自定义。参数以 `MainActivity.applyPreset()` 为准，默认值与校验以 `Profile` 为准。

## 运行时与全局设置

远控设置能改变导航键显示、键盘显示、播放、麦克风、保持唤醒和方向同步，更新当前配置及匹配的已保存配置；键盘展开状态属于会话 UI，不是 Profile 的持久化字段。远端地址、连接方式、画面编码参数和仅观看需断开后修改。

本机名称也是全局设置，作用于新配对和经典 ADB 公钥授权；不更换 RSA 密钥，也不修改远端已经保存的备注。配对码、二维码挑战和远端配对 GUID 只用于当前流程，不写入 SharedPreferences。

应用语言是全局设置，在配置页应用设置和远控设置中均可调整。默认资源为英文，中文位于 `values-zh`，其他系统语言回退英文。选择语言时保留原始表单内容，包括尚未通过校验的数字输入。

权限分开处理：网络无运行时授权；USB 使用系统 USB 设备访问授权；本机麦克风启用时才申请 `RECORD_AUDIO`。USB 权限不等于远端 ADB 调试授权。语言设置与保存配置不应触发录音授权。

## 修改数据格式

新增字段同时维护默认值、`json()`、`from()`、`copy()`、UI 读写和验证。旧 JSON 必须能读取；临时字段应明确不持久化。默认值变化需同步本文、用户说明和适用测试，禁止通过清空用户配置处理兼容问题。
