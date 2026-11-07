# 开发指南

[文档索引](README.md) · [架构](architecture.md) · [验证](testing.md)

## 环境与工作区

| 工具 | 项目使用的版本 / 用途 |
| --- | --- |
| JDK | 11；Java 源码目标兼容级别为 8 |
| Android SDK | compile / target SDK 31，min SDK 26；构建工具可使用 30.0.2 |
| Gradle / Android Gradle Plugin | wrapper 7.0.2 / AGP 7.0.0，由仓库固定 |
| Python 3.8+ | 文档检查与 AVD 验证脚本 |
| Bash、curl、sha256sum | 下载并校验官方 server |
| adb、Android Emulator | 仅设备验证时需要 |

以 [app/build.gradle](../app/build.gradle)、[根构建文件](../build.gradle) 和 [wrapper 配置](../gradle/wrapper/gradle-wrapper.properties) 为实际依据。不要为了修复本机环境问题顺带升级工具链。

先检查 `git status`、当前分支和其他任务的改动，再创建独立 worktree。以下名称替换为本轮任务名，worktree 放在项目专属目录下：

```bash
mkdir -p "$HOME/worktrees/scrcpy-apk"
git worktree add -b '<task-branch>' "$HOME/worktrees/scrcpy-apk/<task>"
```

保留主工作区和其他任务的未提交内容，不重置或清理别人的 worktree。临时日志、下载校验与截图放在 `$HOME/tmp/` 的独立任务目录。

## 配置与构建

配置已安装的工具路径：

```bash
export JAVA_HOME=/path/to/jdk-11
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

也可用未跟踪的 `local.properties` 指定 `sdk.dir`。新 worktree 不自动包含它；检查环境变量或自行创建，不能将本机 SDK 路径提交。

缺少 SDK 组件时，通过 Android SDK Manager 安装；命令行示例：

```bash
sdkmanager "platforms;android-31" "build-tools;30.0.2" "platform-tools"
```

运行：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

正式交付使用 `:app:assembleRelease`，签名与输出说明见 [发布指南](release.md)；设备脚本可用 `--apk` 选择 release 包。

需要端到端验证时再构建探针：

```bash
./gradlew :validation:probe:assembleDebug
```

输出分别为 `app/build/outputs/apk/debug/app-debug.apk` 与 `validation/probe/build/outputs/apk/debug/probe-debug.apk`。Lint 与 JVM 测试报告在 `app/build/reports/`、`app/build/test-results/`。设备运行方法见验证指南。

### server 与离线构建

[prepareServer](../app/build.gradle) 调用 [prepare-server.sh](../scripts/prepare-server.sh)，将固定版本的官方 server 下载到 `app/build/generated/server/scrcpy-server.jar` 并校验 SHA-256，构建时加入 APK assets。下载目录不进入 Git。

Gradle 依赖、wrapper 和 server 已准备好时，可加 `--offline`。该选项只控制 Gradle 的依赖解析，不能禁止 `prepare-server.sh` 在文件缺失或哈希不匹配时下载。新 worktree 需要自己的已校验 server 文件；共享 Gradle 缓存不等于已准备好 server。保持哈希校验，不通过提交二进制或跳过校验解决网络问题。

## 修改界面与实现

- 项目使用原生 Java 和 Android View。界面集中在 `MainActivity`，通过已有控件助手构造；按需求做最小改动，不同时改写整套 UI。
- 英文资源在 [values/strings.xml](../app/src/main/res/values/strings.xml)，中文在 [values-zh/strings.xml](../app/src/main/res/values-zh/strings.xml)，通过 `UiText` 读取。两套文案同步，默认跟随系统，保留手动选择。
- 与自动化交互有关的按钮在 [ids.xml](../app/src/main/res/values/ids.xml) 中使用稳定 ID；改变入口时同步维护验证脚本共用助手，避免各专项继续点击旧位置。
- 图标优先沿用 [drawable](../app/src/main/res/drawable/) 中的 vector 资源。新弹窗复用现有深色圆角、选中高亮与固定底部操作区。
- 涉及异步服务、USB 授权、Surface 或弹窗生命周期时先读架构约束，尤其检查旧回调、资源归属和关闭竞争。

## 完成前

根据 [验证矩阵](testing.md) 选择必要检查；纯文档改动执行 `python3 scripts/check-docs.py` 和 `git diff --check` 即可，不重建 APK、不改版本。

检查 Git 差异只包含预期源码和文档。[.gitignore](../.gitignore) 排除构建输出、APK、SDK 本地配置、日志和 Python 缓存。不要提交密钥、截图、真实配置、私人主机名或 IP；验证记录使用泛化环境说明，原始材料留在仓库外。

行为变化同步主题文档；必要时补充 [版本验证记录](validation/README.md)。实际交付见 [发布指南](release.md)。
