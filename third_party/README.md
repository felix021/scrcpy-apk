# Third-party components

- `app/src/main/java/com/tananaev/adblib`: adblib by Cameron Gutman and contributors, vendored from tananaev/adblib commit `4b3d25641933b92b729ee67d17791992158d20be`. BSD license: [adblib-LICENSE](adblib-LICENSE). Local changes: preserve unread data when peer closes, synchronize stream-open handshake, saturate infinite connect timeout, use 256 KiB receive payloads, acknowledge received packets when consumed for bounded memory, reject oversized peer payloads, accept owned byte streams for USB Host as well as TCP sockets. These sources keep the standalone Android ADB transport independent of a host adb executable.
- Official [scrcpy v5.0.1 server](https://github.com/Genymobile/scrcpy/tree/v5.0.1/server), Apache-2.0. Downloaded by `scripts/prepare-server.sh` into the ignored build directory and bundled in the APK at build time. SHA-256 is pinned in that script. The server binary is not committed to this repository.
- Gradle wrapper 7.0.2, Apache-2.0, copied from the reference project's wrapper. The wrapper JAR is build tooling, not an application build output.

The application uses the reference project's approach to ADB authentication, but implements the official scrcpy 5.0.1 transport, decoding, UI, and lifecycle independently. Reference: [las2mile/scrcpy-android](https://github.com/huage2580/scrcpy-android), GPL-3.0.

## 无线调试配对

- [BoringSSL](https://github.com/google/boringssl)，固定 commit `0131ae9d78e42fcc2c1811315e62410ad96d8230`，Apache-2.0，[许可](BoringSSL-LICENSE.txt)。`scripts/prepare-crypto.py` 校验源码归档 SHA-256，`scripts/build-crypto.sh` 使用 NDK 为四种 ABI 单独编译静态库；源码下载、库和构建缓存均留在忽略目录。JNI 共享库隐藏静态库符号，避免与 Android 系统的加密库混用。
- `app/src/main/cpp/AdbCrypto.c` 复用 [scrcpy-ios](https://github.com/felix021/scrcpy-ios/tree/28d2c66d123e11b3410bce62a4fdbd67172be4fc) 的 GPL-3.0 包装。其证书生成及 SPAKE2 / HKDF / AES-GCM 基础代码改编自 [lionlggnd/ADBClient-iOS](https://github.com/lionlggnd/ADBClient-iOS) 的 `AdbNative.c`，固定 commit `31057ba6defd0d1a7454503e662f49b05ef21c2a`，GPL-3.0。这里通过内存 BIO 与 Java Socket 交换记录，不使用上游阻塞 Socket 实现。
- [ZXing core 3.5.3](https://github.com/zxing/zxing/tree/zxing-3.5.3)，Apache-2.0，[许可](zxing-LICENSE.txt)。用于生成 ADB 二维码；不引入相机或扫码权限。对应许可同时随 APK assets 分发。
- adblib 额外支持 STLS 升级，TCP 与 USB 保留既有流接口；RSA 公钥备注使用可配置的本机名称。TLS、配对与 USB 使用同一持久化身份。

无线协议依据 [AOSP ADB Wi-Fi 架构](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/adb_wifi.md)。
