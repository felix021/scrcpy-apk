# Google Play 上架

[文档索引](README.md) · [发布指南](release.md) · [商店资料](../play/README.md)

当前准备首个 Google Play 版本 **1.2.0 / versionCode 17**，包名 `cn.ohmyterm.scrcpy`，计划一次购买 **US$0.99**，无订阅或 App 内购买。源码继续 GPL-3.0。构建完成不代表已经上传、提审或在售，实际状态记录在私有发布配置。

## 首次创建与账号

使用所有者确认的 Google Play 开发者账号，不因参考了其他项目的构建环境就切换账号。使用服务账号前先确认其能访问同账号下的已知 App。

首次 App 创建、付费类型、Google Play App Signing 初始化和首个 AAB 绑定包名需在 Play Console 网页确认。API 的 `Package not found` 既可能表示没有创建，也可能表示网页草稿尚未上传首包，不能直接要求重复创建。

选择「应用」、默认语言 English (United States)、付费，并设置美国基础价 USD 0.99；其他地区按实际发行计划配置。不要先以免费正式发布占位，再尝试改成付费。所有者自行核对开发者验证、收款和适用协议。

## 构建与签名

构建工具为 JDK 17+、SDK 36、AGP 8.10.1、Gradle 8.14.3、NDK 28。最低客户端 Android 8.0 保持不变。JDK 21 已用于本轮构建。

私有配置提供：

```text
SCRCPY_PLAY_KEYSTORE
SCRCPY_PLAY_STORE_PASSWORD
SCRCPY_PLAY_KEY_ALIAS
SCRCPY_PLAY_KEY_PASSWORD
```

```sh
./gradlew :app:bundlePlay :app:assemblePlay :app:testPlayUnitTest :app:lintPlay
java -jar /path/to/bundletool.jar validate --bundle app/build/outputs/bundle/play/app-play.aab
java -jar /path/to/bundletool.jar dump config --bundle app/build/outputs/bundle/play/app-play.aab
```

交付文件为 `app/build/outputs/bundle/play/app-play.aab`。`app-play.apk` 只用于本地验证，`app-play-androidTest.apk` 是测试代码，不能上传商店。

Play 上传密钥独立于历史 APK；没有配置时拒绝签名，不回退到 debug key。Google Play App Signing 的最终分发证书与上传证书也需要分别记录。证书、私钥、口令和服务账号 JSON 只放私有配置，不能进入公开仓库。

GitHub Release APK 保持原签名。不同签名的同包名安装不能直接覆盖；不要默认卸载已有安装或声称配置会自动迁移。

## 兼容性与材料

- target SDK 36：远控使用系统新的 Back 回调；配置页主动处理系统栏 Insets，远控保留原安全区。
- 64 位原生库需要核对 ELF LOAD 对齐，AAB 的原生库 ZIP 对齐应为 `PAGE_ALIGNMENT_16K`；不能仅以 APK 能安装作为证明。
- 按 [验证指南](testing.md) 测试最终签名包的真实二维码 / 配对码、TLS 视频、系统 Back、横竖屏及音频行为。16 KB 的静态对齐检查不能替代 16 KB 真机运行；模拟器结果不等于 USB 实机链路验证。
- 中英文 title / shortDescription / fullDescription 和发布说明位于 `play/`。Google Play 的中文语言代码使用 `zh-CN`，不要混用 iOS 的 `zh-Hans`。
- 准备真实手机与平板截图、512×512 图标、1024×500 feature graphic；原始图片放私有构建目录，矢量源可维护在 `play/assets/`。

手机截图使用实际 1080×1920 界面。上传前检查 PNG / JPEG、每边 320–3840 px、长边不超过短边两倍，PNG 截图和 feature graphic 不带 alpha；图标为 512×512 的 32 位 PNG。1080×2340 的原始 AVD 截图不符合长宽比要求，需在专用模拟器设置合规分辨率后重新截图，避免拉伸界面。详见 [预览素材规格](https://support.google.com/googleplay/android-developer/answer/9866151)。

## 隐私和内容声明

隐私 URL 为 <https://scrcpy.oh-my-term.cn/privacy.html>。麦克风默认关闭，主动开启时才请求权限；不加入广告、分析或订阅 SDK。

Google Play Data safety 的「收集」定义和 Apple 不完全相同，不能直接沿用 iOS 答案。本 App 有设备直连、麦克风与输入传输；需分别核对用户授权的设备间传输、临时内存处理、TLS / 传统 TCP 等路径，不能把所有连接都宣称为加密。按当前模板完成真实答案，服务账号也可通过 `applications.dataSafety` 提交 CSV；首次申报和模板不明确时先由所有者核对网页。

网页还需检查广告、目标受众、内容分级、App 访问方式及控制台提出的其他项目。审核步骤见 `play/review-instructions.en.md`：无 App 账号，但需要用户授权的 Android 和 ADB；不能提供虚构的演示账号或主机。若控制台要求封闭测试，按该账号和 App 的实际门槛执行，不能用内部测试冒充。

## 上传和发布

首包绑定后，使用同账号的 Developer API 完成 edit、上传 AAB、更新多语言文案 / 图像、轨道与 validate，再按当前授权 commit。每次保存 edit ID、versionCode、包哈希和轨道状态；写入超时先查询结果，避免重复上传。

先验证内部测试或控制台要求的测试轨道，再推进正式生产审核。创建 edit 或把 release 标为 draft 不代表已提审；以 Play Console 的审核状态和实际商店页面为准。正式在售后再添加 Google Play 下载链接，不能提前放虚假的链接。

参考：[创建应用](https://support.google.com/googleplay/android-developer/answer/9859152)、[目标 API 要求](https://support.google.com/googleplay/android-developer/answer/11926878)、[16 KB 页面](https://developer.android.com/guide/practices/page-sizes)、[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)、[Developer API](https://developers.google.com/android-publisher/api-ref/rest)。
