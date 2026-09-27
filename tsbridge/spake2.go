package tsbridge

import (
	"crypto/rand"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"math/big"

	"filippo.io/edwards25519"
)

// SPAKE2 over edwards25519, compatible with BoringSSL's SPAKE2_* functions,
// which adbd uses for Wireless debugging pairing. Only the client (Alice)
// side is implemented.

// M and N are BoringSSL's fixed points: the first valid point encodings found
// by repeatedly hashing "edwards25519 point generation seed (M)" and "(N)"
// with SHA-256.
var (
	spakeM = mustPoint("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")
	spakeN = mustPoint("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")

	// Order of the prime-order subgroup: 2^252 + 27742317777372353535851937790883648493.
	curveOrder, _ = new(big.Int).SetString("7237005577332262213973186563042994240857116359379907606001950938285454250989", 10)
)

func mustPoint(h string) *edwards25519.Point {
	b, err := hex.DecodeString(h)
	if err != nil {
		panic(err)
	}
	p, err := new(edwards25519.Point).SetBytes(b)
	if err != nil {
		panic(err)
	}
	return p
}

type spake2 struct {
	myName, theirName []byte
	x                 *edwards25519.Scalar
	pw                *big.Int // password scalar
	pwHash            [64]byte
	msg               [32]byte
}

// newSpake2 returns the client state and its first message.
func newSpake2(myName, theirName, password []byte) (*spake2, error) {
	var seed [64]byte
	if _, err := rand.Read(seed[:]); err != nil {
		return nil, err
	}
	x, err := edwards25519.NewScalar().SetUniformBytes(seed[:])
	if err != nil {
		return nil, err
	}
	s := &spake2{myName: myName, theirName: theirName, x: x}

	s.pwHash = sha512.Sum512(password)
	pws, err := edwards25519.NewScalar().SetUniformBytes(s.pwHash[:])
	if err != nil {
		return nil, err
	}
	s.pw = scalarInt(pws)
	// BoringSSL adds small multiples of the group order so that the low three
	// bits of the password scalar are zero. The result is unchanged for points
	// of prime order; it is reproduced exactly here.
	for i := 0; i < 3; i++ {
		if s.pw.Bit(i) == 1 {
			s.pw.Add(s.pw, new(big.Int).Lsh(curveOrder, uint(i)))
		}
	}

	// The private key is 8x, so that the cofactor is cleared later.
	p := new(edwards25519.Point).ScalarBaseMult(x)
	p.MultByCofactor(p)
	p.Add(p, mulInt(spakeM, s.pw))
	copy(s.msg[:], p.Bytes())
	return s, nil
}

// finish processes the server's message and returns the 64-byte shared key.
func (s *spake2) finish(theirMsg []byte) ([]byte, error) {
	if len(theirMsg) != 32 {
		return nil, fmt.Errorf("spake2: bad message length %d", len(theirMsg))
	}
	q, err := new(edwards25519.Point).SetBytes(theirMsg)
	if err != nil {
		return nil, fmt.Errorf("spake2: invalid point")
	}
	q.Subtract(q, mulInt(spakeN, s.pw))
	dh := new(edwards25519.Point).ScalarMult(s.x, q)
	dh.MultByCofactor(dh)

	h := sha512.New()
	put := func(b []byte) {
		var n [8]byte
		binary.LittleEndian.PutUint64(n[:], uint64(len(b)))
		h.Write(n[:])
		h.Write(b)
	}
	put(s.myName)
	put(s.theirName)
	put(s.msg[:])
	put(theirMsg)
	put(dh.Bytes())
	put(s.pwHash[:])
	return h.Sum(nil), nil
}

// scalarInt converts a scalar to an integer.
func scalarInt(sc *edwards25519.Scalar) *big.Int {
	le := sc.Bytes()
	be := make([]byte, len(le))
	for i, b := range le {
		be[len(le)-1-i] = b
	}
	return new(big.Int).SetBytes(be)
}

// mulInt returns k*p for an arbitrary non-negative integer k. It is not
// constant time, which is acceptable for a pairing that runs once, over
// loopback.
func mulInt(p *edwards25519.Point, k *big.Int) *edwards25519.Point {
	r := edwards25519.NewIdentityPoint()
	for i := k.BitLen() - 1; i >= 0; i-- {
		r.Add(r, r)
		if k.Bit(i) == 1 {
			r.Add(r, p)
		}
	}
	return r
}
