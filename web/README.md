# FlySight 2 firmware website

A small website in Go, with no dependencies outside the standard library.

- The user uploads `FLYSIGHT.TXT`. The server reads only the `Pubkey_X` line,
  finds the hardware batch in `Deploy/Public_Keys`, and returns `APP.SFB` for
  this batch. It does not keep the uploaded file. The log shows only the batch.
- `/audio.zip` gives the WAV files of `Audio/voice1` in the folder `AUDIO`.
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
| `-audio` | `../Audio/voice1` | Folder with the WAV files. |
| `-version` | (empty) | The version text on the page. |

The server reads all files at the start. Start it again after a new build.

## Test

```sh
go test ./...
```
