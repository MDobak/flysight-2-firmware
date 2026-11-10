package main

// The curve secp256k1, for the ECDH of the firmware encryption. The Go
// standard library does not have this curve. This code is small and slow,
// and it is not constant time. That is not a problem here: the firmware is
// public, and each file uses a new random key.

import (
	"errors"
	"math/big"
)

var (
	curveP  = hexInt("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f")
	curveN  = hexInt("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")
	curveGx = hexInt("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798")
	curveGy = hexInt("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8")
)

func hexInt(s string) *big.Int {
	v, ok := new(big.Int).SetString(s, 16)
	if !ok {
		panic("bad constant " + s)
	}
	return v
}

// point is an affine point. nil is the point at infinity.
type point struct{ x, y *big.Int }

func onCurve(p *point) bool {
	if p == nil || p.x.Sign() < 0 || p.x.Cmp(curveP) >= 0 || p.y.Sign() < 0 || p.y.Cmp(curveP) >= 0 {
		return false
	}
	// y^2 = x^3 + 7
	l := new(big.Int).Mul(p.y, p.y)
	r := new(big.Int).Mul(p.x, p.x)
	r.Mul(r, p.x).Add(r, big.NewInt(7))
	return l.Mod(l, curveP).Cmp(r.Mod(r, curveP)) == 0
}

func add(a, b *point) *point {
	if a == nil {
		return b
	}
	if b == nil {
		return a
	}
	var s *big.Int
	if a.x.Cmp(b.x) == 0 {
		if new(big.Int).Add(a.y, b.y).Mod(new(big.Int).Add(a.y, b.y), curveP).Sign() == 0 {
			return nil // a = -b
		}
		// s = 3x^2 / 2y
		num := new(big.Int).Mul(a.x, a.x)
		num.Mul(num, big.NewInt(3))
		den := new(big.Int).Lsh(a.y, 1)
		s = num.Mul(num, den.ModInverse(den.Mod(den, curveP), curveP))
	} else {
		// s = (yb - ya) / (xb - xa)
		num := new(big.Int).Sub(b.y, a.y)
		den := new(big.Int).Sub(b.x, a.x)
		s = num.Mul(num, den.ModInverse(den.Mod(den, curveP), curveP))
	}
	s.Mod(s, curveP)
	x := new(big.Int).Mul(s, s)
	x.Sub(x, a.x).Sub(x, b.x).Mod(x, curveP)
	y := new(big.Int).Sub(a.x, x)
	y.Mul(y, s).Sub(y, a.y).Mod(y, curveP)
	return &point{x, y}
}

func mul(p *point, k *big.Int) *point {
	var r *point
	for i := k.BitLen() - 1; i >= 0; i-- {
		r = add(r, r)
		if k.Bit(i) == 1 {
			r = add(r, p)
		}
	}
	return r
}

func baseMul(k *big.Int) *point { return mul(&point{curveGx, curveGy}, k) }

// encode returns the uncompressed SEC1 form: 0x04, X (32 bytes), Y (32 bytes).
func (p *point) encode() []byte {
	out := make([]byte, 65)
	out[0] = 4
	p.x.FillBytes(out[1:33])
	p.y.FillBytes(out[33:65])
	return out
}

func decodePoint(b []byte) (*point, error) {
	if len(b) != 65 || b[0] != 4 {
		return nil, errors.New("not an uncompressed public key")
	}
	p := &point{new(big.Int).SetBytes(b[1:33]), new(big.Int).SetBytes(b[33:65])}
	if !onCurve(p) {
		return nil, errors.New("the public key is not on secp256k1")
	}
	return p, nil
}
