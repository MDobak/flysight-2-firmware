// A small website that makes APP.SFB for the FlySight 2 of the user, and
// gives the audio files of this firmware and the Android app.
//
// The user uploads FLYSIGHT.TXT. The server reads only the Pubkey_X line,
// finds the hardware batch, and encrypts UserApp.bin for that batch. It does
// not keep the uploaded file.
package main

import (
	"archive/zip"
	"bytes"
	"crypto/rand"
	"embed"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"html/template"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
	"time"
)

//go:embed index.html
var files embed.FS

var page = template.Must(template.ParseFS(files, "index.html"))

const maxUpload = 64 << 10

// buildVersion is the default of -version. The Docker build sets it with
// -ldflags "-X main.buildVersion=...".
var buildVersion string

type firmware struct {
	Name string
	data []byte
}

// voice is a set of sound files. ID is the folder name, and the value of
// ?voice= in /audio.zip and /sample.wav.
type voice struct {
	ID, Label string
	zip       []byte
	n         int
	sample    []byte // a WAV file with some calls of the FlySight
}

type server struct {
	keys      map[string]batchKey
	firmwares []firmware
	voices    []voice
	apk       []byte
	jumpZip   []byte // the sample jump, or nil
	version   string
}

// listFlag is a flag that you can use more than once (-firmware, -audio).
type listFlag []string

func (f *listFlag) String() string     { return strings.Join(*f, ",") }
func (f *listFlag) Set(v string) error { *f = append(*f, v); return nil }

func main() {
	var fwFlags, audioFlags listFlag
	addr := flag.String("addr", "localhost:8080", "listen address")
	keysDir := flag.String("keys", "../Deploy/Public_Keys", "folder with pub_key_*.bin")
	version := flag.String("version", buildVersion, "firmware version to show on the page")
	apkFile := flag.String("apk", "", "APK of the Android app. Empty: the page has no app download")
	jumpDir := flag.String("jump", "../Samples/wingsuit", "folder with a sample jump (log folders of the FlySight). Empty: the page has no sample jump")
	flag.Var(&fwFlags, "firmware", "UserApp.bin as PATH or NAME=PATH. Use more than once for more firmware choices (default ../Release/UserApp.bin)")
	flag.Var(&audioFlags, "audio", "folder with the WAV files of a voice, as DIR or LABEL=DIR. Use more than once for more voices. The first is the default (default ../Audio/voice1)")
	flag.Parse()
	if len(fwFlags) == 0 {
		fwFlags = listFlag{"../Release/UserApp.bin"}
	}
	if len(audioFlags) == 0 {
		audioFlags = listFlag{"../Audio/voice1"}
	}

	s := &server{version: *version}
	var err error
	if s.keys, err = loadKeys(*keysDir); err != nil {
		log.Fatal(err)
	}
	for _, v := range fwFlags {
		name, file, ok := strings.Cut(v, "=")
		if !ok {
			name, file = "", v
		}
		data, err := os.ReadFile(file)
		if err != nil {
			log.Fatal(err)
		}
		if len(data) == 0 {
			log.Fatalf("%s is empty", file)
		}
		s.firmwares = append(s.firmwares, firmware{name, data})
		log.Printf("firmware %q: %s, %d bytes", name, file, len(data))
	}
	for _, v := range audioFlags {
		label, dir, ok := strings.Cut(v, "=")
		if !ok {
			dir = v
		}
		id := filepath.Base(filepath.Clean(dir))
		if !ok {
			label = id
		}
		data, n, err := makeAudioZip(dir)
		if err != nil {
			log.Fatal(err)
		}
		sample, err := makeSample(dir)
		if err != nil {
			log.Fatal(err)
		}
		s.voices = append(s.voices, voice{id, label, data, n, sample})
		log.Printf("voice %q (%s): %s, %d sound files", label, id, dir, n)
	}
	log.Printf("%d batches", len(s.keys))
	if *apkFile != "" {
		if s.apk, err = os.ReadFile(*apkFile); err != nil {
			log.Fatal(err)
		}
		log.Printf("app: %s, %d bytes", *apkFile, len(s.apk))
	}

	if *jumpDir != "" {
		if s.jumpZip, err = makeTreeZip(*jumpDir); err != nil {
			log.Fatal(err)
		}
		log.Printf("sample jump: %s, %d bytes", *jumpDir, len(s.jumpZip))
	}

	log.Printf("listening on http://%s", *addr)
	srv := &http.Server{Addr: *addr, Handler: s.routes(), ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.index)
	mux.HandleFunc("POST /firmware", s.firmware)
	mux.HandleFunc("GET /audio.zip", s.audio)
	mux.HandleFunc("GET /sample.wav", s.sample)
	mux.HandleFunc("GET /flysight.apk", s.app)
	mux.HandleFunc("GET /sample-jump.zip", s.jump)
	mux.HandleFunc("/", notFound) // all other paths and methods
	return mux
}

