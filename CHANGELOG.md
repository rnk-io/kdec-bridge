# Changelog

## 0.4

A reliability release. It addresses connections that did not come back by themselves after a network change, a restart or a long idle period.

### Fixed

- **The phone stayed unreachable after a network change.** On Android, the tailnet node cannot observe the network and re-reads its interfaces only every 10 minutes. The app now reports every network change to it, including changes underneath a connected VPN app, so the node finds its new endpoints within seconds.
- **The app's ADB key stopped being trusted.** Android removes an ADB key that has not connected for seven days. The bridge used its key only to open the port after a restart, so the key could be gone by then, and the port stayed closed until the key was allowed again. While TCP ADB is on, the app now turns that timeout off. The new option **Keep ADB authorizations while TCP ADB is on** controls this; it is ticked by default and applies to all ADB authorizations on the phone. The app also checks its key every 12 hours and shows a notification if adbd refuses it.
- **Repeating lines filled the event log.** A connection attempt that keeps failing the same way is now logged at most every 10 minutes.
- **A stop from outside the app was recorded as a requested stop.** The bridge was then not restarted after a reboot or when the app was opened. Only **Stop bridge** and the notification's **Stop** action count as requested now.

### Added

- **Opening the ADB port on demand.** When `adb` or scrcpy connects from the tailnet and port 5555 is closed, the bridge opens it and holds the connection for up to 9 seconds, so the first attempt usually succeeds. If the port cannot be opened, further connections start a new attempt only after a pause of 1 to 30 minutes.
- **Retries.** A failed attempt to open the port is repeated after 15, 30, 60, 120 and 300 seconds, instead of waiting for the next 10-minute check.
- **Retry on unlock.** Android asks about an unknown Wi-Fi network only while the phone is unlocked. A refusal with the screen locked is tried again at the next unlock.
- **Wakelock while opening the port.** Attempts that start with the screen off no longer fail because the phone suspends halfway. The wakelock is held for the attempt only.
- **Watchdog.** A repeating alarm restarts the bridge if Android ended it. It does not wake the phone.
- **Notifications when action is needed.** A separate **Action needed** category reports an ADB key that has to be allowed again, a Wi-Fi network that has to be allowed for Wireless debugging, a pending tailnet login, and a restart that Android refused. Tapping the ADB notifications starts **Turn on TCP ADB**.

### Changed

- The app requests the `WAKE_LOCK` permission.
- While TCP ADB is on, Android's ADB authorization timeout is off unless **Keep ADB authorizations while TCP ADB is on** is unticked. The previous setting is put back when TCP ADB is turned off.
- To hide the status notification, turn off the **Bridge status** category rather than all of the app's notifications, so that **Action needed** notifications still appear.

### Updating

Install over the existing app; settings are kept. If the `adb` line in the status shows that the app's key is not allowed, tap **Turn on TCP ADB** and allow it again. See [docs/setup.md](docs/setup.md#10-allow-the-apps-adb-key).

## 0.3

- ADB over the tailnet for scrcpy: the bridge keeps adbd listening on TCP port 5555 and forwards it from the tailnet.
- The app's own ADB key, trusted through the "Allow USB debugging?" prompt or by pairing with Wireless debugging.
- The bridge restarts after an app update.

## 0.2

- First release: KDE Connect between a phone and a computer over a userspace Tailscale node, without using the phone's VPN slot.
