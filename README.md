# App Lock (One UI style)

Personal Android app lock. Builds automatically with GitHub Actions on every push to `main`.

## Features
- System-style lock screen showing the **locked app's own icon and name** (PIN keypad, pattern or password, optional fingerprint).
- App Lock can **lock itself**.
- **Custom unlock duration**: immediately, preset times, until the screen turns off, or any custom time up to 24 hours.
- Search box, **Lock all / Unlock all** buttons.
- English One UI-inspired design with light and dark mode.

## Download the APK
GitHub → **Actions** → latest green run → **Artifacts** → `AppLock-release-apk`.

## Notes
- The release APK is signed with the key in `app/keystore/applock.jks` so future builds install as updates. Keep this repository **private**.
- The app uses an AccessibilityService only to detect which app is in the foreground. No data is collected or sent.
