# Contributing

Thanks for your interest in VIO Bridge. This is a small, personal
interoperability project, but issues and pull requests are welcome.

## Before you start

- **Never touch the VIO firmware channel.** The project speaks only to the
  display, event and sync channels. Writing to the firmware-update channel can
  permanently brick the device. PRs that write to it will not be accepted.
- This is **unofficial** software, not affiliated with TomTom. Keep contributions
  to clean-room interoperability work: don't include TomTom firmware, proprietary
  assets, or decompiled code from the official app.

## Building

No Gradle. With an Android SDK (build-tools 36) and JDK 17+:

```sh
./build.sh          # → build/vio-bridge.apk
adb install -r --no-incremental build/vio-bridge.apk
```

## Code style

- Match the surrounding style: small, focused classes; comments explain *why*,
  not *what*.
- Keep the app dependency-free (no third-party libraries) and Gradle-free.
- Prefer behaviour that saves the VIO's battery (don't re-send unchanged frames,
  don't collect location unless navigating).

## Pull requests

1. Keep each PR focused on one change.
2. Update `CHANGELOG.md` under an "Unreleased" heading.
3. Describe how you tested it (the `RouteSimulator` can replay routes over adb
   without riding — see the README).
4. Bump `--version-code`/`--version-name` in `build.sh` only if asked.
