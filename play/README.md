# Google Play 商店资料

这是首发准备资料，不能据此认定已经在 Google Play 上线。构建、签名与首次创建步骤见 [Google Play 指南](../docs/google-play.md)。

- `metadata/en-US.json`、`metadata/zh-CN.json`：标题、简短与完整说明。长度分别不超过 30、80、4000 字符。
- `release-notes.json`：每种语言不超过 500 字符。
- `review-instructions.en.md`：授权 Android 硬件、ADB 配对 / USB、音频权限与清理的审核步骤。
- `assets/`：复用现有手机控制图标的商店图标与 feature graphic 矢量源。PNG、真实截图和上传证据放构建临时目录，不提交生成产物。

定价为一次购买 US$0.99，由商店收款；不加入应用内付费墙或订阅。个人联系资料、服务账号、签名凭据和实际发布状态留在私有配置。
