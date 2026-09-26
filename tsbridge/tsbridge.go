// Package tsbridge runs a userspace Tailscale node and forwards KDE Connect's
// ports over it.
//
// tsnet runs WireGuard and a userspace TCP/IP stack inside this process. It
// needs no TUN device and no routes, and therefore no VpnService, so another
// VPN app can keep the system VPN slot.
package tsbridge

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/logtail"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// Logger receives status messages for the app's event log.
type Logger interface{ Log(msg string) }

// LinkWatcher is notified when the control channel goes up or down.
type LinkWatcher interface{ OnLink(up bool) }

// NetInfo is implemented on the Android side; see SetNetInfo.
type NetInfo interface{ Interfaces() string }

// Callbacks have their own lock: they are invoked from goroutines that outlive
// Start and Stop, and atomic.Value cannot hold a nil interface.
var (
	cbMu    sync.RWMutex
	logger  Logger
	watcher LinkWatcher
)

var (
	mu        sync.Mutex // guards srv, listeners, upCancel
	srv       *tsnet.Server
	listeners []net.Listener
	upCancel  context.CancelFunc

	ctrlAlive   atomic.Int64
	stopping    atomic.Bool
	statusCache atomic.Value // string
	loginURL    atomic.Value // string
	controlPort atomic.Int64 // port actually bound for the control channel
)

func logf(format string, a ...any) {
	cbMu.RLock()
	l := logger
	cbMu.RUnlock()
	if l != nil {
		l.Log(fmt.Sprintf(format, a...))
	}
}

func setStatus(s string) { statusCache.Store(s) }

// SetLinkWatcher registers a callback for control-link transitions.
func SetLinkWatcher(w LinkWatcher) {
	cbMu.Lock()
	watcher = w
	cbMu.Unlock()
}

func notifyLink(up bool) {
	cbMu.RLock()
	w := watcher
	cbMu.RUnlock()
	if w != nil {
		go w.OnLink(up)
	}
}

