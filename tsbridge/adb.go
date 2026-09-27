package tsbridge

// ADB client and tailnet forward for adbd.
//
// An Android app can change adbd's mode only as an ADB client. The app has
// its own RSA key trusted once, either by pairing with Wireless debugging
// (AdbPair) or through the "Allow USB debugging?" prompt (AdbAuthorize). It
// then connects to adbd on this phone and requests "tcpip:5555" or "usb:"
// (AdbExec), as `adb tcpip` and `adb usb` do. SetAdbForward exposes adbd's TCP
// port on the tailnet, so adb and scrcpy on the computer can reach it.

import (
	"bufio"
	"bytes"
	"crypto"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tsnet"
)

// ---- tailnet forward ---------------------------------------------------------

var (
	adbWant   atomic.Int64 // port the tailnet ADB listener should use; 0 = closed
	adbLn     net.Listener // guarded by mu
	adbLnPort int          // guarded by mu
)

// SetAdbForward opens a tailnet listener on port that forwards connections to
// adbd on loopback, or closes it when port is 0. It can be called at any time:
// before Start, the listener opens once the node is up.
func SetAdbForward(port int) (err error) {
	defer recoverTo(&err)
	adbWant.Store(int64(port))
	mu.Lock()
	defer mu.Unlock()
	if adbLn != nil && adbLnPort == port {
		return nil
	}
	closeAdbLocked()
	if port <= 0 || srv == nil || !ready {
		return nil
	}
	return openAdbLocked(srv, port)
}

func openAdbLocked(s *tsnet.Server, port int) error {
	ln, err := s.Listen("tcp", ":"+strconv.Itoa(port))
	if err != nil {
		return err
	}
	adbLn, adbLnPort = ln, port
	local := "127.0.0.1:" + strconv.Itoa(port)
	logf("adb: tailnet :%d -> %s open", port, local)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				d, err := net.DialTimeout("tcp", local, 5*time.Second)
				if err != nil {
					logf("adb: connection refused - adbd is not listening on %s", local)
					c.Close()
					return
				}
				logf("adb: tailnet connection -> %s", local)
				splice(c, d)
			}()
		}
	}()
	return nil
}

func closeAdbLocked() {
	if adbLn == nil {
		return
	}
	adbLn.Close()
	logf("adb: tailnet :%d closed", adbLnPort)
	adbLn, adbLnPort = nil, 0
}

// ---- key ---------------------------------------------------------------------

var keyMu sync.Mutex

// loadKey returns the app's ADB key, creating it on first use.
func loadKey(dir string) (*rsa.PrivateKey, error) {
	keyMu.Lock()
	defer keyMu.Unlock()
	path := filepath.Join(dir, "adbkey")
	if b, err := os.ReadFile(path); err == nil {
		blk, _ := pem.Decode(b)
		if blk == nil {
			return nil, fmt.Errorf("adb key file is not PEM")
		}
		k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
		if err != nil {
			return nil, err
		}
		rk, ok := k.(*rsa.PrivateKey)
		if !ok {
			return nil, fmt.Errorf("adb key is not RSA")
		}
		return rk, nil
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}

	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, path); err != nil {
		return nil, err
	}
	return k, nil
}

// adbPublicKey encodes pub in adb's format: base64 of the RSAPublicKey
// structure from libcrypto_utils, followed by a name.
func adbPublicKey(pub *rsa.PublicKey) string {
	const words = 2048 / 32
	r32 := new(big.Int).Lsh(big.NewInt(1), 32)
	n0inv := new(big.Int).ModInverse(new(big.Int).Mod(pub.N, r32), r32)
	n0inv.Sub(r32, n0inv) // -1/n mod 2^32
	rr := new(big.Int).Lsh(big.NewInt(1), 4096)
	rr.Mod(rr, pub.N) // (2^2048)^2 mod n

	b := make([]byte, 4+4+256+256+4)
	binary.LittleEndian.PutUint32(b[0:], words)
	binary.LittleEndian.PutUint32(b[4:], uint32(n0inv.Uint64()))
	putLE(b[8:264], pub.N)
	putLE(b[264:520], rr)
	binary.LittleEndian.PutUint32(b[520:], uint32(pub.E))
	return base64.StdEncoding.EncodeToString(b) + " kdec-bridge@android"
}

