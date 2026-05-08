<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Apple Trace Changelog

## [Unreleased]

## [1.0.0] - 2026-05-07

### Added

- First public release of **Apple Trace**.
- Live log streaming from booted iOS Simulators (`xcrun simctl spawn ... log stream`).
- Live log streaming from physical iOS devices via `idevicesyslog` (requires `brew install libimobiledevice`).
- Auto device discovery for both simulators and physical devices.
- Color-coded console output per log level (Debug, Info, Default, Error, Fault).
- Server-side filters for simulators: bundle id (subsystem), process, log level.
- Client-side filters: free-text search across streamed entries.
- Tool window with Start / Stop / Clear controls and a status bar.

[Unreleased]: https://github.com/daniele-NA/apple-trace/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/daniele-NA/apple-trace/releases/tag/v1.0.0
