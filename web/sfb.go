package main

// APP.SFB, the encrypted firmware file of FlySight 2. This is a port of
// Deploy/deploy_firmware.py and Deploy/ecdh_aesgcm.py. Keep them the same.

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/sha512"
	"encoding/binary"
	"fmt"
	"io"
	"math/big"
)

const sfbHeaderSize = 512

// makeSFB encrypts the firmware for the device public key (65 bytes,
// uncompressed). It reads the ephemeral key and the nonce from random.
func makeSFB(firmware, devicePub []byte, random io.Reader) ([]byte, error) {
	pub, err := decodePoint(devicePub)
	if err != nil {
		return nil, err
	}
	// The ephemeral secret is in the range 1 .. n-1.
	secret, err := randScalar(random)
	if err != nil {
		return nil, err
	}
	nonce := make([]byte, 12)
	if _, err := io.ReadFull(random, nonce); err != nil {
		return nil, err
	}

	ephPub := baseMul(secret).encode()
	shared := mul(pub, secret)
	if shared == nil {
		return nil, fmt.Errorf("bad shared point")
	}
	// HKDF-SHA512 with no salt and no info. The input is the ephemeral
	// public key and the shared point, both uncompressed.
	key, err := hkdf.Key(sha512.New, append(ephPub, shared.encode()...), nil, "", 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	sealed := gcm.Seal(nil, nonce, firmware, nil)
	encrypted, tag := sealed[:len(firmware)], sealed[len(firmware):]

	// The header: "SFU1", then struct.pack("HHIII", 1, 1, size, 0, size).
	h := make([]byte, 0, sfbHeaderSize)
	h = append(h, "SFU1"...)
	h = binary.LittleEndian.AppendUint16(h, 1)
	h = binary.LittleEndian.AppendUint16(h, 1)
	h = binary.LittleEndian.AppendUint32(h, uint32(len(firmware)))
	h = binary.LittleEndian.AppendUint32(h, 0)
	h = binary.LittleEndian.AppendUint32(h, uint32(len(firmware)))
	h = append(h, tag...)
	h = append(h, tag...)
	h = append(h, nonce...)
	h = append(h, ephPub[1:]...)
	h = append(h, make([]byte, 48)...)
	// The GMAC of the header: the same key and nonce, no data, the header as AAD.
	h = append(h, gcm.Seal(nil, nonce, nil, h)...)
	h = append(h, fill(0xff, 32+64)...)
	h = append(h, fill(0x00, 32)...)
	h = append(h, fill(0xff, 192)...)
	if len(h) != sfbHeaderSize {
		return nil, fmt.Errorf("header size %d", len(h))
	}
	return append(h, encrypted...), nil
}

func randScalar(random io.Reader) (*big.Int, error) {
	// Same as get_valid_secret: read 32 bytes until the value is in range.
	b := make([]byte, 32)
	for {
		if _, err := io.ReadFull(random, b); err != nil {
			return nil, err
		}
		k := new(big.Int).SetBytes(b)
		if k.Sign() > 0 && k.Cmp(curveN) < 0 {
			return k, nil
		}
	}
}

func fill(v byte, n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = v
	}
	return b
}
