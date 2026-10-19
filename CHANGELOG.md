# Changelog

All notable changes to this project are documented here. Versions correspond to
the `--version-name` in `build.sh`.

## [1.4] — 2026-10-19

### Added
- **Sleep after arrival.** Once the destination is reached and the arrival screen
  has been shown, the VIO turns its screen off (brightness 0) and the phone stops
  re-rendering and encoding frames — saving power on both the display and the
  phone. A tap on the VIO, or starting a new route, wakes it.

## [1.3] — 2026-10-19

### Changed
- **Location is now gated on navigation.** GPS/network is used only while a bike
  navigation is active (built-in or the Google Maps mirror), never merely because
  the VIO is connected. Reconciled in a single `updateLocation()` path.
- **Hardened location handling.** `LocationTracker` subscribes to both GPS and
  network providers and picks the freshest last-known fix across providers
  (fused included), so a fix still reaches navigation if one provider drops out.

## [1.2] — 2026-10-19

### Fixed
- Ride simulator / navigation bootstrap could fail with "no current position"
  after a test provider was torn down. It now falls back through all providers
  (fused/passive included) for the initial fix.

## [1.1]

### Added
- **Built-in bike navigation**, independent of Google Maps: OSRM cycling routes,
  route line and turn arrows on the VIO, automatic re-routing when off-route,
  arrival detection, and destination search (Geocoder + Nominatim).

## [1.0]

### Added
- Bluetooth (Forza protocol) bridge to the TomTom VIO: sync handshake, device
  info, indexed-palette PNG frame streaming to the 320×320 display.
- Google Maps cycling-navigation mirror via notification listener.
- Touch gestures (switch screens, zoom the map).
- Battery-aware rendering: unchanged frames aren't re-sent; idle auto-dimming.
- Boot auto-start and a foreground service.

[1.4]: https://github.com/mrapaul/vio-bridge/releases/tag/v1.4
[1.3]: https://github.com/mrapaul/vio-bridge/releases/tag/v1.3

<!-- 1.0–1.2 predate the public repo and have no tagged release. -->

