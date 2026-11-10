# FlySight 2 firmware website

A small website in Go, with no dependencies outside the standard library.

- The user uploads `FLYSIGHT.TXT`. The server reads only the `Pubkey_X` line,
  finds the hardware batch in `Deploy/Public_Keys`, and returns `APP.SFB` for
  this batch. It does not keep the uploaded file. The log shows only the batch.
- `/audio.zip` gives the WAV files of a voice in the folder `AUDIO`. With more
  than one `-audio` flag, the page shows a voice choice, and
  `/audio.zip?voice=ID` selects the voice. The ID is the name of the folder.
- `/sample.wav?voice=ID` gives a sample of a voice: three calls of the
  FlySight. The page has a button that plays the sample of each voice. Each
  voice folder must have the sound files of the sample (`sampleCalls` in
  `main.go`).
- `/sample-jump.zip` gives a sample jump: the files in the folder of `-jump`,
  with their folders (for example `24-10-11/18-33-52/TRACK.CSV`).
- `/flysight.apk` gives the Android app (`android/`), if you start the server
  with `-apk`.
- The page has a link to <https://flysight.ca/firmware/> for the official
  firmware and the original audio files.

`APP.SFB` is for a hardware batch, not for one device. The encryption uses only
the public key of the batch, so the server has no secret. `sfb.go` is a port of
`Deploy/deploy_firmware.py` and `Deploy/ecdh_aesgcm.py`. A test makes sure that
the two give the same file, byte for byte. Batch B1 has no published key, so
the server refuses B1 devices.

## Run

Build the firmware first (`Scripts/build.sh`). Then, with Go 1.24 or later, in this folder:

```sh
go run . -version "$(git describe --tags --always)"
```

Open <http://localhost:8080>.

| Flag | Default | Function |
|------|---------|----------|
| `-addr` | `localhost:8080` | Listen address. Use `:8080` for all interfaces. |
| `-firmware` | `../Release/UserApp.bin` | `PATH` or `NAME=PATH`. Use the flag more than once to show a firmware choice, for example `-firmware develop=a.bin -firmware simulation=b.bin`. |
| `-keys` | `../Deploy/Public_Keys` | Folder with `pub_key_*.bin`. |
| `-audio` | `../Audio/voice1` | Folder with the WAV files of a voice, as `DIR` or `LABEL=DIR`. Use the flag more than once for more voices, for example `-audio "Voice 1=../Audio/voice1" -audio "Voice 2=../Audio/voice2"`. The first voice is the default. |
| `-version` | (empty) | The version text on the page. |
| `-apk` | (empty) | APK of the Android app. If it is empty, the page has no app download. |
| `-jump` | `../Samples/wingsuit` | Folder with a sample jump, as log folders of the FlySight. If it is empty, the page has no sample jump. |

The server reads all files at the start. Start it again after a new build.

## Docker

The image has all files that the website needs: the server, the firmware
(built in the image from the sources), the public keys, the sound files and
the Android app (a debug build, also built in the image).
The server listens on port 8080. It runs as a user without root rights.

GitHub Actions (`.github/workflows/web-image.yml`) builds the image for
`linux/amd64` and `linux/arm64` after each push to `mdobak/develop` or
`mdobak/simulation`. The repository secret `ANDROID_DEBUG_KEYSTORE` is the
base64 text of the keystore that signs the app. All builds must use the same
key, because Android updates an app only if the key is the same. To set the
secret:

```sh
base64 -i ~/.android/debug.keystore | gh secret set ANDROID_DEBUG_KEYSTORE
```

Images:

| Branch | Image |
|--------|-------|
| `mdobak/develop` | `ghcr.io/mdobak/flysight-2-firmware-web:develop` |
| `mdobak/simulation` | `ghcr.io/mdobak/flysight-2-firmware-web:simulation` |

Each push also gets a tag with the short commit hash, for example `develop-3e64227`.

Run the website on a server:

```sh
docker run -d --name flysight-web --restart unless-stopped -p 80:8080 \
  ghcr.io/mdobak/flysight-2-firmware-web:develop
```

The website uses HTTP only. For HTTPS, put a reverse proxy (for example
Caddy or nginx) in front of it.

To get a new image, pull it and start the container again:

```sh
docker pull ghcr.io/mdobak/flysight-2-firmware-web:develop
docker rm -f flysight-web
# then the docker run command above
```

Build the image locally, from the root of the repository:

```sh
docker build -f web/Dockerfile --build-arg VERSION="$(git describe --tags --always)" \
  --secret id=android_keystore,src="$HOME/.android/debug.keystore" -t flysight-web .
```

The Android build tools have Linux programs for x86-64 only. Thus the app
always builds for `linux/amd64`. On an Arm computer, Docker uses emulation for
this step, and the step is slow.

The image uses the same compiler as the local build (Arm GNU Toolchain
13.3.Rel1). The compiled object files are the same. The linked
`UserApp.bin` can be different by some bytes, because the C libraries in the
macOS and Linux toolchain packages are not the same files.

## Test

```sh
go test ./...
```
