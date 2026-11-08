# FlySight 2: Custom Firmware Update and Recovery

This document gives two procedures:

1. [Firmware update procedure](#1-firmware-update-procedure): build a custom firmware and install it.
2. [Recovery procedure](#2-recovery-procedure): install the official firmware again if the custom firmware does not operate correctly.

You do not need to open the case for these procedures.

Do the [preparation](#0-preparation) before you install a custom firmware for the first time.

## How the update system operates

- The flash memory has two parts. The bootloader is at `0x08000000`. The application starts at `0x08019200`.
- A firmware update replaces only the application. It does not change the bootloader.
- After each reset, the bootloader runs first. It starts the application, or it starts the firmware update mode (orange LED).
- The bootloader starts the hardware watchdog. The application must refresh it. If the application stops, the watchdog resets the device.
- The FlySight accepts only an encrypted `.sfb` file. Each hardware batch has a different key. A file for one batch does not install on a different batch.
- The public batch keys are sufficient to make an `.sfb` file. You do not need a private key from Bionic Avionics.

Note: The bootloader source code is not public. The information above comes from the public documents, the linker script, and the application source code.

## 0. Preparation

Do these steps one time, before the first custom firmware.

### 0.1 Record the device data

1. Connect the FlySight to the computer with a USB cable.
2. Open the file `FLYSIGHT.TXT` in the root folder of the FlySight drive.
3. Record these values:
   - `Firmware_Ver`: the installed firmware version.
   - `Stack_Ver`: the installed wireless stack version.
   - `Pubkey_X`: the key of your hardware batch.
4. Copy `FLYSIGHT.TXT` to a safe location on the computer.

### 0.2 Find the batch of your device

Find your `Pubkey_X` value in this table.

| `Pubkey_X` | Batch | Key file |
| :--- | :--- | :--- |
| `486bee2d3dd60fd0de481a072d12ae5492c52c0cb2bbdde697b16aad1ca903f3` | B2 | `pub_key_b2.bin` |
| `211d721dbd9114a6bbe9ba1256ccbbfbf2c4015b139126ab56c331935e29c016` | B3 | `pub_key_b3.bin` |
| `dac40d2597e371390a84f3196d4256b5c96a89373afb3584dcbc226b74bcbc0c` | B4 | `pub_key_b4.bin` |
| `3157e02846ddfd2a78347e099ba1d86c92920b101137f28091ed0dec3b4589c2` | B5 | `pub_key_b5.bin` |
| `8ee7870905b3e792df0ed796b996c9ff743d196c2e8abbf1a39f39733cceac80` | B6 | `pub_key_b6.bin` |
| `728168718da35f7de7cf9ab6b10eb6d74f2cb31614daa2cc4e7963c2ab4e5b11` | B7 | `pub_key_b7.bin` |
| `b36f45a10cbe91772a3d80723f8808f1a660f963ce34938a30b86f49660a3404` | B8 | `pub_key_b8.bin` |
| `df8afa69a077a213bdc8e11ba65548b7e2a3982cb92ff4072d92dbf4a9955654` | B9 | `pub_key_b9.bin` |
| `338f4f697b98a9b1eff90bfb3ad2f13e5c55bac3a1e8a5c132a0b67b777e0cd4` | DEV | `pub_key_dev.bin` |
| `ffff…ffff` (all `f`) | B1 | No public key |

The key files are in the `Deploy/Public_Keys` folder. This folder comes from the upstream `develop` branch.

If `Pubkey_X` is all `f`, the device is from batch B1. Do not continue. You cannot make a custom firmware for B1, because its public key is not published.

If `Pubkey_X` is not in the table, do not continue. Your batch is newer than this document. Get the new key file from the upstream `develop` branch.

### 0.3 Keep a copy of the official firmware

1. Go to the official update page: <https://flysight.ca/?p=141>.
2. Upload `FLYSIGHT.TXT` as the page tells you.
3. Download the recommended `APP.SFB`. If the page also gives `STACK.BIN`, download it too.
4. Put the files in a safe folder, for example `~/flysight-official/<version>/`.

You need these files for the recovery procedure. With these files, the recovery procedure does not need an internet connection.

### 0.4 Do a recovery test with the official firmware

Do the [recovery procedure](#2-recovery-procedure) one time with the official files from step 0.3.

This test has no risk, because it installs the official firmware. It confirms that the update mode operates on your device. It also gives you practice with the procedure before you need it.

## 1. Firmware update procedure

### 1.1 Check the requirements

- Your batch is in the table in step 0.2, and it is not B1.
- You have a copy of the official `APP.SFB` (step 0.3).
- The recovery test was successful (step 0.4).
- `Stack_Ver` in `FLYSIGHT.TXT` is `1.19.0`. This firmware needs wireless stack 1.19.0.

If `Stack_Ver` is lower than `1.19.0`, install the latest official firmware first. The official procedure also updates the stack.

Note: This branch starts from the upstream `develop` branch at the beta release `v2026.09.09.develop`. The custom firmware has the features of that beta release. It does not have features from a newer official release.

### 1.2 Build the firmware

First, commit your changes to a branch.
The build writes the result of `git describe --tags` into the firmware version. After a commit, the version is different from the official version, for example `v2026.09.09.develop-2-g1234abc`. Uncommitted changes do not change the version, so you cannot see the difference.

Then use one of the two methods below. Both methods make the file `Release/UserApp.bin` with the same settings.

#### Method A: Command line (no STM32CubeIDE)

1. Go to the repository folder.
2. Run the build script:

   ```bash
   Scripts/build.sh
   ```

   The script needs the Arm GNU Toolchain (`arm-none-eabi-gcc`) on the PATH. The tested version is 13.3.Rel1.
3. Make sure that the script shows `Result: .../Release/UserApp.bin`.
4. Make sure that `Warnings:` shows 23. The original `develop` code has 23 warnings. A larger number shows a new problem.

#### Method B: STM32CubeIDE

1. Open the project in STM32CubeIDE.
2. Select the `Release` build configuration.
3. On macOS or Linux, change the pre-build step of the `Release` configuration:
   1. Open **Project > Properties > C/C++ Build > Settings > Build Steps**.
   2. Change **Pre-build steps** from `..\prebuild.bat` to `../prebuild.sh`.

   The `Release` configuration uses `prebuild.bat`, which runs only on Windows. Do not commit this change to `.cproject`.
4. Build the project.
5. Make sure that the file `Release/UserApp.bin` exists.

### 1.3 Make the `APP.SFB` file

The script `Scripts/make_sfb.py` encrypts `UserApp.bin` with the public key of your batch. It uses `Deploy/deploy_firmware.py` without changes. The official firmware files come from the same code.

The first run makes a Python environment in `~/.cache/flysight-2-firmware/venv` with `python3`. If the `coincurve` package does not install on your Python version, set `PYTHON` to a different version, for example `PYTHON=python3.12 Scripts/build.sh`.

#### With the build script (Method A)

Add one of these options to the build command from 1.2. The script then builds the firmware and makes `Release/APP.SFB`.

- Find the batch from `FLYSIGHT.TXT` (recommended):

  ```bash
  Scripts/build.sh --flysight-txt /Volumes/<FlySight drive>/FLYSIGHT.TXT
  ```

  You can also use the copy of `FLYSIGHT.TXT` from step 0.1.
- Give the batch name from step 0.2, for example B7:

  ```bash
  Scripts/build.sh --batch B7
  ```

The script remembers the batch in `~/.cache/flysight-2-firmware/batch`. The next builds make `APP.SFB` for the same batch without an option, and the FlySight does not need to be connected:

```bash
Scripts/build.sh
```

- To use a different batch, add `--batch` or `--flysight-txt` again. The script then remembers the new batch.
- To build only `UserApp.bin`, add `--no-sfb`.
- To delete the remembered batch, add `--forget-batch`.

Each build deletes the old `Release/APP.SFB` first. Thus `Release/` never contains an `APP.SFB` from an older build.

#### After an STM32CubeIDE build (Method B)

1. Run the build script one time with `--batch` to make the Python environment.
2. Run the packaging script:

   ```bash
   ~/.cache/flysight-2-firmware/venv/bin/python Scripts/make_sfb.py \
     Release/UserApp.bin Release/APP.SFB --flysight-txt /Volumes/<FlySight drive>/FLYSIGHT.TXT
   ```

#### Check the result

1. Make sure that the script shows the correct batch, for example `Batch: B7`.
2. Make sure that the file `Release/APP.SFB` exists.
   The script checks that the file starts with `SFU1` and is 512 bytes larger than `UserApp.bin`.

The script stops with an error message in these conditions:
- The device is from batch B1.
- `Pubkey_X` is not in `Deploy/Public_Keys`.
- `FLYSIGHT.TXT` has no `Pubkey_X`.

### 1.4 Install the firmware

1. Charge the battery.
   Do not start an update with a low battery. A power loss during the installation can stop the update.
2. Connect the FlySight to the computer with a USB cable.
3. Push and hold the button until the FlySight resets and the LED is orange. This takes approximately 10 seconds.
4. Open the FlySight drive on the computer.
5. If the drive has no `FW` folder, make it in the root folder.
6. Copy `Release/APP.SFB` into the `FW` folder. Use the Terminal on macOS:

   ```bash
   cp -X Release/APP.SFB /Volumes/<FlySight drive>/FW/APP.SFB
   ```

   The `-X` option stops macOS from copying extended attributes. The Finder can add hidden `._` files to the folder.
7. Eject the FlySight drive on the computer.
   Do not disconnect the cable before the eject is complete. The copy of the file can be incomplete.
8. Disconnect the USB cable.
9. Wait a few seconds. The FlySight resets and installs the firmware.

### 1.5 Check the installation

1. Connect the FlySight to the computer with a USB cable.
2. Open `FLYSIGHT.TXT`.
3. Make sure that `Firmware_Ver` shows the version of your build.
4. Disconnect the USB cable.
5. Do a short test of the functions that you changed.

If a check fails, do the [recovery procedure](#2-recovery-procedure).

## 2. Recovery procedure

Use this procedure if the FlySight does not operate correctly after a firmware update.

### 2.1 Signs of a defective firmware

| Sign | Possible cause |
| :--- | :--- |
| The red LED stays on. | The firmware stopped in `Error_Handler()`. The watchdog then resets the device. |
| The FlySight resets again and again. | The firmware stops during start-up, and the watchdog resets the device. |
| No LED, and the button has no effect. | The firmware does not start correctly. |
| The FlySight drive does not show on the computer in normal operation. | The USB function of the application is defective. |
| Bluetooth, the GNSS log, or audio does not operate. | A function of the application is defective. |

The procedure is the same for all of these signs.

### 2.2 Install the official firmware

1. Get the official files from step 0.3 (`APP.SFB`, and `STACK.BIN` if you have it).
   If you do not have them, download them from <https://flysight.ca/?p=141>. Use the copy of `FLYSIGHT.TXT` from step 0.1.
2. Connect the FlySight to the computer with a USB cable.
3. Push and hold the button until the FlySight resets and the LED is orange. This takes approximately 10 seconds.
   This step very probably operates when the application is defective. The 10-second reset is not in the application code, and the bootloader controls the orange mode.
4. Open the FlySight drive on the computer.
5. If the drive has no `FW` folder, make it in the root folder.
6. Copy the official `APP.SFB` into the `FW` folder. If you have `STACK.BIN`, copy it into the `FW` folder too.

   ```bash
   cp -X ~/flysight-official/<version>/APP.SFB /Volumes/<FlySight drive>/FW/APP.SFB
   ```

7. Eject the FlySight drive on the computer.
8. Disconnect the USB cable.
9. Wait a few seconds. The FlySight resets and installs the firmware.
10. Do the checks in [1.5](#15-check-the-installation). `Firmware_Ver` must show the official version.

### 2.3 If the orange mode does not start

These steps are general fault-finding steps. They are not from the official FlySight documents.

1. Charge the FlySight for a minimum of 1 hour.
   A fully discharged battery can prevent the start.
2. Disconnect the USB cable.
3. Push and hold the button for 15 seconds, then release it.
4. Connect the USB cable.
5. Push and hold the button again until the LED is orange.
6. If the LED is orange, continue at step 4 of [2.2](#22-install-the-official-firmware).
7. If the drive does not show on the computer, use a different USB cable and a different USB port.
   Some USB cables supply only power and do not carry data.

If the orange mode does not start after these steps, the problem is not in the application. Possible causes are a hardware fault or damage to the bootloader. To repair the device, you must open the case and connect an SWD programmer, or send the device to Bionic Avionics. A custom application that does not write to flash memory or to the option bytes cannot damage the bootloader.

## References

- Official update instructions: <https://flysight.ca/?p=141>
- Upstream branch `develop`, documents `Docs/deploy.md`, `Docs/firmware.md`, `Docs/ble.md`
- `Deploy/` (from the upstream branch `develop`): `deploy_firmware.py`, `ecdh_aesgcm.py`, `Public_Keys/`
- `Scripts/build.sh`, `Scripts/make_sfb.py`