// Start brings up the node and all forwarded ports. It blocks until the node
// is authenticated, which can take a long time on first run, so it must not be
// called from the UI thread. Stop aborts a pending login.
//
// Forward ports (fwdFirst..fwdLast) listen on loopback and connect to the
// computer; they carry file transfers started by the computer. Reverse ports
// (revFirst..revLast) listen on the tailnet and connect to loopback; they
// carry file transfers started by the phone, which the computer connects to.
func Start(stateDir, authKey, hostname, target string,
	controlLocal, controlRemote, fwdFirst, fwdLast, revFirst, revLast int,
	lg Logger) (err error) {

	// tsnet panics instead of returning an error on some start-up paths. A
	// panic that crosses the JNI boundary aborts the app, so convert it.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("tsnet panic: %v", r)
			setStatus("start failed: " + err.Error())
		}
	}()

	mu.Lock()
	if srv != nil {
		mu.Unlock()
		return nil
	}
	cbMu.Lock()
	logger = lg
	cbMu.Unlock()
	stopping.Store(false)
	controlPort.Store(0)

	// logpolicy panics ("no safe place found to store log state") when none of
	// its candidate directories is writable, which is the case on Android.
	// TS_LOGS_DIR takes precedence, so point it at the app's directory.
	os.Setenv("TS_LOGS_DIR", stateDir)

	// Keep diagnostics on the device: do not upload logs to Tailscale.
	logtail.Disable()

	setStatus("starting")
	s := &tsnet.Server{
		Dir:      stateDir,
		AuthKey:  authKey,
		Hostname: hostname,
		// UserLogf carries the interactive login URL when no auth key is given.
		UserLogf: func(format string, a ...any) {
			m := fmt.Sprintf(format, a...)
			if i := strings.Index(m, "https://login.tailscale.com/"); i >= 0 {
				u := strings.Fields(m[i:])[0]
				if prev, _ := loginURL.Load().(string); prev != u {
					loginURL.Store(u)
					setStatus("needs login")
					logf("%s", m)
				}
				return // log each URL once
			}
			logf("%s", m)
		},
		Logf: func(format string, a ...any) {},
	}
	if err := s.Start(); err != nil {
		mu.Unlock()
		setStatus("start failed: " + err.Error())
		return err
	}
	ctx, cancel := context.WithCancel(context.Background())
	srv = s
	upCancel = cancel
	mu.Unlock()

	// Up blocks until the node is authenticated, indefinitely if the login is
	// never completed. The lock is not held here, so Stop can cancel it.
	_, err = s.Up(ctx)
	if err != nil || stopping.Load() {
		mu.Lock()
		if srv == s {
			srv = nil
			upCancel = nil
		}
		mu.Unlock()
		s.Close()
		if err == nil {
			err = fmt.Errorf("stopped before login completed")
		}
		setStatus("login aborted: " + err.Error())
		return err
	}
	loginURL.Store("")
	setStatus("up as " + hostname)
	logf("tailnet up as %s", hostname)

	mu.Lock()
	defer mu.Unlock()
	if srv != s { // Stop was called while waiting
		return fmt.Errorf("stopped during startup")
	}

	// The control channel is required; fail if no port in its range is free.
	// The range lies within KDE Connect's 1716-1764 window, below the payload
	// ports.
	port, err := forwardControl(s, controlLocal, fwdFirst-1, target, controlRemote)
	if err != nil {
		return err
	}
	controlPort.Store(int64(port))
	if port != controlLocal {
		logf("port %d busy, control channel on :%d instead", controlLocal, port)
	}

	for p := fwdFirst; p <= fwdLast; p++ {
		if err := forwardOne(s, p, target, p, false); err != nil {
			logf("payload :%d not available - %v", p, err)
		}
	}
	logf("forward :%d->%d + payload :%d-%d", port, controlRemote, fwdFirst, fwdLast)

	for p := revFirst; p <= revLast; p++ {
		if err := reverse(s, p); err != nil {
			logf("reverse :%d not available - %v", p, err)
		}
	}
	logf("reverse tailnet :%d-%d -> loopback", revFirst, revLast)
	return nil
}

// ControlPort reports the loopback port the control channel actually bound.
// 0 while not running.
func ControlPort() int { return int(controlPort.Load()) }

// forwardControl binds the first free loopback port in [first,last].
func forwardControl(s *tsnet.Server, first, last int, target string, remotePort int) (int, error) {
	for p := first; p <= last; p++ {
		if err := forwardOne(s, p, target, remotePort, true); err == nil {
			return p, nil
		}
	}
	return 0, fmt.Errorf("no free loopback port in %d-%d for the control channel", first, last)
}

// forwardOne listens on loopback and connects to the computer over the
// tailnet.
func forwardOne(s *tsnet.Server, localPort int, target string, remotePort int, isControl bool) error {
	ln, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(localPort))
	if err != nil {
		return err
	}
	listeners = append(listeners, ln)
	dst := net.JoinHostPort(target, strconv.Itoa(remotePort))
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				if !stopping.Load() {
					logf("accept :%d ended: %v", localPort, err)
				}
				return
			}
			go func() {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				up, err := s.Dial(ctx, "tcp", dst)
				cancel()
				if err != nil {
					// Only established connections count as a live link.
					logf("dial %s failed: %v", dst, err)
					c.Close()
					return
				}
				if isControl {
					if ctrlAlive.Add(1) == 1 {
						notifyLink(true)
					}
					defer func() {
						if ctrlAlive.Add(-1) == 0 {
							notifyLink(false)
						}
					}()
				}
				logf("linked :%d -> %s", localPort, dst)
				splice(c, up)
			}()
		}
	}()
	return nil
}

