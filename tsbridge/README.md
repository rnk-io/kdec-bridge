# tsbridge

Go library that runs a userspace Tailscale node ([tsnet](https://pkg.go.dev/tailscale.com/tsnet)) inside the Android app and forwards KDE Connect's ports over it. It also contains the ADB client the app uses to switch adbd on the phone to TCP mode. gomobile compiles it into `app/libs/tsbridge.aar`.

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
| `SetAdbForward(port)` | Opens a tailnet listener on `port` that forwards to adbd on loopback, or closes it when `port` is 0. Can be called before `Start` |
| `AdbPair(keyDir, host, port, code)` | Pairs the app's ADB key with a Wireless debugging pairing service |
| `AdbAuthorize(keyDir, host, port, timeoutSec)` | Connects to adbd's classic TCP port and, if the key is not trusted, has adbd show the "Allow USB debugging?" prompt for it |
| `AdbExec(keyDir, host, port, service, timeoutSec)` | Connects to adbd with the app's key, runs one service such as `tcpip:5555` or `usb:`, and returns its output |

## Building

Build with [`tools/build-aar.sh`](../tools/build-aar.sh) rather than calling `gomobile bind` directly; the script sets the required flags and builds from a fixed path. See [docs/building.md](../docs/building.md).

## Listeners

| Ports | Listens on | Forwards to | Carries |
|---|---|---|---|
| 1717 (up to 1738) | Loopback | Computer, port 1716 | Control channel |
| 1739–1743 | Loopback | Computer, same port | File transfers started by the computer |
| 1744–1764 | Tailnet | Loopback, same port | File transfers started by the phone |
| 5555 | Tailnet | Loopback, same port | ADB. Only while `SetAdbForward` has set it |

Holding the low payload ports on loopback moves KDE Connect's own payload server on the phone into the high range, where the tailnet listeners accept the computer's connections.

## ADB client

[`adb.go`](adb.go) implements the parts of the ADB protocol the app needs:

- **Pairing** with Wireless debugging: TLS 1.3, SPAKE2, and AES-128-GCM for the exchange of public keys.
- **Connections** to adbd. On the Wireless debugging port, adbd upgrades the connection to TLS and checks the key in the client certificate. On port 5555, it sends an RSA challenge, which the client signs. If adbd refuses the signature, `AdbAuthorize` offers the public key, and adbd asks the user to allow it.
- **One service stream** per connection, such as `tcpip:5555`, `usb:` or `shell:<command>`.

[`spake2.go`](spake2.go) is a SPAKE2 client over edwards25519, compatible with BoringSSL's implementation, which adbd uses. The key is a 2048-bit RSA key stored in PKCS#8 PEM format in the directory passed as `keyDir`.

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
