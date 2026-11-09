# scrcpy APK

[简体中文](README.md) · **English**

View and control an Android device from another Android phone over network ADB or a USB cable. The client handles the connection, video decoding and input directly, with no computer relay or permanently installed app on the remote device.

- Wireless debugging with QR / six-digit code pairing, port discovery, TLS connections and a customizable local device name.
- Save and switch complete device profiles, with H.264 / H.265 and video quality presets.
- Touch, multitouch, Back / Home / Recents, edge gestures and a six-row QWERTY keyboard.
- Independent switches for remote system audio → local playback and local microphone → remote input. Microphone forwarding is off by default.
- Portrait and landscape layouts, remote orientation sync, manual rotation, view-only mode and keep-awake settings.
- A consistent dark UI, Chinese / English with a system-language option, and app settings with version and repository information.

The application ID is now `cn.ohmyterm.scrcpy`. Earlier `cn.felix021.scrcpy` installs are separate apps; profiles and ADB identities do not migrate automatically. Configure and authorize the new app again.

## Download and install

Download the APK from [GitHub Releases](https://github.com/moteng-network/scrcpy-apk/releases/latest) to install. In-place upgrades require the same application ID; earlier IDs need the setup described above. Each release includes a SHA-256 checksum file. The APK uses a non-debuggable release build and retains the signing certificate used by earlier versions.

## Quick start

The client requires Android 8.0+. Remote audio playback requires Android 11+ on the remote device; other capabilities depend on the remote OS, codecs and vendor permissions.

**Network:** Enable traditional ADB TCP on the remote device, for example with `adb -s '<target-serial>' tcpip 5555` from an already authorized adb host. Enter the reachable IP / hostname and port in the app, connect, and approve the initial debugging authorization on the remote device. Alternatively, on remote Android 11+, enable Wireless debugging, tap “Pair wireless debugging” in this app, and use a QR code or six-digit pairing code. After pairing, connect to its wireless debugging port.

**USB:** Enable USB Host / OTG on the controlling phone and USB debugging on the remote phone, then connect them with a data-capable cable. Select “USB ADB” and the device, connect, and approve local USB access and remote debugging authorization. USB ADB has passed user-reported testing on real phones.

Once connected, interact with the remote screen directly. The keyboard icon opens the virtual keyboard; the gear opens session settings, where disconnecting requires confirmation. The gear on the connection screen opens local device name, language and About settings. See the [user guide](docs/usage.md) for controls, audio and system-gesture limitations, or [troubleshooting](docs/troubleshooting.md) for common issues.

## Build

Install JDK 11, Python 3.8+, Android SDK 31, NDK 28.0.13004108 and CMake 3.31.6, configure `JAVA_HOME` and `ANDROID_HOME`, then run:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The first build downloads and verifies a pinned official scrcpy server and BoringSSL source, then builds the crypto libraries. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`; the application ID is `cn.ohmyterm.scrcpy`. Build outputs and local configuration are excluded from Git. See the [development guide](docs/development.md) for setup and offline builds.

## Documentation and contributing

The [documentation index](docs/README.md) covers usage, architecture, configuration, development, testing, releases and troubleshooting. AI contributors should start with [AGENTS.md](AGENTS.md) and load the topics relevant to their task. Historical evidence lives in the [validation archive](docs/validation/README.md).

Keep both README versions in sync. Detailed technical documentation is currently in Chinese. Run `python3 scripts/check-docs.py` to check local documentation links and index coverage.

## License

The application is licensed under [GPL-3.0](LICENSE). The ADB library uses a BSD license, and the official scrcpy server uses Apache-2.0. See [third-party components](third_party/README.md) for sources and local changes. Reference project: [scrcpy-android](https://github.com/huage2580/scrcpy-android).