// reverse listens on the tailnet and forwards connections to loopback, so the
// computer can connect to the phone for transfers the phone started.
func reverse(s *tsnet.Server, port int) error {
	ln, err := s.Listen("tcp", ":"+strconv.Itoa(port))
	if err != nil {
		return err
	}
	listeners = append(listeners, ln)
	local := "127.0.0.1:" + strconv.Itoa(port)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				d, err := net.DialTimeout("tcp", local, 10*time.Second)
				if err != nil {
					c.Close()
					return
				}
				logf("reverse tailnet :%d -> %s", port, local)
				splice(c, d)
			}()
		}
	}()
	return nil
}

func splice(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)

	// Close both sides as soon as either direction ends. KDE Connect does not
	// use half-close, and waiting for both directions would keep a dead link
	// open until TCP timed out, delaying the link-down notification.
	<-done
	a.Close()
	b.Close()
	<-done
}

// ControlActive reports the number of live control connections. The app
// injects only while this is zero: an identity packet received during a live
// link makes KDE Connect reset it (addOrUpdateLink).
func ControlActive() int { return int(ctrlAlive.Load()) }

// DiscoverIdentity learns the identity of the computer at target. It sends a
// probe identity; the computer connects back and sends its own identity in
// cleartext before the TLS upgrade.
//
// Returns the raw identity line (a complete kdeconnect.identity packet).
func DiscoverIdentity(target string, timeoutSec int) (string, error) {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return "", fmt.Errorf("tailnet not started")
	}

	// Any free tailnet port inside KDE Connect's accepted range will do.
	var ln net.Listener
	var listenPort int
	for p := 1725; p <= 1738; p++ {
		l, err := s.Listen("tcp", ":"+strconv.Itoa(p))
		if err == nil {
			ln, listenPort = l, p
			break
		}
	}
	if ln == nil {
		return "", fmt.Errorf("no free tailnet port for discovery")
	}
	defer ln.Close()

	probe := map[string]any{
		"id":   time.Now().UnixMilli(),
		"type": "kdeconnect.identity",
		"body": map[string]any{
			"deviceId":   probeDeviceId(),
			"deviceName": "kdec-bridge-probe", "deviceType": "phone",
			"protocolVersion": 8, "tcpPort": listenPort,
			"incomingCapabilities": []string{}, "outgoingCapabilities": []string{},
		},
	}
	raw, _ := json.Marshal(probe)

	// Keep one UDP socket for the whole attempt. netstack writes are
	// asynchronous, and closing the socket right after Write can drop the
	// datagram before it is sent.
	dctx, dcancel := context.WithTimeout(context.Background(), 10*time.Second)
	uc, err := s.Dial(dctx, "udp", net.JoinHostPort(target, "1716"))
	dcancel()
	if err != nil {
		return "", fmt.Errorf("udp dial to %s failed: %w", target, err)
	}
	defer uc.Close()

	send := func() error {
		_, werr := uc.Write(append(raw, '\n'))
		return werr
	}

	// tsnet listeners do not reliably support SetDeadline, so Accept is raced
	// against a timer.
	type acceptRes struct {
		c   net.Conn
		err error
	}
	ch := make(chan acceptRes, 1)
	go func() {
		ac, aerr := ln.Accept()
		ch <- acceptRes{ac, aerr}
	}()

	// The computer accepts at most one connection per second from an address,
	// across TCP and UDP. A probe that arrives in the same second as another
	// connection is dropped, so resend until the computer connects back.
	if err := send(); err != nil {
		return "", err
	}
	deadline := time.After(time.Duration(timeoutSec) * time.Second)
	resend := time.NewTicker(3 * time.Second)
	defer resend.Stop()

	var c net.Conn
