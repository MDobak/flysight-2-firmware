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
| `FlySight/alt_ref.c/h` | Altitude reference: ground level (zero), barometer height, and `Alt_Source`. |
| `FlySight/config_ext.c/h` | Fork configuration keys (`Alt_Source`, `Alt_Zero`, `Alt_Format`). |
| `FlySight/device_state_ext.c/h` | Fork `DS_Control_Point` commands. |
| `FlySight/mode_ext.c/h` | Fork modes (sensor mode). |
| `FlySight/sensor_ble.c/h` | `SD_Sensor_Measurement` packets and the BLE task. |
| `FlySight/sensor_mode.c/h` | Sensor mode. |
| `Scripts/build.sh`, `Scripts/make_sfb.py` | Build without STM32CubeIDE, and make `APP.SFB`. |

## Hooks in upstream files

| File | Hook |
|------|------|
| `Core/Inc/app_conf.h` | `CFG_TASK_FS_SENSOR_BLE_ID` in the task list. |
| `FlySight/config.c` | `FS_ConfigExt_Init()`, `FS_ConfigExt_Handle()`, `FS_ConfigExt_Write()`, and `enable_nav = 1` (no `Device_ID` lock). |
| `FlySight/device_state.c` | `DS_CMD_REQUEST_SENSOR` (`0x15`) as a mode request, and `DeviceStateExt_Handle()` in the `default` case. |
| `FlySight/mode.c`, `mode.h` | `FS_MODE_STATE_SENSOR`, `FS_MODE_EVENT_EXT_STOP`, the `FS_Mode_State_Ext` handler, and `FS_ModeExt_Init()` in `FS_Mode_EnterRequestedState`. |
| `FlySight/active_mode.c` | `FS_AltRef_Start()`, and the barometer is on for `Alt_Source: 1`. |
| `FlySight/active_control.c` | `Sensor_BLE_DataReady()` in the sensor callbacks, `FS_AltRef_UpdateBaro()`, and `FS_AltRef_UpdateGNSS()`. |
| `FlySight/audio_control.c` | Altitude from `alt_ref.c`, the spoken altitude format, and audio without a GNSS fix (barometer). The audio reads its GNSS data from `gnssSource`. |
| `FlySight/activelook_mode0.c` | `ALT_MIN_MM` is `INT32_MIN` (no minimum altitude). |
| `FlySight/vbat.c`, `vbat.h` | `FS_VBAT_Measure()` at the end of the file. |
| `STM32_WPAN/App/custom_app.c` | The `SD_Sensor_Measurement` cases, `Sensor_BLE_Init()`, `Sensor_BLE_OnDisconnect()`, and `Custom_VBAT_Refresh()`. All are in `USER CODE` sections. |
| `STM32_WPAN/App/custom_stm.c`, `custom_stm.h` | Generated from the `.ioc` file. |

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
- `0x16` (`DS_CMD_SET_ALT_ZERO`): Set the ground level (zero) for the altitude alarms, windows, and calls to the current position. Accepted only in active and sensor mode. Payload: none.
  - The GNSS part is set if the last 10 GNSS measurements had a 3D fix and a vertical accuracy better than 10 m.
  - The barometer part is set after 10 barometer averages.
- `0x17` (`DS_CMD_GET_ALT_ZERO`): Get the ground level. Accepted in all modes. Payload: none.

Response data of `0x16` and `0x17` (12 bytes, little-endian):

| Offset | Type | Value |
|--------|------|-------|
| 0 | `uint8` | Parts that this command set: `0x01` GNSS, `0x02` barometer. Always 0 for `0x17`. |
| 1 | `uint8` | Zero flags: `0x01` GNSS zero is set, `0x02` barometer zero is set, `0x04` the last zero is automatic (`Alt_Zero: 1`). |
| 2 | `int32` | GNSS ground level (mm above sea level). |
| 6 | `int32` | Ground pressure (Pa × 100). |
| 10 | `uint8` | `Alt_Source` of the last configuration that the firmware read. |
| 11 | `uint8` | `Alt_Zero` of the last configuration that the firmware read. |

Notes:

- RAM keeps the zero in sleep mode. A reset clears it.
- With a GNSS zero, the firmware ignores `DZ_Elev`.
- With `Alt_Source: 1` and a barometer zero, the height is the standard-atmosphere altitude difference between the current pressure and the ground pressure.
- The air flow in freefall gives barometer noise of approximately 10 m. A filter removes this noise. At each GNSS measurement, the filter moves the altitude with the vertical speed (GNSS, or barometer without a fix). Then it moves the altitude a part of the distance to the new barometer value (time constant 1 s). Thus the height has no delay, and the alarms, windows, and altitude calls do not repeat.
- With the barometer height, the alarms, the windows, the altitude calls, and the spoken altitude work also without a GNSS fix. Then the barometer gives the vertical speed for `V_Thresh`, and `H_Thresh` is not used. The speech list speaks only its altitude items, and the tone is off.
- With `Alt_Zero: 1`, the firmware sets the zero again after 1 minute with no movement in active mode. "No movement" is a ground speed and a vertical speed below 0.5 m/s.