func putLE(dst []byte, n *big.Int) {
	n.FillBytes(dst)
	for i, j := 0, len(dst)-1; i < j; i, j = i+1, j-1 {
		dst[i], dst[j] = dst[j], dst[i]
	}
}

// tlsConfig returns the client configuration for adbd's TLS transports. adbd
// identifies the client by the public key in its certificate. The server
// certificate is self-signed and not checked: the peer is adbd on this phone,
// reached over loopback.
func tlsConfig(k *rsa.PrivateKey) (*tls.Config, error) {
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "kdec-bridge"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		BasicConstraintsValid: true,
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &k.PublicKey, k)
	if err != nil {
		return nil, err
	}
	cert := tls.Certificate{Certificate: [][]byte{der}, PrivateKey: k}
	return &tls.Config{
		MinVersion:         tls.VersionTLS13,
		InsecureSkipVerify: true,
		// adbd lists the keys it knows as acceptable CAs. Always offer this
		// key; the handshake fails if adbd does not trust it.
		GetClientCertificate: func(*tls.CertificateRequestInfo) (*tls.Certificate, error) {
			return &cert, nil
		},
	}, nil
}

// ---- pairing -----------------------------------------------------------------

const (
	pairingPacketVersion = 1
	pairingSpake2Msg     = 0
	pairingPeerInfo      = 1
	peerInfoSize         = 8192
)

var (
	pairingClientName = []byte("adb pair client\x00")
	pairingServerName = []byte("adb pair server\x00")
)

// AdbPair pairs the app's ADB key with the Wireless debugging pairing service
// at host:port, using the six-digit code shown by Settings.
func AdbPair(keyDir, host string, port int, code string) (err error) {
	defer recoverTo(&err)
	k, err := loadKey(keyDir)
	if err != nil {
		return err
	}
	cfg, err := tlsConfig(k)
	if err != nil {
		return err
	}
	raw, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 5*time.Second)
	if err != nil {
		return err
	}
	defer raw.Close()
	raw.SetDeadline(time.Now().Add(30 * time.Second))
	c := tls.Client(raw, cfg)
	if err := c.Handshake(); err != nil {
		return fmt.Errorf("pairing TLS handshake: %w", err)
	}

	// The password binds the code to this TLS session.
	cs := c.ConnectionState()
	ekm, err := cs.ExportKeyingMaterial("adb-label\x00", nil, 64)
	if err != nil {
		return err
	}
	sp, err := newSpake2(pairingClientName, pairingServerName, append([]byte(code), ekm...))
	if err != nil {
		return err
	}
	if err := writePairing(c, pairingSpake2Msg, sp.msg[:]); err != nil {
		return err
	}
	typ, theirMsg, err := readPairing(c)
	if err != nil {
		return err
	}
	if typ != pairingSpake2Msg {
		return fmt.Errorf("pairing: unexpected packet type %d", typ)
	}
	secret, err := sp.finish(theirMsg)
	if err != nil {
		return err
	}
	aesKey, err := hkdf.Key(sha256.New, secret, nil, "adb pairing_auth aes-128-gcm key", 16)
	if err != nil {
		return err
	}
	blk, err := aes.NewCipher(aesKey)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(blk)
	if err != nil {
		return err
	}

	// Each direction numbers its messages from 0; the first message in each
	// direction therefore uses an all-zero nonce.
	nonce := make([]byte, gcm.NonceSize())
	info := make([]byte, peerInfoSize)
	info[0] = 0 // ADB_RSA_PUB_KEY
	copy(info[1:peerInfoSize-1], adbPublicKey(&k.PublicKey))
	if err := writePairing(c, pairingPeerInfo, gcm.Seal(nil, nonce, info, nil)); err != nil {
		return err
	}
	typ, sealed, err := readPairing(c)
	if err != nil {
		// adbd closes the connection when it cannot decrypt the peer info.
		return fmt.Errorf("pairing rejected - check the code")
	}
	if typ != pairingPeerInfo {
		return fmt.Errorf("pairing: unexpected packet type %d", typ)
	}
	if _, err := gcm.Open(nil, nonce, sealed, nil); err != nil {
		return fmt.Errorf("pairing rejected - check the code")
	}
	return nil
}

