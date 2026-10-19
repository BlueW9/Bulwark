# Bulwark

An app firewall for Android. Pick the apps you want cut off from the network, separately for Wi-Fi and mobile data.

- No root. Uses Android's local VPN interface and sends the traffic of the chosen apps into a dead end
- Keeps working when the app is closed, and starts again after a reboot
- No internet permission, no tracking, no ads
- Dark theme, English only
- License: GPL-3.0-or-later

## Notes
- Android allows one VPN at a time, so Bulwark cannot run together with another VPN app.
- For the most reliable behavior turn on *Always-on VPN* for Bulwark in Android's VPN settings. Do not turn on "Block connections without VPN".

## Build
Push a tag (`git tag v1.0.0 && git push --tags`). GitHub Actions builds the APK and attaches it, with a SHA-256 checksum, to the Release.
