# InstaEclipse workflow

- After APK-producing changes, build on this Mac and install the debug APK on the connected wireless ADB phone unless the user says otherwise.
- The current phone is a Samsung SM-S9210. Resolve its serial with `adb devices -l` each time; wireless IP and mDNS suffixes can change.
- Android SDK root: `/opt/homebrew/share/android-commandlinetools`.
- Build with `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew testDebugUnitTest assembleDebug`.
- Install `app/build/outputs/apk/debug/app-debug.apk` with `adb -s <serial> install -r`.
- Never uninstall an existing package after a signature mismatch without explicit approval because uninstalling removes its local data.
- The user patches Instagram manually with LSPatch after the module APK is updated; remind them when a repatch is required.
- Keep local reverse-engineering artifacts under `.tmp/reverse/` (`.tmp/` is ignored by Git).
- The preserved Instagram reference is `.tmp/reverse/original/instagram-425.0.0.47.61-base.apk` (`versionCode 383105985`, SHA-256 `f9a16d14273691184e18b45dce5d52e211c63853961299f86e21dd676c04c062`). Treat files in `.tmp/reverse/original/` as immutable and never overwrite them.