func writePairing(w io.Writer, typ byte, payload []byte) error {
	b := make([]byte, 6, 6+len(payload))
	b[0], b[1] = pairingPacketVersion, typ
	binary.BigEndian.PutUint32(b[2:], uint32(len(payload)))
	_, err := w.Write(append(b, payload...))
	return err
}

func readPairing(r io.Reader) (byte, []byte, error) {
	var h [6]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return 0, nil, err
	}
	if h[0] != pairingPacketVersion {
		return 0, nil, fmt.Errorf("pairing: unsupported version %d", h[0])
	}
	n := binary.BigEndian.Uint32(h[2:])
	if n > 2*peerInfoSize {
		return 0, nil, fmt.Errorf("pairing: packet too large")
	}
	p := make([]byte, n)
	if _, err := io.ReadFull(r, p); err != nil {
		return 0, nil, err
	}
	return h[1], p, nil
}

// ---- ADB transport -----------------------------------------------------------

const (
	aCNXN = 0x4e584e43
	aOPEN = 0x4e45504f
	aOKAY = 0x59414b4f
	aCLSE = 0x45534c43
	aWRTE = 0x45545257
	aAUTH = 0x48545541
	aSTLS = 0x534c5453

	adbVersion  = 0x01000001
	stlsVersion = 0x01000000
	maxPayload  = 256 * 1024

	authToken        = 1
	authSignature    = 2
	authRSAPublicKey = 3
)

var errNotAuthorized = errors.New("adbd rejected this app's key")

type adbConn struct {
	c net.Conn
	r *bufio.Reader
}

type adbMsg struct {
	cmd, arg0, arg1 uint32
	data            []byte
}

func (a *adbConn) send(cmd, arg0, arg1 uint32, data []byte) error {
	b := make([]byte, 24+len(data))
	var sum uint32
	for _, x := range data {
		sum += uint32(x)
	}
	binary.LittleEndian.PutUint32(b[0:], cmd)
	binary.LittleEndian.PutUint32(b[4:], arg0)
	binary.LittleEndian.PutUint32(b[8:], arg1)
	binary.LittleEndian.PutUint32(b[12:], uint32(len(data)))
	binary.LittleEndian.PutUint32(b[16:], sum)
	binary.LittleEndian.PutUint32(b[20:], cmd^0xffffffff)
	copy(b[24:], data)
	_, err := a.c.Write(b)
	return err
}

func (a *adbConn) recv() (adbMsg, error) {
	var h [24]byte
	if _, err := io.ReadFull(a.r, h[:]); err != nil {
		return adbMsg{}, err
	}
	m := adbMsg{
		cmd:  binary.LittleEndian.Uint32(h[0:]),
		arg0: binary.LittleEndian.Uint32(h[4:]),
		arg1: binary.LittleEndian.Uint32(h[8:]),
	}
	if binary.LittleEndian.Uint32(h[20:]) != m.cmd^0xffffffff {
		return adbMsg{}, fmt.Errorf("adb: bad message header")
	}
	n := binary.LittleEndian.Uint32(h[12:])
	if n > 1<<20 {
		return adbMsg{}, fmt.Errorf("adb: message too large")
	}
	m.data = make([]byte, n)
	if _, err := io.ReadFull(a.r, m.data); err != nil {
		return adbMsg{}, err
	}
	return m, nil
}