type pageData struct {
	Version   string
	Firmwares []firmware
	Voices    []voice
	AudioN    int
	APKSize   string // empty if there is no APK
	JumpSize  string // empty if there is no sample jump
	Error     string
}

func (s *server) render(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	apkSize, jumpSize := "", ""
	if len(s.apk) > 0 {
		apkSize = fmt.Sprintf("%.1f MB", float64(len(s.apk))/1e6)
	}
	if len(s.jumpZip) > 0 {
		jumpSize = fmt.Sprintf("%d KB", (len(s.jumpZip)+999)/1000)
	}
	data := pageData{s.version, s.firmwares, s.voices, s.voices[0].n, apkSize, jumpSize, msg}
	if err := page.Execute(w, data); err != nil {
		log.Print(err)
	}
}

func (s *server) index(w http.ResponseWriter, _ *http.Request) { s.render(w, http.StatusOK, "") }

func (s *server) firmware(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, maxUpload)
	file, _, err := r.FormFile("flysight_file")
	if err != nil {
		s.render(w, http.StatusBadRequest, "Select the FLYSIGHT.TXT file of your FlySight. The maximum size is 64 KB.")
		return
	}
	defer file.Close()
	text, err := io.ReadAll(file)
	if err != nil {
		s.render(w, http.StatusBadRequest, "The upload failed. Try again.")
		return
	}
	fw := s.firmwares[0]
	if len(s.firmwares) > 1 {
		i := -1
		for j, f := range s.firmwares {
			if f.Name == r.FormValue("firmware") {
				i = j
			}
		}
		if i < 0 {
			s.render(w, http.StatusBadRequest, "Select a firmware.")
			return
		}
		fw = s.firmwares[i]
	}

	x, err := pubkeyX(text)
	switch {
	case errors.Is(err, errNoPubkey):
		s.render(w, http.StatusBadRequest, "This file has no Pubkey_X line. Use the FLYSIGHT.TXT file from the FlySight 2. "+
			"If the file is correct, the firmware on the FlySight is older than v2023.09.22. Install the official firmware first.")
		return
	case x == b1PubkeyX:
		s.render(w, http.StatusUnprocessableEntity, "This FlySight is from hardware batch B1. "+
			"The public key of B1 is not published, so a custom firmware is not possible.")
		return
	}
	key, ok := s.keys[x]
	if !ok {
		s.render(w, http.StatusUnprocessableEntity, "The hardware batch of this FlySight is not known. "+
			"The website needs the new public key from the upstream repository.")
		return
	}
	sfb, err := makeSFB(fw.data, key.pub, rand.Reader)
	if err != nil {
		log.Printf("makeSFB %s: %v", key.name, err)
		s.render(w, http.StatusInternalServerError, "The server could not make the firmware file.")
		return
	}
	// Log only the batch, not the file.
	log.Printf("APP.SFB for batch %s, firmware %q", key.name, fw.Name)
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="APP.SFB"`)
	w.Header().Set("Content-Length", fmt.Sprint(len(sfb)))
	w.Header().Set("Cache-Control", "no-store")
	w.Write(sfb)
}

// voice gives the voice of ?voice=ID, or the first voice if there is no ID.
func (s *server) voice(r *http.Request) (voice, bool) {
	id := r.URL.Query().Get("voice")
	if id == "" {
		return s.voices[0], true
	}
	i := slices.IndexFunc(s.voices, func(v voice) bool { return v.ID == id })
	if i < 0 {
		return voice{}, false
	}
	return s.voices[i], true
}

// notFound sends the browser to the home page. The website has no 404 page.
func notFound(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/", http.StatusFound)
}

// audio gives the ZIP file of a voice.
func (s *server) audio(w http.ResponseWriter, r *http.Request) {
	v, ok := s.voice(r)
	if !ok {
		notFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="flysight-2-audio-%s.zip"`, v.ID))
	w.Header().Set("Content-Length", fmt.Sprint(len(v.zip)))
	w.Write(v.zip)
}

