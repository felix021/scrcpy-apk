# Google Play reviewer instructions

No app account, subscription, advertising or demo login is required. An Android device owned or authorized by the tester is required as the controlled device. Nothing connects to a developer-owned host or relay.

1. Install this client on an Android 8.0+ phone or tablet. For wireless pairing, the controlled Android device must run Android 11+ and have Developer options → Wireless debugging enabled.
2. Put both devices on the same Wi-Fi network. Open Pair wireless debugging in this client. Scan the client QR using the target Android's Wireless debugging QR option, or enter the address, pairing port and six-digit code shown by the target.
3. Use the discovered connection port after pairing; it differs from the temporary pairing port. Connect to see and control the entire Android screen.
4. Touch input, the three navigation keys, system Back and edge gestures operate the authorized target. The keyboard button opens the six-row keyboard; the gear opens runtime audio, microphone and rotation settings.
5. Remote audio and microphone forwarding have separate switches. Microphone forwarding is off by default and asks for permission only when enabled. Capture availability depends on Android and app policies.
6. USB is optional: use compatible USB Host hardware and a data cable, select USB, and approve this client's USB permission plus debugging authorization on the controlled device. Classic TCP ADB is also supported.
7. Open runtime settings and Disconnect; confirm to close the session. Moving the app to the background also closes the session and restores its temporary remote settings.

The pinned scrcpy server and microphone DEX run on the authorized remote Android under ADB, never through a developer relay. Only this session's remote temporary files and processes are cleaned up. There is no persistent target APK installation or hidden background control.

Privacy: https://scrcpy.oh-my-term.cn/privacy.html
Support: https://scrcpy.oh-my-term.cn/support.html
Source: https://github.com/moteng-network/scrcpy-apk
