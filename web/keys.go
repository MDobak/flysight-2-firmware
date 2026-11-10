package main

import (
	"bufio"
	"bytes"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// The X value of the B1 devices. Their public key is not published.
const b1PubkeyX = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

type batchKey struct {
	name string // for example B7
	pub  []byte // uncompressed, 65 bytes
}

// loadKeys reads Deploy/Public_Keys/pub_key_NAME.bin. The map key is Pubkey_X in hex.
func loadKeys(dir string) (map[string]batchKey, error) {
	files, err := filepath.Glob(filepath.Join(dir, "pub_key_*.bin"))
	if err != nil {
		return nil, err
	}
	sort.Strings(files)
	keys := map[string]batchKey{}
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		if _, err := decodePoint(data); err != nil {
			return nil, fmt.Errorf("%s: %w", f, err)
		}
		name := strings.ToUpper(strings.TrimSuffix(strings.TrimPrefix(filepath.Base(f), "pub_key_"), ".bin"))
		keys[hex.EncodeToString(data[1:33])] = batchKey{name, data}
	}
	if len(keys) == 0 {
		return nil, fmt.Errorf("no pub_key_*.bin in %s", dir)
	}
	return keys, nil
}

var pubkeyXLine = regexp.MustCompile(`^\s*Pubkey_X\s*:\s*([0-9a-fA-F]{64})`)

var errNoPubkey = errors.New("no Pubkey_X line")

// pubkeyX finds the Pubkey_X line of FLYSIGHT.TXT. The function reads
// nothing else from the file.
func pubkeyX(flysightTxt []byte) (string, error) {
	s := bufio.NewScanner(bytes.NewReader(flysightTxt))
	for s.Scan() {
		if m := pubkeyXLine.FindStringSubmatch(s.Text()); m != nil {
			return strings.ToLower(m[1]), nil
		}
	}
	return "", errNoPubkey
}
