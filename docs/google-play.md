# Google Play 上架

[文档索引](README.md) · [发布指南](release.md) · [商店资料](../play/README.md)

当前准备首个 Google Play 版本 **1.2.0 / versionCode 18**，包名 `cn.ohmyterm.scrcpy`，计划一次购买 **US$0.99**，无订阅或 App 内购买。源码继续 GPL-3.0。构建完成不代表已经上传、提审或在售，实际状态记录在私有发布配置。

## 首次创建与账号

使用所有者确认的 Google Play 开发者账号，不因参考了其他项目的构建环境就切换账号。使用服务账号前先确认其能访问同账号下的已知 App。

首次 App 创建和付费类型在 Play Console 网页确认；创建页要求包名时填写 `cn.ohmyterm.scrcpy`。不要仅凭 API 的 `Package not found` 就重复创建 App，先核对网页记录、包名和服务账号的应用权限。

新 App 不会自动继承同账号下其他 App 的服务账号权限。上传前先核对凭据和本 App 的查看、草稿、商店资料、政策声明及测试 / 生产发布权限；能够访问参考 App 不代表能访问本 App。`users.list` 需传 `pageSize=-1`。App 管理员仅获某个旧 App 的权限时，不能替自己授予新 App 权限。

App 建立且权限到位后，可以通过 API 上传首个 AAB，本项目 1.2.0 已验证该路径。Google Play App Signing 按控制台与 API 的实际要求配置，不预先假定首包必须手工上传。

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

隐私 URL 为 <https://scrcpy.oh-my-term.cn/privacy.html>，应用「设置 → 关于 → 隐私政策」可直接打开同一地址。麦克风默认关闭，主动开启时才请求权限；不加入广告、分析或订阅 SDK。

Google Play Data safety 的「收集」定义和 Apple 不完全相同，不能直接沿用 iOS 答案。本 App 有设备直连、麦克风与输入传输；需分别核对用户授权的设备间传输、临时内存处理、TLS / 传统 TCP 等路径，不能把所有连接都宣称为加密。按当前模板完成真实答案，服务账号也可通过 `applications.dataSafety` 提交 CSV；首次申报和模板不明确时先由所有者核对网页。

网页还需检查广告、目标受众、内容分级、App 访问方式及控制台提出的其他项目。审核步骤见 `play/review-instructions.en.md`：无 App 账号，但需要用户授权的 Android 和 ADB；不能提供虚构的演示账号或主机。若控制台要求封闭测试，按该账号和 App 的实际门槛执行，不能用内部测试冒充。

## 上传和发布

首包绑定后，使用同账号的 Developer API 完成 edit、上传 AAB、更新多语言文案 / 图像、轨道与 validate，再按当前授权 commit。每次保存 edit ID、versionCode、包哈希和轨道状态；写入超时先查询结果，避免重复上传。

先验证内部测试或控制台要求的测试轨道，再推进正式生产审核。创建 edit 或把 release 标为 draft 不代表已提审；以 Play Console 的审核状态和实际商店页面为准。正式在售后再添加 Google Play 下载链接，不能提前放虚假的链接。

提交 edit 前先 validate，提交后创建新 edit 回读构建哈希、轨道状态、文案与图片数量，再删除这个只读检查的 edit。首次准备保存 `internal` 的 `draft`，不自动邀请测试人员。使用 `changesInReviewBehavior=ERROR_IF_IN_REVIEW` 避免取消已有审核。`changesNotSentForReview` 不能一律设为 true：本项目首次草稿提交时 Google 明确要求不传该参数；按实际错误修正，不能把参数名当作未提审的证据。

参考：[创建应用](https://support.google.com/googleplay/android-developer/answer/9859152)、[目标 API 要求](https://support.google.com/googleplay/android-developer/answer/11926878)、[16 KB 页面](https://developer.android.com/guide/practices/page-sizes)、[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)、[Developer API](https://developers.google.com/android-publisher/api-ref/rest)。

## API 与网页的边界

当前公开 Developer API 可写 AAB、轨道、商店文案 / 图片、联系信息和 `applications.dataSafety` 的 CSV 声明。隐私政策 URL、登录说明、广告、分级、目标受众、政府 / 金融 / 健康声明、Trader status、应用分类、商家账号和付费应用售价没有对应的公开写入接口；不要把应用内商品定价接口用于应用下载价格，也不要把面向第三方应用商店的 `appstoreappsreview` 当作 Play Console 问卷接口。

Data safety CSV 必须清空官方样例自带的示例答案，再按真实代码填写。官方旧样例缺少当前必填的账号创建问题；本 App 的回答为 `PSL_SUPPORTED_ACCOUNT_CREATION_METHODS / PSL_ACM_NONE`，没有外部账号登录。旧的 `PSL_DATA_COLLECTION_USER_REQUEST_DELETE` 已从新表单移除，不能照抄旧模板。只用当前表单支持的问答组合，API 400 时先查格式与遗漏，不能改成不真实的业务答案以求通过。

本轮声明包含设备间的画面、录音、控制交互、发送文本与 ADB 身份标识，目的仅为应用功能；音频 / 交互可关闭，输入文字及远端授权标识不能一律视为即时删除。声明按用户主动的设备传输处理第三方共享豁免，不申报广告或统计分析用途。传统 TCP 路径不加密，因此没有声明全部传输均加密。详细 CSV 和 API 回执保存在私有配置，HTTP 204 仅证明已接受声明，不代表生产审核通过。
