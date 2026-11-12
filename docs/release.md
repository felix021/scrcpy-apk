# 发布指南

[文档索引](README.md) · [开发](development.md) · [验证](testing.md)

## 交付范围

按当前任务已有授权执行提交、推送和 APK 发布，保持仓库原有可见性，不自行更改访问范围。公开内容不携带私人网络地址、凭据、USB 序列号或日志中的隐私信息。文档只说明通用交付流程，具体下载主机和目录由部署环境提供。

纯文档修改不升级应用版本、不重建或重新发布 APK。应用功能交付再执行下列构建与签名步骤。

## 应用版本与构建

1. 在 [app/build.gradle](../app/build.gradle) 中更新 `versionName`，递增 `versionCode`；通常不改应用 ID；用户明确要求品牌迁移时，按下文的包名变更规则执行。About 从 `BuildConfig` 读取版本，无需另改文案。
2. 检查配置兼容、中英文文案和相关主题文档，运行需要的 [验证阶段](testing.md)，按实际结果补充 [版本记录](validation/README.md)。
3. 构建最终交付 APK，记录对应源码提交、版本与 SHA-256。若验证后还改动应用源码，应重新构建并补做受影响检查。

```bash
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
python3 scripts/check-docs.py
sha256sum app/build/outputs/apk/release/app-release.apk
```

自 1.0.0 起，GitHub Release 交付非调试的 release 构建，输出为 `app/build/outputs/apk/release/app-release.apk`。为保持覆盖升级，release 构建显式使用与早期 APK 相同的本机 debug keystore；这是签名身份的延续，应用本身不启用调试。这份历史证书不用于 Google Play 上传；商店包通过独立的 `play` 构建类型和上传密钥生成。当前包名为 `cn.ohmyterm.scrcpy`。

2026-11 的组织迁移按用户要求将包名从 `cn.felix021.scrcpy` 改为 `cn.ohmyterm.scrcpy`（Android 包名不能包含连字符）。新旧包是独立应用，可并存；即使证书相同，也不能覆盖升级，连接配置和 ADB 配对身份不会自动迁移。不要卸载用户旧包来掩盖这一差异。源码包、Manifest、JNI 符号、测试与远端麦克风入口必须一起更新；历史 Release 和验证记录保留当时的包名。iOS 仍固定使用旧版麦克风 DEX，其类名随所选资源版本变化，不能只改调用端。

## 签名与覆盖安装

检查 APK 的 `debuggable` 标志未开启，再核对版本与签名。APK 版本正确不代表能够覆盖旧包，还需要签名证书一致。新构建机器的默认 debug keystore 可能不同；交付前用已安装版本 / 上次交付的 APK 核对证书，不把私钥和 keystore 提交到 Git。

```bash
"$ANDROID_HOME/build-tools/<installed-version>/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/<installed-version>/aapt" dump badging \
  app/build/outputs/apk/release/app-release.apk
```

替换为已安装的 build-tools 版本，检查包名、versionCode、versionName、min SDK 和签名证书 SHA-256。签名冲突时先解决构建环境或密钥来源，不把卸载旧包当作默认步骤，因为卸载会丢失配置和 ADB 身份。

## 依赖与 server 升级

当前 server 下载版本、URL 和哈希在 [prepare-server.sh](../scripts/prepare-server.sh) 固定；会话启动参数在 `ScrcpySession`、`AudioForwarder` 中也含版本。升级时一起核对：

- 下载来源、SHA-256、视频 / 音频头格式、控制消息和启动参数。
- RSA 授权、TCP / USB、编码切换、声音四种组合、仅观看、旋转恢复和关闭清理。
- [第三方说明](../third_party/README.md)、上游许可、[APK 内许可](../app/src/main/assets/third-party-licenses.txt) 是否同步。

Gradle wrapper JAR 是构建工具，可保留在 Git；下载的 server、编译出的 APK 和探针是生成产物，不提交。不要仅修改下载链接就视为完成协议升级。

## 提交与下载校验

- `git diff --check`，确认差异只含本任务内容；检查没有被跟踪的 APK、构建目录、临时文件、密钥和本机配置。提交不添加 AI coauthor。
- 按项目已有分支工作流集成，检查主工作区未提交变更，不覆盖其他任务修改；按已有授权推送。
- 在部署环境指定的静态目录发布带版本文件名的 APK，避免覆盖已缓存的同名版本。原始构建包和发布包应具有相同 SHA-256。
- GitHub Release 使用与应用版本对应的 tag，上传可安装的 `scrcpy-apk-<version>.apk` 及 `SHA256SUMS`，发布为正式版并设置 latest；APK 只作为 Release asset，不进入 Git。
- 从实际下载 URL 重新取回文件，比较哈希；验证链接可访问及文件完整，不能只确认本地拷贝成功。截图另检查隐私后发布，不放入源码仓库。
- 交付说明包含版本、下载入口、行为变化、验证结果及实际限制；保留原始证据在仓库外，报告使用占位环境信息。

## Google Play

商店渠道使用 `:app:bundlePlay`，私有上传身份通过环境变量提供；没有凭据时拒绝签名，不回退到 debug key。GitHub 的 `release` 构建保留原有签名。两个渠道同包名但签名不同，不能直接覆盖安装；不要自动卸载用户旧包。完整流程见 [Google Play 上架](google-play.md)。
