# Third-party components

- `app/src/main/java/com/tananaev/adblib`: adblib by Cameron Gutman and contributors, vendored from tananaev/adblib commit `4b3d25641933b92b729ee67d17791992158d20be`. BSD license: [adblib-LICENSE](adblib-LICENSE). Local changes: preserve unread data when peer closes, synchronize stream-open handshake, saturate infinite connect timeout, use 256 KiB receive payloads, acknowledge received packets when consumed for bounded memory, reject oversized peer payloads, accept owned byte streams for USB Host as well as TCP sockets. These sources keep the standalone Android ADB transport independent of a host adb executable.
- Official [scrcpy v5.0.1 server](https://github.com/Genymobile/scrcpy/tree/v5.0.1/server), Apache-2.0. Downloaded by `scripts/prepare-server.sh` into the ignored build directory and bundled in the APK at build time. SHA-256 is pinned in that script. The server binary is not committed to this repository.
- Gradle wrapper 7.0.2, Apache-2.0, copied from the reference project's wrapper. The wrapper JAR is build tooling, not an application build output.

The application uses the reference project's approach to ADB authentication, but implements the official scrcpy 5.0.1 transport, decoding, UI, and lifecycle independently. Reference: [las2mile/scrcpy-android](https://github.com/huage2580/scrcpy-android), GPL-3.0.
