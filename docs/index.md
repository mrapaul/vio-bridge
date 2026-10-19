---
title: VIO Bridge
---

# Teaching a TomTom VIO to speak my own navigation

The TomTom VIO is a small, round, 320×320 screen that clips to a scooter or bike
handlebar and pairs with your phone. It was built to be a dumb second display for
TomTom's own scooter app. I wanted it to show **bike** directions, from my own
app, without any of TomTom's cloud account plumbing in the way. This is how I got
there — and the code is on [GitHub](https://github.com/mrapaul/vio-bridge) under
the MIT license.

> **Unofficial project.** I'm not affiliated with TomTom, and this isn't endorsed
> by them. It's interoperability work on a device I own. Use at your own risk.

## The device, and the gap

The VIO is a nice piece of hardware going mostly unused: a sunlight-readable round
display with a battery, touch input, and a Bluetooth link. The official app drives
it as a scooter dashboard. There's no "show me cycling directions" mode, and no
public SDK.

So the question was simply: can the phone pretend to be the thing the VIO expects
to talk to, and then draw whatever I want on it?

## Listening to the conversation

The VIO connects over Bluetooth **RFCOMM** and speaks TomTom's "Forza" protocol.
The shape of it, once you watch a session:

- It advertises a service UUID and, on connect, sends a **sync request**. Answer
  it with a short `FZA` acknowledgement and it hands over device info — firmware
  version and the 320×320 screen size.
- The screen is driven by streaming **indexed-palette PNG frames** to a display
  channel. You render a bitmap on the phone, encode it as a small palette PNG, and
  push it over.
- A separate **event channel** carries touch input, battery level and status, and
  power events (user power-off, low battery, suspend).
- There is also a firmware channel. **I never touch it** — writing there can brick
  the device. Everything here stays on the display, event, and sync channels.

Nothing about this cracks encryption or defeats a protection measure; it's the
same clean-room, watch-and-reimplement approach that community projects like
Gadgetbridge use for all sorts of wearables.

## Drawing my own screens

Once the phone can stream frames, the VIO is just a canvas. VIO Bridge renders:

- a **map** with the route line and a position marker,
- **turn-by-turn** cards with a big manoeuvre arrow and distance,
- a **battery / status** screen,
- and an idle clock.

The arrows are drawn in code rather than shipped as images, so they stay crisp at
any angle and the app carries no asset bundle.

Two details mattered more than I expected. First, the VIO's battery is small, so
the renderer **doesn't re-send a frame that hasn't changed** (apart from an
occasional keep-alive), and it **auto-dims** the screen when no route is active.
Second, the round screen needs everything laid out on a disc — text and icons
clipped to a circle, not a rectangle.

## Routing, without the cloud

For navigation I didn't want to depend on anyone's proprietary service. VIO Bridge
has two modes:

1. **Built-in bike navigation.** You pick a destination and it fetches a cycling
   route from OpenStreetMap's routing (OSRM bicycle profile), draws it on the VIO,
   and follows your GPS along it. When you leave the route it notices you're off
   by more than a threshold for a few fixes and **re-routes automatically** from
   where you are. Destination search uses the phone's geocoder with a Nominatim
   fallback.

2. **Google Maps mirror.** If you'd rather navigate in Google Maps, the app reads
   the cycling-navigation notification and mirrors the next instruction onto the
   VIO instead.

To test all this without actually riding across a city, there's a small mock-GPS
**route simulator** triggered over `adb`: it replays a route — optionally through
a deliberate wrong-turn detour — so I can watch the re-routing logic work at a
desk. That's how the reroute path was validated: plan a route, send the rider the
wrong way, and confirm it recovers and still arrives.

## A note on location and privacy

An always-connected handlebar display could quietly become an always-on location
tracker. I didn't want that. So location is **gated on navigation**: the app uses
GPS only while a bike navigation is actually running. Connecting the VIO, or
leaving it showing the clock, never starts location tracking. It's a small design
choice but the kind I think a personal device should make by default.

## Building it the boring way

The app has **no Gradle and no third-party libraries**. It builds from a shell
script that runs the Android toolchain directly:

```
aapt2 → javac → d8 → zipalign → apksigner
```

One `./build.sh` produces the APK. It's refreshing how little you actually need
for a focused app, and it makes the whole thing easy to read end to end — about
3,000 lines of Java across 17 files.

## What I learned

- A "dumb display" is only dumb until you can speak its protocol; then it's a
  blank canvas you own.
- Watching a real session beats guessing. The handshake and the frame format fell
  out of observing the official app, not from any document.
- Battery and privacy are features, not afterthoughts, on a device you wear.

The source, build instructions, and safety notes are in the
[repository](https://github.com/mrapaul/vio-bridge). If you have a VIO gathering
dust, maybe this gives it a second life.
