# Fork changes

This fork adds features for the companion Android app (`android/`). Most fork code is in new files. The upstream files have only small hooks. This makes it easy to merge new upstream versions.

## Merge upstream changes

1. Run `git fetch origin`.
2. Run `git merge origin/develop`.
3. If a file has a conflict, keep the upstream lines and add the hook again. The table below shows each hook.
4. If upstream changed `flysight-2-firmware.ioc`, generate the code again in STM32CubeMX. Then make sure that `SD_Sensor_Measurement` is in `custom_stm.c` and `custom_stm.h`.
5. Build the firmware with `Scripts/build.sh`.

## New files

| File | Function |
|------|----------|
| `Audio/original/` | The sound files of the `/AUDIO` folder on the microSD card of the FlySight (24 kHz, mono, 16-bit, 44-byte header). |
| `FlySight/alt_ref.c/h` | Altitude reference: ground level (zero), barometer height, and `Alt_Source`. |
| `FlySight/config_ext.c/h` | Fork configuration keys (`Alt_Source`, `Alt_Zero`, `Alt_Format`). |
| `FlySight/device_state_ext.c/h` | Fork `DS_Control_Point` commands. |
| `FlySight/mode_ext.c/h` | Fork modes (sensor mode and replay mode). |
| `FlySight/replay_mode.c/h` | Replay mode. |
| `FlySight/replay_parse.c/h` | Parsers for `track.csv` and `sensor.csv`. No hardware. |
| `FlySight/sensor_ble.c/h` | `SD_Sensor_Measurement` packets and the BLE task. |
| `FlySight/sensor_mode.c/h` | Sensor mode. |
| `FlySight/tone_ext.c/h` | The change of value 1 for `Mode_2: 9` (slope of the last second). No hardware. |
| `Audio/voice1/`, `Audio/voice1-1.25/` | New sound files, at 1.0 and 1.25 speed: the TTS model Fish Audio Drama 3, with the voice of `Audio/original/`. |
| `Audio/voice2/`, `Audio/voice2-1.25/` | New sound files, at 1.0 and 1.25 speed: the TTS model Fish Audio Drama 3, with the voice of a male reader of LibriTTS-R (speaker 2078). |
| `Audio/voice3/`, `Audio/voice3-1.25/` | New sound files, at 1.0 and 1.25 speed: the macOS voice Samantha. |
| `Audio/words.tsv` | The words of the new sound files. |
| `Samples/wingsuit/` | A sample wingsuit jump from an airplane, as a log folder of the FlySight. `SOURCE.txt` gives the source and the license. |
| `Scripts/build.sh`, `Scripts/make_sfb.py` | Build without STM32CubeIDE, and make `APP.SFB`. |
| `Tests/test_replay_parse.c` | Host test for `replay_parse.c`. The file shows the command. |
| `web/` | A website in Go: it makes `APP.SFB` from `FLYSIGHT.TXT`, and gives the new sound files and the sample jump as ZIP files, and the Android app. `web/Dockerfile` makes an image with all files of the website. See `web/README.md`. |
| `.github/workflows/web-image.yml` | GitHub Actions: builds the website image and pushes it to `ghcr.io`. |

## Hooks in upstream files

