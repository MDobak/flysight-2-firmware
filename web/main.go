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
	"errors"
	"flag"
	"fmt"
	"html/template"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
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

type server struct {
	keys      map[string]batchKey
	firmwares []firmware
	audioZip  []byte
	audioN    int
	apk       []byte
	version   string
}

// firmwareFlags is the -firmware flag. Each value is PATH or NAME=PATH.
type firmwareFlags []string

func (f *firmwareFlags) String() string     { return strings.Join(*f, ",") }
func (f *firmwareFlags) Set(v string) error { *f = append(*f, v); return nil }

func main() {
	var fwFlags firmwareFlags
	addr := flag.String("addr", "localhost:8080", "listen address")
	keysDir := flag.String("keys", "../Deploy/Public_Keys", "folder with pub_key_*.bin")
	audioDir := flag.String("audio", "../Audio/voice1", "folder with the WAV files")
	version := flag.String("version", buildVersion, "firmware version to show on the page")
	apkFile := flag.String("apk", "", "APK of the Android app. Empty: the page has no app download")
	flag.Var(&fwFlags, "firmware", "UserApp.bin as PATH or NAME=PATH. Use more than once for more firmware choices (default ../Release/UserApp.bin)")
	flag.Parse()
	if len(fwFlags) == 0 {
		fwFlags = firmwareFlags{"../Release/UserApp.bin"}
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
	if s.audioZip, s.audioN, err = makeAudioZip(*audioDir); err != nil {
		log.Fatal(err)
	}
	log.Printf("%d batches, %d sound files", len(s.keys), s.audioN)
	if *apkFile != "" {
		if s.apk, err = os.ReadFile(*apkFile); err != nil {
			log.Fatal(err)
		}
		log.Printf("app: %s, %d bytes", *apkFile, len(s.apk))
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", s.index)
	mux.HandleFunc("POST /firmware", s.firmware)
	mux.HandleFunc("GET /audio.zip", s.audio)
	mux.HandleFunc("GET /flysight.apk", s.app)
	log.Printf("listening on http://%s", *addr)
	srv := &http.Server{Addr: *addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	log.Fatal(srv.ListenAndServe())
}

type pageData struct {
	Version   string
	Firmwares []firmware
	AudioN    int
	APKSize   string // empty if there is no APK
	Error     string
}

func (s *server) render(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	apkSize := ""
	if len(s.apk) > 0 {
		apkSize = fmt.Sprintf("%.1f MB", float64(len(s.apk))/1e6)
	}
	if err := page.Execute(w, pageData{s.version, s.firmwares, s.audioN, apkSize, msg}); err != nil {
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

func (s *server) audio(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", `attachment; filename="flysight-2-audio.zip"`)
	w.Header().Set("Content-Length", fmt.Sprint(len(s.audioZip)))
	w.Write(s.audioZip)
}

func (s *server) app(w http.ResponseWriter, r *http.Request) {
	if len(s.apk) == 0 {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/vnd.android.package-archive")
	w.Header().Set("Content-Disposition", `attachment; filename="FlySight.apk"`)
	w.Header().Set("Content-Length", fmt.Sprint(len(s.apk)))
	w.Write(s.apk)
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
