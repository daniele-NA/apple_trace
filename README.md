# Apple Trace

> Logcat-style live console for **iOS Simulators** and **physical iOS devices**, inside Android Studio (or any IntelliJ-based IDE) on macOS.

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/daniele-NA/apple-trace/releases)
[![Platform](https://img.shields.io/badge/platform-macOS-lightgrey.svg)]()
[![IDE](https://img.shields.io/badge/IDE-Android%20Studio%20%7C%20IntelliJ-orange.svg)]()

If your day looks like _"Android in the morning, iOS in the afternoon"_, Apple Trace lets you stay inside Android Studio while debugging iOS. It opens a tool window that mirrors the Logcat experience, but talks to Apple's tooling under the hood (`xcrun simctl`, `xcrun devicectl`, `idevicesyslog`).

---

## Why

Android Studio has Logcat. Xcode has the Console. If you build cross-platform apps (Flutter, React Native, KMP, native…), context switching is friction. Apple Trace removes the trip to Console.app or `Window → Devices and Simulators` for the 80% case: _"just show me what my iPhone is logging, right now, with filters."_

## Features

- **Live log streaming** from any booted iOS Simulator and any connected iPhone / iPad.
- **Auto device discovery** — populates a dropdown with every running simulator and every plugged-in device.
- **Color-coded output** per log level (Debug, Info, Default, Error, Fault) using the IDE's themed colors. Errors and Faults pop visually.
- **Server-side filters** for simulators (push the predicate into `log stream`, so you receive only what you asked for):
  - Bundle ID (`subsystem`)
  - Process name
  - Log level
- **Client-side search** — free-text filter across the streamed buffer.
- **Start / Stop / Clear** controls and a status bar showing what's streaming.

## Requirements

- **macOS only** — the plugin shells out to `xcrun` and friends.
- **Xcode Command Line Tools** installed (`xcode-select --install`).
- **For physical devices**: install [`libimobiledevice`](https://libimobiledevice.org/) to enable `idevicesyslog`:
  ```sh
  brew install libimobiledevice
  ```
  Simulator support works without anything extra — `xcrun simctl` ships with Xcode.

## Install

### From a release `.zip`

1. Download the latest `apple-trace-x.y.z.zip` from the [Releases page](https://github.com/daniele-NA/apple-trace/releases).
2. In Android Studio: **Settings → Plugins → ⚙️ → Install Plugin from Disk…**
3. Pick the `.zip` file. Restart the IDE when prompted.

### Build from source

```sh
git clone https://github.com/daniele-NA/apple-trace.git
cd apple-trace
./gradlew buildPlugin
# Distributable lives in build/distributions/apple-trace-1.0.0.zip
```

## Usage

1. Open the **Apple Trace** tool window (View → Tool Windows → Apple Trace, or the bottom strip).
2. Pick a device from the dropdown. Hit **⟳** to refresh.
3. (Optional) Type a **Bundle ID** (e.g. `com.example.MyApp`), a **Process** name, and/or a **Level**.
4. Click **Start**. Logs stream live.
5. Use the **Search** field for free-text filtering, **Clear** to wipe the console, **Stop** to end the stream.

### Filter cheatsheet

| Filter      | Simulators                         | Physical devices                   |
|-------------|------------------------------------|------------------------------------|
| Bundle ID   | Native (`subsystem == ...`)        | _Ignored_ — syslog has no subsystem |
| Process     | Native (`process == ...`)          | Native (`idevicesyslog -p ...`)    |
| Level       | Native (`--level ...`)             | Client-side                        |
| Search      | Client-side                        | Client-side                        |

## How it works

- **Simulators** — runs `xcrun simctl spawn <udid> log stream --style ndjson [--level X] [--predicate ...]` and parses each NDJSON record.
- **Physical devices** — runs `idevicesyslog -u <udid> [-p process]` and parses Apple syslog format.

The streaming process is owned by the project: closing the tool window or the project tears it down cleanly.

## Roadmap

- [ ] Save / load filter presets per project.
- [ ] Export captured logs to file.
- [ ] Click-to-launch app (via `simctl launch` / `devicectl device process launch`).
- [ ] Native physical-device streaming via `xcrun devicectl device console` once the API stabilizes across Xcode versions.

## Contributing

Bug reports and PRs welcome. Open an issue first if you're proposing a non-trivial change.

## License

Apache 2.0 — see [LICENSE](LICENSE).