// adbConnect opens an authenticated ADB connection. On the Wireless debugging
// port adbd upgrades the connection to TLS and checks the key in the client
// certificate. On a classic TCP port it sends an RSA challenge instead; if
// askUser is set and the key is not trusted, the client then offers its
// public key, and adbd asks the user to allow it.
func adbConnect(host string, port int, k *rsa.PrivateKey, timeout time.Duration, askUser bool) (*adbConn, error) {
	raw, err := net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(port)), 5*time.Second)
	if err != nil {
		return nil, err
	}
	raw.SetDeadline(time.Now().Add(timeout))
	a := &adbConn{c: raw, r: bufio.NewReader(raw)}
	fail := func(err error) (*adbConn, error) {
		raw.Close()
		return nil, err
	}
	if err := a.send(aCNXN, adbVersion, maxPayload, []byte("host::features=cmd\x00")); err != nil {
		return fail(err)
	}
	signed, offered := false, false
	for {
		m, err := a.recv()
		if err != nil {
			return fail(err)
		}
		switch m.cmd {
		case aCNXN:
			return a, nil
		case aSTLS:
			if err := a.send(aSTLS, stlsVersion, 0, nil); err != nil {
				return fail(err)
			}
			if a.r.Buffered() > 0 {
				return fail(fmt.Errorf("adb: unexpected data before TLS"))
			}
			cfg, err := tlsConfig(k)
			if err != nil {
				return fail(err)
			}
			tc := tls.Client(raw, cfg)
			if err := tc.Handshake(); err != nil {
				return fail(fmt.Errorf("%w (%v)", errNotAuthorized, err))
			}
			a.c, a.r = tc, bufio.NewReader(tc)
		case aAUTH:
			if m.arg0 != authToken {
				return fail(errNotAuthorized)
			}
			switch {
			case !signed:
				// adb signs the 20-byte token as if it were a SHA-1 digest.
				sig, err := rsa.SignPKCS1v15(nil, k, crypto.SHA1, m.data)
				if err != nil {
					return fail(err)
				}
				if err := a.send(aAUTH, authSignature, 0, sig); err != nil {
					return fail(err)
				}
				signed = true
			case askUser && !offered:
				// adbd shows "Allow USB debugging?" and sends CNXN once
				// the user allows the key.
				pub := append([]byte(adbPublicKey(&k.PublicKey)), 0)
				if err := a.send(aAUTH, authRSAPublicKey, 0, pub); err != nil {
					return fail(err)
				}
				offered = true
			default:
				return fail(errNotAuthorized)
			}
		}
	}
}

// run opens one service stream and returns everything it writes until it is
// closed.
func (a *adbConn) run(service string) (string, error) {
	const local = 1
	if err := a.send(aOPEN, local, 0, append([]byte(service), 0)); err != nil {
		return "", err
	}
	var out bytes.Buffer
	opened := false
	for {
		m, err := a.recv()
		if err != nil {
			// tcpip: and usb: restart adbd right after replying.
			if opened && out.Len() > 0 {
				return out.String(), nil
			}
			return out.String(), err
		}
		switch m.cmd {
		case aOKAY:
			opened = true
		case aWRTE:
			out.Write(m.data)
			if err := a.send(aOKAY, local, m.arg0, nil); err != nil {
				return out.String(), err
			}
		case aCLSE:
			if !opened {
				return "", fmt.Errorf("adbd refused %q", service)
			}
			a.send(aCLSE, local, m.arg0, nil)
			return out.String(), nil
		}
	}
}

// AdbExec connects to adbd at host:port with the app's key, runs one service
// such as "tcpip:5555", "usb:" or "shell:<command>", and returns its output.
func AdbExec(keyDir, host string, port int, service string, timeoutSec int) (out string, err error) {
	defer recoverTo(&err)
	k, err := loadKey(keyDir)
	if err != nil {
		return "", err
	}
	a, err := adbConnect(host, port, k, time.Duration(timeoutSec)*time.Second, false)
	if err != nil {
		return "", err
	}
	defer a.c.Close()
	return a.run(service)
}

// AdbAuthorize connects to adbd's classic TCP port at host:port. If the app's
// key is not trusted yet, adbd shows the "Allow USB debugging?" prompt for it.
// Returns once the key is accepted, or fails after timeoutSec. A key allowed
// with "Always allow" is also accepted by Wireless debugging.
func AdbAuthorize(keyDir, host string, port int, timeoutSec int) (err error) {
	defer recoverTo(&err)
	k, err := loadKey(keyDir)
	if err != nil {
		return err
	}
	a, err := adbConnect(host, port, k, time.Duration(timeoutSec)*time.Second, true)
	if err != nil {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return fmt.Errorf("not allowed on the phone within %ds", timeoutSec)
		}
		return err
	}
	a.c.Close()
	return nil
}

// recoverTo converts a panic into an error, so that it does not cross JNI.
func recoverTo(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("panic: %v", r)
	}
}
