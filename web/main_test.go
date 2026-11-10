package main

import (
	"archive/zip"
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/sha512"
	"encoding/binary"
	"encoding/hex"
	"math/big"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

const keysDir = "../Deploy/Public_Keys"

func testFirmware() []byte {
	fw := make([]byte, 1000)
	for i := range fw {
		fw[i] = byte(i % 251)
	}
	return fw
}

// The same file as Deploy/deploy_firmware.py, with os.urandom set to
// 32 bytes of 0x11 (the ephemeral key), then 12 bytes of 0x22 (the nonce).
func TestMakeSFBMatchesPython(t *testing.T) {
	pub, err := os.ReadFile(filepath.Join(keysDir, "pub_key_b7.bin"))
	if err != nil {
		t.Fatal(err)
	}
	random := bytes.NewReader(append(bytes.Repeat([]byte{0x11}, 32), bytes.Repeat([]byte{0x22}, 12)...))
	sfb, err := makeSFB(testFirmware(), pub, random)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(sfb)
	const want = "fc6e714549dfca4d156bccd66fa2d45f9b50ce3fbebd1dbbc6af9e9e4cfd010f"
	if got := hex.EncodeToString(sum[:]); got != want {
		t.Fatalf("sha256 %s, want %s", got, want)
	}
}

// Decrypt with the private key, as the bootloader does.
func TestMakeSFBRoundTrip(t *testing.T) {
	d, err := randScalar(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	fw := testFirmware()
	sfb, err := makeSFB(fw, baseMul(d).encode(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	h := sfb[:sfbHeaderSize]
	if string(h[:4]) != "SFU1" || binary.LittleEndian.Uint32(h[8:]) != uint32(len(fw)) || len(sfb) != sfbHeaderSize+len(fw) {
		t.Fatal("bad header")
	}
	tag, nonce := h[20:36], h[52:64]
	eph, err := decodePoint(append([]byte{4}, h[64:128]...))
	if err != nil {
		t.Fatal(err)
	}
	key, err := hkdf.Key(sha512.New, append(eph.encode(), mul(eph, d).encode()...), nil, "", 32)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	plain, err := gcm.Open(nil, nonce, append(append([]byte{}, sfb[sfbHeaderSize:]...), tag...), nil)
	if err != nil || !bytes.Equal(plain, fw) {
		t.Fatalf("firmware does not decrypt: %v", err)
	}
	if _, err := gcm.Open(nil, nonce, h[176:192], h[:176]); err != nil {
		t.Fatalf("header GMAC: %v", err)
	}
}

func TestCurve(t *testing.T) {
	if !onCurve(baseMul(big.NewInt(1))) {
		t.Fatal("G is not on the curve")
	}
	if baseMul(curveN) != nil {
		t.Fatal("n*G is not infinity")
	}
	a, b := big.NewInt(12345), big.NewInt(67890)
	if !bytes.Equal(mul(baseMul(a), b).encode(), mul(baseMul(b), a).encode()) {
		t.Fatal("ECDH does not agree")
	}
}

func TestLoadKeys(t *testing.T) {
	keys, err := loadKeys(keysDir)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	for _, k := range keys {
		names[k.name] = true
	}
	for _, n := range []string{"B2", "B7", "DEV"} {
		if !names[n] {
			t.Errorf("no batch %s", n)
		}
	}
}

func TestPubkeyX(t *testing.T) {
	x := strings.Repeat("aB", 32)
	got, err := pubkeyX([]byte("FlySight 2\r\nFirmware_Ver: v1\r\n  Pubkey_X: " + x + "\r\nDevice_ID: 1\r\n"))
	if err != nil || got != strings.ToLower(x) {
		t.Fatalf("got %q, %v", got, err)
	}
	if _, err := pubkeyX([]byte("Firmware_Ver: v1\n")); err != errNoPubkey {
		t.Fatalf("got %v", err)
	}
}

func testServer(t *testing.T) *server {
	keys, err := loadKeys(keysDir)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	for _, n := range []string{"ALT.WAV", "1.wav", "ALT.txt"} {
		os.WriteFile(filepath.Join(dir, n), []byte(n), 0o644)
	}
	audio, n, err := makeAudioZip(dir)
	if err != nil {
		t.Fatal(err)
	}
	return &server{keys: keys, firmwares: []firmware{{"", testFirmware()}}, audioZip: audio, audioN: n}
}

func upload(t *testing.T, s *server, text string) *httptest.ResponseRecorder {
	var body bytes.Buffer
	m := multipart.NewWriter(&body)
	f, _ := m.CreateFormFile("flysight_file", "FLYSIGHT.TXT")
	f.Write([]byte(text))
	m.Close()
	r := httptest.NewRequest(http.MethodPost, "/firmware", &body)
	r.Header.Set("Content-Type", m.FormDataContentType())
	w := httptest.NewRecorder()
	s.firmware(w, r)
	return w
}

func TestFirmwareHandler(t *testing.T) {
	s := testServer(t)
	pub, _ := os.ReadFile(filepath.Join(keysDir, "pub_key_b7.bin"))
	w := upload(t, s, "Pubkey_X: "+hex.EncodeToString(pub[1:33])+"\n")
	if w.Code != http.StatusOK || !strings.HasPrefix(w.Body.String(), "SFU1") ||
		!strings.Contains(w.Header().Get("Content-Disposition"), "APP.SFB") {
		t.Fatalf("B7: %d %q", w.Code, w.Header())
	}
	if w := upload(t, s, "Pubkey_X: "+b1PubkeyX+"\n"); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "B1") {
		t.Fatalf("B1: %d", w.Code)
	}
	if w := upload(t, s, "Pubkey_X: "+strings.Repeat("12", 32)+"\n"); w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("unknown batch: %d", w.Code)
	}
	if w := upload(t, s, "nothing\n"); w.Code != http.StatusBadRequest || !strings.Contains(w.Body.String(), "Pubkey_X") {
		t.Fatalf("no key: %d", w.Code)
	}
}

func TestIndexAndAudio(t *testing.T) {
	s := testServer(t)
	w := httptest.NewRecorder()
	s.index(w, httptest.NewRequest(http.MethodGet, "/", nil))
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "flysight.ca/firmware") {
		t.Fatalf("index: %d", w.Code)
	}
	w = httptest.NewRecorder()
	s.audio(w, httptest.NewRequest(http.MethodGet, "/audio.zip", nil))
	z, err := zip.NewReader(bytes.NewReader(w.Body.Bytes()), int64(w.Body.Len()))
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, f := range z.File {
		names = append(names, f.Name)
	}
	if got := strings.Join(names, " "); got != "AUDIO/ AUDIO/1.wav AUDIO/ALT.WAV" {
		t.Fatalf("zip: %s", got)
	}
}

func TestApp(t *testing.T) {
	s := testServer(t)
	get := func(f http.HandlerFunc, url string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		f(w, httptest.NewRequest(http.MethodGet, url, nil))
		return w
	}
	if w := get(s.index, "/"); strings.Contains(w.Body.String(), "flysight.apk") {
		t.Fatal("index has the app download without an APK")
	}
	if w := get(s.app, "/flysight.apk"); w.Code != http.StatusNotFound {
		t.Fatalf("no APK: %d", w.Code)
	}
	s.apk = bytes.Repeat([]byte("PK"), 1_500_000)
	if w := get(s.index, "/"); !strings.Contains(w.Body.String(), "Download the app (3.0 MB)") {
		t.Fatal("index has no app download")
	}
	w := get(s.app, "/flysight.apk")
	if w.Code != http.StatusOK || w.Body.Len() != len(s.apk) ||
		w.Header().Get("Content-Type") != "application/vnd.android.package-archive" {
		t.Fatalf("APK: %d %q", w.Code, w.Header())
	}
}
