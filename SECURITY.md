# Security Policy

VIO Bridge is a personal hobby project. There are no warranties — see the
[LICENSE](LICENSE).

## Reporting

If you find a security or safety issue (for example, something that could corrupt
a connected device, leak location data, or crash the host phone), please open a
GitHub issue. If it is sensitive, mark it clearly and avoid posting a working
exploit; a description of the problem is enough to start.

## Scope notes

- The app talks to the VIO only over its display, event, and sync channels. It
  deliberately never writes to the firmware channel.
- Location is only collected while actively navigating by bike; please flag any
  code path that would collect it otherwise.
