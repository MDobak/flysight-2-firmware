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
	"io"
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
	return &server{keys: keys, firmwares: []firmware{{"", testFirmware()}}, voices: []voice{{"voice1", "Voice 1", audio, n, []byte("s1")}}}
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

func TestVoices(t *testing.T) {
	s := testServer(t)
	get := func(f http.HandlerFunc, url string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		f(w, httptest.NewRequest(http.MethodGet, url, nil))
		return w
	}
	if w := get(s.index, "/"); strings.Contains(w.Body.String(), `name="voice"`) {
		t.Fatal("index has a voice choice with one voice")
	}
	s.voices = append(s.voices, voice{"voice2", "Voice 2", []byte("v2"), 1, []byte("s2")})
	body := get(s.index, "/").Body.String()
	for _, want := range []string{
		`<input type="radio" name="voice" value="voice1" checked> Voice 1`,
		`<input type="radio" name="voice" value="voice2"> Voice 2`,
		`data-sample="/sample.wav?voice=voice2"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("index has no %s", want)
		}
	}
	for url, want := range map[string]string{
		"/audio.zip":              "voice1",
		"/audio.zip?voice=voice1": "voice1",
		"/audio.zip?voice=voice2": "voice2",
	} {
		w := get(s.audio, url)
		if w.Code != http.StatusOK || !strings.Contains(w.Header().Get("Content-Disposition"), "flysight-2-audio-"+want+".zip") {
			t.Errorf("%s: %d %q", url, w.Code, w.Header())
		}
	}
	if w := get(s.audio, "/audio.zip?voice=x"); w.Code != http.StatusFound {
		t.Errorf("unknown voice: %d", w.Code)
	}
	for url, want := range map[string]string{
		"/sample.wav":              "s1",
		"/sample.wav?voice=voice2": "s2",
	} {
		w := get(s.sample, url)
		if w.Code != http.StatusOK || w.Body.String() != want || w.Header().Get("Content-Type") != "audio/wav" {
			t.Errorf("%s: %d %q", url, w.Code, w.Body.String())
		}
	}
	if w := get(s.sample, "/sample.wav?voice=x"); w.Code != http.StatusFound {
		t.Errorf("unknown voice sample: %d", w.Code)
	}
}

// testWAV is a plain WAV file with a 44-byte header: 24 kHz, mono, 16-bit.
func testWAV(data []byte) []byte {
	out := []byte("RIFF")
	out = binary.LittleEndian.AppendUint32(out, uint32(36+len(data)))
	out = append(out, "WAVEfmt "...)
	out = binary.LittleEndian.AppendUint32(out, 16)
	out = binary.LittleEndian.AppendUint16(out, 1)
	out = binary.LittleEndian.AppendUint16(out, 1)
	out = binary.LittleEndian.AppendUint32(out, 24000)
	out = binary.LittleEndian.AppendUint32(out, 48000)
	out = binary.LittleEndian.AppendUint16(out, 2)
	out = binary.LittleEndian.AppendUint16(out, 16)
	out = append(out, "data"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(len(data)))
	return append(out, data...)
}

func TestMakeSample(t *testing.T) {
	dir := t.TempDir()
	words := 0
	for _, call := range sampleCalls {
		for _, name := range call {
			os.WriteFile(filepath.Join(dir, name+".WAV"), testWAV([]byte{1, 2}), 0o644)
			words++
		}
	}
	wav, err := makeSample(dir)
	if err != nil {
		t.Fatal(err)
	}
	gap := int(48000 * sampleGap.Seconds())
	n := 2*words + gap*(len(sampleCalls)-1)
	if want := testWAV(make([]byte, n)); len(wav) != len(want) || !bytes.Equal(wav[:44], want[:44]) {
		t.Fatalf("got %d bytes, header % x; want %d bytes, header % x", len(wav), wav[:44], len(want), want[:44])
	}
	if !bytes.Equal(wav[44:48], []byte{1, 2, 1, 2}) {
		t.Fatalf("data starts with % x", wav[44:48])
	}

	os.WriteFile(filepath.Join(dir, "ALT.WAV"), []byte("text"), 0o644)
	if _, err := makeSample(dir); err == nil {
		t.Fatal("no error for a file that is not a WAV file")
	}
	os.Remove(filepath.Join(dir, "ALT.WAV"))
	if _, err := makeSample(dir); err == nil {
		t.Fatal("no error for a missing file")
	}
}

// TestSampleOfVoices makes the sample of each voice of the website.
func TestSampleOfVoices(t *testing.T) {
	for _, v := range []string{"voice1", "voice1-1.25", "voice2", "voice2-1.25", "voice3", "voice3-1.25"} {
		if _, err := makeSample(filepath.Join("..", "Audio", v)); err != nil {
			t.Error(err)
		}
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
	if w := get(s.app, "/flysight.apk"); w.Code != http.StatusFound {
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

func TestSampleJump(t *testing.T) {
	dir := t.TempDir()
	for _, f := range []string{"24-10-11/18-33-52/TRACK.CSV", "24-10-11/18-33-52/SOURCE.txt", ".DS_Store", "24-10-11/.DS_Store"} {
		if err := os.MkdirAll(filepath.Join(dir, filepath.Dir(f)), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, f), []byte(f), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	data, err := makeTreeZip(dir)
	if err != nil {
		t.Fatal(err)
	}
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil || len(z.File) != 2 {
		t.Fatalf("zip: %v", err)
	}
	for _, f := range z.File {
		r, _ := f.Open()
		b, _ := io.ReadAll(r)
		if string(b) != f.Name {
			t.Fatalf("%s has %q", f.Name, b)
		}
	}
	if _, err := makeTreeZip(t.TempDir()); err == nil {
		t.Fatal("no error for an empty folder")
	}

	s := testServer(t)
	get := func(f http.HandlerFunc, url string) *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		f(w, httptest.NewRequest(http.MethodGet, url, nil))
		return w
	}
	if w := get(s.index, "/"); strings.Contains(w.Body.String(), "sample-jump.zip") {
		t.Fatal("index has the sample jump without a jump")
	}
	if w := get(s.jump, "/sample-jump.zip"); w.Code != http.StatusFound {
		t.Fatalf("no jump: %d", w.Code)
	}
	s.jumpZip = data
	if w := get(s.index, "/"); !strings.Contains(w.Body.String(), "Download the sample jump (") {
		t.Fatal("index has no sample jump")
	}
	w := get(s.jump, "/sample-jump.zip")
	if w.Code != http.StatusOK || !bytes.Equal(w.Body.Bytes(), data) || w.Header().Get("Content-Type") != "application/zip" {
		t.Fatalf("jump: %d %q", w.Code, w.Header())
	}
}

func TestSampleJumpOfRepository(t *testing.T) {
	data, err := makeTreeZip("../Samples/wingsuit")
	if err != nil || len(data) == 0 {
		t.Fatal(err)
	}
}

func TestNotFoundRedirect(t *testing.T) {
	h := testServer(t).routes()
	for _, c := range []struct{ method, url string }{
		{"GET", "/x"}, {"GET", "/index.html"}, {"GET", "/audio.zip/x"}, {"GET", "/firmware"},
		{"POST", "/x"}, {"GET", "/audio.zip?voice=x"}, {"GET", "/flysight.apk"}, {"GET", "/sample-jump.zip"},
	} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest(c.method, c.url, nil))
		if w.Code != http.StatusFound || w.Header().Get("Location") != "/" {
			t.Fatalf("%s %s: %d %q", c.method, c.url, w.Code, w.Header().Get("Location"))
		}
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, httptest.NewRequest("GET", "/", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("home page: %d", w.Code)
	}
}