// sample gives the sample WAV file of a voice.
func (s *server) sample(w http.ResponseWriter, r *http.Request) {
	v, ok := s.voice(r)
	if !ok {
		notFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Content-Length", fmt.Sprint(len(v.sample)))
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.Write(v.sample)
}

func (s *server) app(w http.ResponseWriter, r *http.Request) {
	if len(s.apk) == 0 {
		notFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="FlySight.apk"`)
	w.Header().Set("Content-Length", fmt.Sprint(len(s.apk)))
	w.Write(s.apk)
}

func (s *server) jump(w http.ResponseWriter, r *http.Request) {
	if len(s.jumpZip) == 0 {
		notFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="flysight-2-sample-jump.zip"`)
	w.Header().Set("Content-Length", fmt.Sprint(len(s.jumpZip)))
	w.Write(s.jumpZip)
}

// makeTreeZip puts all files of dir and its subfolders into a ZIP file, with
// their paths from dir.
func makeTreeZip(dir string) ([]byte, error) {
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	n := 0
	err := filepath.WalkDir(dir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil || rel == "." {
			return err
		}
		if strings.HasPrefix(d.Name(), ".") { // for example .DS_Store
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		f, err := z.CreateHeader(&zip.FileHeader{Name: filepath.ToSlash(rel), Method: zip.Deflate, Modified: info.ModTime()})
		if err != nil {
			return err
		}
		n++
		_, err = f.Write(data)
		return err
	})
	if err != nil {
		return nil, err
	}
	if n == 0 {
		return nil, fmt.Errorf("no files in %s", dir)
	}
	if err := z.Close(); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

// makeAudioZip puts the WAV files of dir into the folder AUDIO of a ZIP file.
func makeAudioZip(dir string) ([]byte, int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, 0, err
	}
	var names []string
	for _, e := range entries {
		if !e.IsDir() && strings.EqualFold(filepath.Ext(e.Name()), ".wav") {
			names = append(names, e.Name())
		}
	}
	if len(names) == 0 {
		return nil, 0, fmt.Errorf("no WAV files in %s", dir)
	}
	sort.Strings(names)
	var buf bytes.Buffer
	z := zip.NewWriter(&buf)
	if _, err := z.Create("AUDIO/"); err != nil {
		return nil, 0, err
	}
	for _, name := range names {
		data, err := os.ReadFile(filepath.Join(dir, name))
		if err != nil {
			return nil, 0, err
		}
		info, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			return nil, 0, err
		}
		f, err := z.CreateHeader(&zip.FileHeader{Name: "AUDIO/" + name, Method: zip.Deflate, Modified: info.ModTime()})
		if err != nil {
			return nil, 0, err
		}
		if _, err := f.Write(data); err != nil {
			return nil, 0, err
		}
	}
	if err := z.Close(); err != nil {
		return nil, 0, err
	}
	return buf.Bytes(), len(names), nil
}

// sampleCalls are the calls of the sample, as the names of the sound files:
// "alti 2500", "H speed 55" and "glide 2.5".
var sampleCalls = [][]string{
	{"ALT", "2", "000", "5", "00"},
	{"HSPEED", "50", "5"},
	{"GLIDE", "2", "DOT", "5"},
}

// sampleGap is the silence between two calls of the sample.
const sampleGap = 600 * time.Millisecond

// makeSample joins the sound files of sampleCalls into one WAV file. The sound
// files must be plain WAV files with a 44-byte header (the format of the
// FlySight), all with the same format.
func makeSample(dir string) ([]byte, error) {
	var format, data []byte
	for i, call := range sampleCalls {
		for _, name := range call {
			file := filepath.Join(dir, name+".WAV")
			wav, err := os.ReadFile(file)
			if err != nil {
				return nil, err
			}
			if len(wav) < 44 || string(wav[0:4]) != "RIFF" || string(wav[8:16]) != "WAVEfmt " || string(wav[36:40]) != "data" {
				return nil, fmt.Errorf("%s: not a WAV file with a 44-byte header", file)
			}
			if format == nil {
				format = wav[20:36]
			} else if !bytes.Equal(format, wav[20:36]) {
				return nil, fmt.Errorf("%s: the format is not the same as in the other files", file)
			}
			data = append(data, wav[44:]...)
		}
		if i < len(sampleCalls)-1 {
			bytesPerSecond := binary.LittleEndian.Uint32(format[8:12])
			blockAlign := int(binary.LittleEndian.Uint16(format[12:14]))
			n := int(float64(bytesPerSecond)*sampleGap.Seconds()) / blockAlign * blockAlign
			data = append(data, make([]byte, n)...)
		}
	}
	out := make([]byte, 0, 44+len(data))
	out = append(out, "RIFF"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(36+len(data)))
	out = append(out, "WAVEfmt "...)
	out = binary.LittleEndian.AppendUint32(out, 16)
	out = append(out, format...)
	out = append(out, "data"...)
	out = binary.LittleEndian.AppendUint32(out, uint32(len(data)))
	return append(out, data...), nil
}
