# tsbridge

Go library that runs a userspace Tailscale node ([tsnet](https://pkg.go.dev/tailscale.com/tsnet)) inside the Android app and forwards KDE Connect's ports over it. gomobile compiles it into `app/libs/tsbridge.aar`.

tsnet runs WireGuard and a userspace TCP/IP stack in-process. It needs no TUN device, so the app does not use Android's `VpnService`.

## API

| Function | Description |
|---|---|
| `SetNetInfo(NetInfo)` | Supplies the network interface list from Java. Call before `Start` |
| `SetLinkWatcher(LinkWatcher)` | Registers a callback for control link up and down events |
| `Start(...)` | Brings up the node and all listeners. Blocks until the node is authenticated, so call it off the UI thread |
| `Stop()` | Closes everything and aborts a pending login. Safe to call when nothing is running |
| `Status()`, `LoginURL()` | Cached node state and pending login URL. Never block |
| `ControlPort()` | Loopback port bound for the control channel, or 0 |
| `ControlActive()` | Number of live control connections |
| `DiscoverIdentity(target, timeoutSec)` | Learns the computer's identity packet over the tailnet |

## Building

Build with [`tools/build-aar.sh`](../tools/build-aar.sh) rather than calling `gomobile bind` directly; the script sets the required flags and builds from a fixed path. See [docs/building.md](../docs/building.md).

## Listeners

| Ports | Listens on | Forwards to | Carries |
|---|---|---|---|
| 1717 (up to 1738) | Loopback | Computer, port 1716 | Control channel |
| 1739–1743 | Loopback | Computer, same port | File transfers started by the computer |
| 1744–1764 | Tailnet | Loopback, same port | File transfers started by the phone |

Holding the low payload ports on loopback moves KDE Connect's own payload server on the phone into the high range, where the tailnet listeners accept the computer's connections.

## Android-specific behavior

These issues do not occur on desktop platforms.

1. **Interface enumeration.** `net.Interfaces()` reads the netlink RIB, which Android's SELinux policy denies to apps (`netlinkrib: permission denied`). The app enumerates interfaces with `java.net.NetworkInterface` and passes them as JSON through `SetNetInfo`, which registers them with `netmon.RegisterInterfaceGetter`. Tailscale's Android client takes the same approach.
2. **Log state directory.** `logpolicy` panics with `no safe place found to store log state` when none of its candidate directories is writable, which is the case on Android. `Start` sets `TS_LOGS_DIR` to the app's state directory.
3. **Panics across JNI.** A Go panic that reaches the JNI boundary aborts the whole process with `SIGABRT`. `Start` recovers panics and returns them as errors.
4. **Blocking calls.** `Up()` blocks until the node is authenticated, which never happens while it waits for a login. `Status()` returns a cached value and `LoginURL()` exposes the pending login URL, so the UI never blocks.
5. **Stopping during login.** `Start` does not hold its lock while waiting in `Up()`, and `Stop` cancels that wait, so a pending login can always be aborted.
6. **Asynchronous UDP writes.** netstack sends UDP datagrams asynchronously, and closing a socket immediately after `Write` can discard the datagram. Identity discovery keeps its UDP socket open for the whole attempt.
7. **16 KB page alignment.** gomobile produces 4 KB-aligned libraries by default. The build links with `-z max-page-size=16384`, which 16 KB-page devices require from Android 15 onwards.

## Logging

tsnet's internal logging is discarded, and upload of diagnostic logs to Tailscale is disabled with `logtail.Disable()`. Status messages intended for the user go to the app's event log through the `Logger` interface.