wait:
	for {
		select {
		case r := <-ch:
			if r.err != nil {
				return "", fmt.Errorf("dial-back failed: %w", r.err)
			}
			c = r.c
			break wait
		case <-resend.C:
			if err := send(); err != nil {
				return "", err
			}
		case <-deadline:
			return "", fmt.Errorf("no dial-back from %s within %ds", target, timeoutSec)
		}
	}
	defer c.Close()
	c.SetReadDeadline(time.Now().Add(time.Duration(timeoutSec) * time.Second))
	line, err := bufio.NewReader(c).ReadString('\n')
	if err != nil && line == "" {
		return "", err
	}
	return line, nil
}

// probeDeviceId returns a random 32-character hex id.
//
// kdeconnect-kde validates identity packets against
//
//	DEVICE_ID_REGEX("^[a-zA-Z0-9_-]{32,38}$")
//
// and silently drops packets that fail, so a shorter id would never get a
// response.
func probeDeviceId() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// Fall back to a zero-padded timestamp, still 32 characters.
		return fmt.Sprintf("%032x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b[:]) // 32 characters
}

// Status is non-blocking and safe to call from the UI thread.
func Status() string {
	if v, ok := statusCache.Load().(string); ok && v != "" {
		return v
	}
	return "stopped"
}

// LoginURL returns the interactive auth URL while one is pending, else "".
func LoginURL() string {
	if v, ok := loginURL.Load().(string); ok {
		return v
	}
	return ""
}

// Stop closes everything, including a Start that is still waiting for the
// login to complete. It is safe to call when nothing is running.
func Stop() {
	stopping.Store(true)
	mu.Lock()
	s := srv
	ls := listeners
	cancel := upCancel
	srv = nil
	listeners = nil
	upCancel = nil
	mu.Unlock()

	if cancel != nil {
		cancel()
	}
	for _, l := range ls {
		l.Close()
	}
	if s != nil {
		s.Close()
	}
	setStatus("stopped")
	loginURL.Store("")
	controlPort.Store(0)
	cbMu.Lock()
	logger = nil
	watcher = nil
	cbMu.Unlock()
}

// --- Android network interface bridging --------------------------------------
//
// Go's net.Interfaces() reads the netlink RIB, which Android's SELinux policy
// denies to apps ("netlinkrib: permission denied"). The app enumerates
// interfaces in Java and supplies them here, and they are registered with
// netmon. Tailscale's Android client takes the same approach.
//
// Interfaces returns JSON:
//
//	[{"name":"wlan0","index":5,"mtu":1500,"up":true,"loopback":false,
//	  "p2p":false,"multicast":true,"addrs":["192.168.1.21/24"]}]

type jsonIface struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Up        bool     `json:"up"`
	Loopback  bool     `json:"loopback"`
	P2P       bool     `json:"p2p"`
	Multicast bool     `json:"multicast"`
	Addrs     []string `json:"addrs"`
}

// SetNetInfo must be called before Start.
func SetNetInfo(ni NetInfo) {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		var raw []jsonIface
		if err := json.Unmarshal([]byte(ni.Interfaces()), &raw); err != nil {
			return nil, fmt.Errorf("bad interface json: %w", err)
		}
		out := make([]netmon.Interface, 0, len(raw))
		for _, r := range raw {
			var fl net.Flags
			if r.Up {
				fl |= net.FlagUp | net.FlagRunning
			}
			if r.Loopback {
				fl |= net.FlagLoopback
			}
			if r.P2P {
				fl |= net.FlagPointToPoint
			}
			if r.Multicast {
				fl |= net.FlagMulticast
			}
			ifc := &net.Interface{Index: r.Index, MTU: r.MTU, Name: r.Name, Flags: fl}
			addrs := make([]net.Addr, 0, len(r.Addrs))
			for _, a := range r.Addrs {
				ip, ipnet, err := net.ParseCIDR(a)
				if err != nil {
					continue
				}
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
			}
			out = append(out, netmon.Interface{Interface: ifc, AltAddrs: addrs})
		}
		if len(out) == 0 {
			return nil, fmt.Errorf("no interfaces reported by android")
		}
		return out, nil
	})
}