| File | Hook |
|------|------|
| `Core/Inc/app_conf.h` | `CFG_TASK_FS_SENSOR_BLE_ID` and `CFG_TASK_FS_REPLAY_ID` in the task list. |
| `FlySight/config.c` | `FS_ConfigExt_Init()`, `FS_ConfigExt_Handle()`, `FS_ConfigExt_Write()`, and `enable_nav = 1` (no `Device_ID` lock). |
| `FlySight/device_state.c` | `DS_CMD_REQUEST_SENSOR` (`0x15`) and `DS_CMD_REQUEST_REPLAY` (`0x19`) as mode requests, and `DeviceStateExt_Handle()` in the `default` case. |
| `FlySight/mode.c`, `mode.h` | `FS_MODE_STATE_SENSOR`, `FS_MODE_STATE_REPLAY`, `FS_MODE_EVENT_EXT_STOP`, the `FS_Mode_State_Ext` handler, and `FS_ModeExt_Init()` in `FS_Mode_EnterRequestedState`. |
| `FlySight/active_mode.c` | `FS_AltRef_Start()`, and the barometer is on for `Alt_Source: 1`. |
| `FlySight/active_control.c` | `Sensor_BLE_DataReady()` in the sensor callbacks, `FS_AltRef_UpdateBaro()`, and `FS_AltRef_UpdateGNSS()`. |
| `FlySight/audio.c`, `audio.h` | The tone generator (see [Tone](#tone)): `FS_Audio_Voice_t`, the fade in `FS_Audio_LoadTone()`, `FS_Audio_Splice()`, and the splice in `FS_Audio_Beep()`. New functions: `FS_Audio_Tone()`, `FS_Audio_SetToneFrequency()`, `FS_Audio_EndTone()`, `FS_Audio_FadeOut()`, `FS_Audio_IsTone()`, and `FS_Audio_IsQuiet()`. |
| `FlySight/audio_control.c` | Altitude from `alt_ref.c`, the spoken altitude format, and audio without a GNSS fix (barometer). The audio reads its GNSS data from `gnssSource`. The tone changes (see [Tone](#tone)): `updateToneOutput()`, `speechWaiting()`, `toneDue`, `toneFlat` in `setTone()`, and `FS_ToneExt_Change()` in `updateTones()`. `FS_Audio_FadeOut()` when the tone is suppressed. |
| `FlySight/activelook_mode0.c` | `ALT_MIN_MM` is `INT32_MIN` (no minimum altitude). |
| `FlySight/crs.c` | A zero byte after the path for `CREATE`, `DELETE` and `MK_DIR`. Upstream does not end these paths, so old bytes of the buffer follow the path. A new command during a read or a listing stops the transfer, and the idle state handles the command. Upstream discarded the command. The last `FILE_ACK` of a read ends the read before the next packet. After a return to idle, `FS_CRS_Update()` handles the packets that are in the queue. |
| `FlySight/vbat.c`, `vbat.h` | `FS_VBAT_Measure()` at the end of the file. |
| `STM32_WPAN/App/custom_app.c` | The `SD_Sensor_Measurement` cases, `Sensor_BLE_Init()`, `Sensor_BLE_OnDisconnect()`, `Custom_VBAT_Refresh()`, and `Custom_CRS_UngetRxPacket()` (also in `custom_app.h`). All are in `USER CODE` sections. |
| `STM32_WPAN/App/custom_stm.c`, `custom_stm.h` | Generated from the `.ioc` file. |

## Tone

- With `Flatline: 1`, the constant tone is one continuous sound. Upstream played a chain of 125 ms beeps, with a click between two beeps. The pitch of the continuous tone moves smoothly to the new value (time constant 21 ms). Speech, alarms, and the altitude calls end the continuous tone.
- Each tone starts and ends with a fade of 5 ms. Upstream started each beep at a random point of the sine wave and stopped it at full level, so the speaker clicked.
- A beep during a tone, and the end of a tone, do not stop the DMA. `FS_Audio_Splice()` writes the new sound into the buffer 4 ms after the read position, with a fade between the two sounds. Upstream stopped the DMA and the SAI. This stopped the audio clock and gave a click. The splice goes back to the tone state that `FS_Audio_LoadTone()` keeps each 32 samples.
- The tone generator uses about 2% of the CPU (64 MHz) while a tone plays. A splice writes the buffer again (up to 85 ms of audio, about 2 ms of CPU).
- With `Flatline: 1`, the constant tone starts when value 2 goes below `Min_Val_2`. It stops only when value 2 goes above `Min_Val_2` plus 1/8 of the range (`Max_Val_2` − `Min_Val_2`). Thus small noise does not switch the constant tone on and off.
- With `Mode_2: 9`, value 2 is the slope of value 1 in the last second (least squares). Upstream used the difference of two samples, 2 × `Rate` apart, which has much noise.
- The beeps start in the consumer task, not in the timer interrupt. Thus all sounds start in one task.

## Speech

- Each spoken value starts with its name, also with only one value in the speech list. For example, "glide 1 2" or "V speed 1 8 5". Upstream spoke the name only with two or more values.
- The names are short: "H speed" (`hspeed.wav`), "V speed" (`vspeed.wav`), "speed", "glide", "inverse glide", "dive", "direction", "distance", "bearing", and "alti" (`alt.wav`).
- The FlySight does not speak the decimal point. 1.2 is "1 2".
- The altitude calls, the altitude in the speech list, and the altitude after power on are "alti" and the altitude, with no unit. The short format (`Alt_Format: 1`) has no decimal point.
- All folders in `Audio/`, but not `Audio/original/`, have the sound files for this format. The upstream sound files do not have `hspeed.wav` and `vspeed.wav`, and their `alt.wav` says "altitude".

## BLE extensions

These extensions add to [ble.md](ble.md).

### Sensor mode

| Value | Mode | Description |
|-------|------|-------------|
| `0x06` | SENSOR | Live sensor view. No recording. No file access. |

- The LED pulses orange.
- A central starts sensor mode from Idle with `DS_CMD_REQUEST_SENSOR` (`0x15`). There is no button sequence for this mode.
- The FlySight reads `config.txt` and the selected profile. Then it starts the GNSS, barometer, humidity sensor, magnetometer, IMU, and battery measurement. It starts all sensors, also if the configuration does not log them. It does not record data.
- `SD_GNSS_Measurement` and `SD_Sensor_Measurement` notifications are available if subscribed.
- The FlySight goes back to Idle on `DS_CMD_REQUEST_SLEEP`, on a button push, or when the BLE connection closes.

### Replay mode

| Value | Mode | Description |
|-------|------|-------------|
| `0x07` | REPLAY | Plays a recorded track through the audio. No recording. No file access over BLE. |

- The LED pulses green. On an error, the LED is red and the FlySight goes back to Idle.
- A central sets the track with `DS_CMD_SET_REPLAY` (`0x18`). Then it starts replay mode from Idle with `DS_CMD_REQUEST_REPLAY` (`0x19`).
- The FlySight reads `config.txt` and the selected profile. Then it reads `track.csv` of the log folder at real-time speed. It reads the barometer data from `sensor.csv` if the file is there. The `$TIME` lines of `sensor.csv` give the time pulse in UTC time of week, so the FlySight adds the leap seconds (18 s). It uses the most frequent offset of the first 32 `$TIME` lines, because some lines are 1 s wrong. Without `$TIME` lines, the replay uses no barometer data.
- Each `$GNSS` row goes to the altitude reference, as a GNSS measurement does in active mode. A row with `hAcc` above 1 km has no fix.
- The audio gets one row at each `Rate` interval of the configuration, as in active mode. It does not get the rows between, for example in a 20 Hz track. If the track has fewer rows than `Rate`, the audio gets each row. During the replay, `Rate` in memory is the real time between two rows for the audio, so the speech interval is correct. At the end, the FlySight sets `Rate` back.
- There is no GNSS, no log, and no ActiveLook.
- At the start, the ground level is clear. With bit 15 of the start offset, the FlySight sets the ground level from the first measurements of the track. With bit 14, it sets the ground level from the landing before the replay starts: the average GNSS altitude (3D fix only) and the average pressure of the last 5 s of `track.csv` and `sensor.csv`. It reads only the last 32 KB of each file for this. If a file has no usable data, that part of the ground level stays clear. At the end, the FlySight sets the ground level that it had before the replay.
- Before the start offset, the rows go only to the altitude reference, without audio.
- The FlySight goes back to Idle 5 s after the end of the track, on `DS_CMD_REQUEST_SLEEP`, or on a button push. A BLE disconnect does not stop the replay.

### `SD_Sensor_Measurement`

- UUID: `00000008-8e22-4541-9d4c-21edae82ed19`, in the Sensor_Data service.
- Properties: Notify. Encrypted Read/Write is necessary.
- The packet has the newest value of each sensor. The FlySight sends a packet at most every 250 ms, and only when a sensor has a new value.
- Notifications come in Active Mode and Sensor Mode. In Active Mode, only the sensors that `config.txt` turns on send values.
- Maximum length: 47 bytes. The length depends on the mask.

Data format (little-endian):

- Byte 0: `mask` (uint8). It shows which sensors have a new value in this packet. The values follow in this order:

| Bit | Sensor | Values | Bytes |
|-----|--------|--------|-------|
| `0x01` | Barometer | `pressure` (int32, Pa × 100), `temperature` (int16, °C × 100) | 6 |
| `0x02` | Humidity | `humidity` (uint16, % × 10), `temperature` (int16, °C × 10) | 4 |
| `0x04` | Magnetometer | `x`, `y`, `z` (int16, gauss × 1000), `temperature` (int16, °C × 10) | 8 |
| `0x08` | IMU | `wx`, `wy`, `wz` (int32, °/s × 1000), `ax`, `ay`, `az` (int32, g × 100000), `temperature` (int16, °C × 100) | 26 |
| `0x10` | Battery | `voltage` (uint16, mV) | 2 |

A sensor that has no new value since the last packet is not in the packet. Keep the last value.

### `DS_Control_Point` commands

- `0x15` (`DS_CMD_REQUEST_SENSOR`): Request sensor mode. Accepted only from sleep.
- `0x16` (`DS_CMD_SET_ALT_ZERO`): Set the ground level (zero) for the altitude alarms, windows, and calls.
  - Payload none: set the two parts to the current position. Accepted only in active and sensor mode. The GNSS part is set if the last 10 GNSS measurements had a 3D fix and a vertical accuracy better than 10 m. The barometer part is set after 10 barometer averages.
  - Payload 9 bytes: set the parts to the values. Accepted in all modes. `uint8` parts to set (`0x01` GNSS, `0x02` barometer), `int32` GNSS ground level (mm above sea level), `int32` ground pressure (Pa × 100). The firmware ignores the value of a part that is not in the parts byte. A ground pressure of 0 or less is not set.
- `0x17` (`DS_CMD_GET_ALT_ZERO`): Get the ground level. Accepted in all modes. Payload: none.
- `0x18` (`DS_CMD_SET_REPLAY`): Set the track for replay mode. Not accepted in replay mode. Payload:
  - Bytes 0-1: `uint16` (little-endian). Bits 0-13: start offset in seconds after the first row. Bit 14: set the ground level from the end of the track (the landing). Bit 15: set the ground level from the start of the track. Set only one of bits 14 and 15.
  - Bytes 2 and up: the log folder without the leading slash, 1 to 17 characters, no NUL. For example `24-05-01/12-34-56` or `temp/0001`.
- `0x19` (`DS_CMD_REQUEST_REPLAY`): Request replay mode. Accepted only from sleep.
- `0x1A` (`DS_CMD_GET_REPLAY`): Get the replay status. Accepted in all modes. Payload: none.

Response data of `0x16` and `0x17` (16 bytes, little-endian):

| Offset | Type | Value |
|--------|------|-------|
| 0 | `uint8` | Parts that this command set: `0x01` GNSS, `0x02` barometer. Always 0 for `0x17`. |
| 1 | `uint8` | Zero flags: `0x01` GNSS zero is set, `0x02` barometer zero is set, `0x04` the last zero is automatic (`Alt_Zero: 1`). |
| 2 | `int32` | GNSS ground level (mm above sea level). |
| 6 | `int32` | Ground pressure (Pa × 100). |
| 10 | `uint8` | `Alt_Source` of the last configuration that the firmware read. |
| 11 | `uint8` | `Alt_Zero` of the last configuration that the firmware read. |
| 12 | `int32` | `DZ_Elev` of the last configuration that the firmware read (mm). |

Response data of `0x1A` (11 bytes, little-endian):

| Offset | Type | Value |
|--------|------|-------|
| 0 | `uint8` | State: 0 idle, 1 seeking (goes to the start offset), 2 playing, 3 done, 4 stopped, 5 error. |
| 1 | `uint8` | Error: 0 none, 1 no track was set, 2 no microSD card, 3 `track.csv` cannot be opened, 4 no GNSS data. |
| 2 | `uint32` | Time of the last row after the first row (ms). |
| 6 | `int32` | Height above the ground of the last row (mm). |
| 10 | `uint8` | Source of the height: 0 GNSS above `DZ_Elev`, 1 GNSS above the ground level, 2 barometer. |

The status stays after replay mode ends.

Notes about the ground level:

- RAM keeps the zero in sleep mode. A reset clears it.
- With a GNSS zero, the firmware ignores `DZ_Elev`.
- With `Alt_Source: 1` and a barometer zero, the height is the standard-atmosphere altitude difference between the current pressure and the ground pressure.
- The air flow in freefall gives barometer noise of approximately 10 m. A filter removes this noise. At each GNSS measurement, the filter moves the altitude with the vertical speed (GNSS, or barometer without a fix). Then it moves the altitude a part of the distance to the new barometer value (time constant 1 s). Thus the height has no delay, and the alarms, windows, and altitude calls do not repeat.
- With the barometer height, the alarms, the windows, the altitude calls, and the spoken altitude work also without a GNSS fix. Then the barometer gives the vertical speed for `V_Thresh`, and `H_Thresh` is not used. The speech list speaks only its altitude items, and the tone is off.
- With `Alt_Zero: 1`, the firmware sets the zero again after 1 minute with no movement in active mode. "No movement" is a ground speed and a vertical speed below 0.5 m/s.
