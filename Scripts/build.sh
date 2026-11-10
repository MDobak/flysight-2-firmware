#!/usr/bin/env bash
#
# Build the FlySight 2 firmware without STM32CubeIDE.
#
# The compiler and linker settings are the same as the "Release"
# configuration in .cproject. The result is Release/UserApp.bin,
# which is the same file that the STM32CubeIDE build makes.
#
# With --batch or --flysight-txt, the script also makes Release/APP.SFB.
# This is the encrypted update file for the FlySight bootloader.
# The script remembers the batch. The next builds then make APP.SFB for
# the same batch without an option.
#
# Usage:
#   Scripts/build.sh [options]
#
# Options:
#   --batch NAME          Make APP.SFB for this hardware batch (for example B7).
#   --flysight-txt FILE   Make APP.SFB for the batch in this FLYSIGHT.TXT
#                         (for example /Volumes/FLYSIGHT/FLYSIGHT.TXT).
#   --no-sfb              Do not make APP.SFB. Keep the remembered batch.
#   --forget-batch        Delete the remembered batch, then build.
#   --out DIR             Output folder. The default is Release/ in the repository.
#
# Without git (for example in the Docker build of web/), set FLYSIGHT_VERSION.
# The script then finds the source files with find.
#
# The remembered batch is in ~/.cache/flysight-2-firmware/batch.
# APP.SFB needs Python 3 (PYTHON selects the version). The script makes a Python environment
# in ~/.cache/flysight-2-firmware/venv the first time.

set -euo pipefail

usage() { sed -n '3,30p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=$REPO/Release
BATCH=""
FLYSIGHT_TXT=""
NO_SFB=0
FORGET_BATCH=0
CACHE=${XDG_CACHE_HOME:-$HOME/.cache}/flysight-2-firmware
BATCH_FILE=$CACHE/batch

while [ $# -gt 0 ]; do
  case "$1" in
    --batch) BATCH=${2:?--batch needs a value}; shift 2 ;;
    --flysight-txt) FLYSIGHT_TXT=${2:?--flysight-txt needs a value}; shift 2 ;;
    --no-sfb) NO_SFB=1; shift ;;
    --forget-batch) FORGET_BATCH=1; shift ;;
    --out) OUT=${2:?--out needs a value}; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "Unknown option: $1" >&2; usage 1 >&2 ;;
  esac
done

if [ -n "$BATCH" ] && [ -n "$FLYSIGHT_TXT" ]; then
  echo "Use --batch or --flysight-txt, not both." >&2
  exit 1
fi
if [ "$NO_SFB" = 1 ] && { [ -n "$BATCH" ] || [ -n "$FLYSIGHT_TXT" ]; }; then
  echo "Use --no-sfb without --batch and --flysight-txt." >&2
  exit 1
fi
if [ -n "$FLYSIGHT_TXT" ]; then
  [ -f "$FLYSIGHT_TXT" ] || { echo "File not found: $FLYSIGHT_TXT" >&2; exit 1; }
  FLYSIGHT_TXT=$(cd "$(dirname "$FLYSIGHT_TXT")" && pwd)/$(basename "$FLYSIGHT_TXT")
fi

if [ "$FORGET_BATCH" = 1 ]; then
  rm -f "$BATCH_FILE"
  echo "Deleted the remembered batch."
fi
# No batch option: use the batch of the last APP.SFB
if [ "$NO_SFB" = 0 ] && [ -z "$BATCH" ] && [ -z "$FLYSIGHT_TXT" ] && [ -s "$BATCH_FILE" ]; then
  BATCH=$(tr -d '[:space:]' < "$BATCH_FILE")
  echo "Remembered batch: $BATCH (from $BATCH_FILE). To use a different batch, add --batch NAME."
fi

mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 4)

command -v arm-none-eabi-gcc >/dev/null || {
  echo "arm-none-eabi-gcc not found. Install the Arm GNU Toolchain and add its bin folder to the PATH." >&2
  exit 1
}

cd "$REPO"

HAVE_GIT=0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 && HAVE_GIT=1
if [ "$HAVE_GIT" = 0 ] && [ -z "${FLYSIGHT_VERSION:-}" ]; then
  echo "No git repository. Set FLYSIGHT_VERSION to the firmware version." >&2
  exit 1
fi

# Same as prebuild.sh: write the firmware version from git
GIT_TAG=${FLYSIGHT_VERSION:-$(git describe --tags --always)}
printf '#ifndef VERSION_H\n#define VERSION_H\n\n#define GIT_TAG "%s"\n\n#endif // VERSION_H\n' \
  "$GIT_TAG" > FlySight/version.h
if [ "$HAVE_GIT" = 1 ] && [ -n "$(git status --porcelain --untracked-files=no)" ]; then
  echo "Note: the working tree has uncommitted changes. The firmware version is $GIT_TAG." >&2
fi

ARCH="-mcpu=cortex-m4 -mthumb -mfpu=fpv4-sp-d16 -mfloat-abi=hard"
CFLAGS="$ARCH -std=gnu11 -Os -g0 -DUSE_HAL_DRIVER -DSTM32WB5Mxx \
  -ffunction-sections -fdata-sections -Wall --specs=nano.specs"

# Linker options from the "Release" configuration in .cproject
LDEXTRA=""
if awk '/name="Release"/,/<\/configuration>/' .cproject | grep 'nanoprintffloat[^>]*value="true"' >/dev/null; then
  LDEXTRA="$LDEXTRA -u _printf_float"
fi

INC=""
for d in Core/Inc Drivers/BSP Drivers/STM32WBxx_HAL_Driver/Inc Drivers/STM32WBxx_HAL_Driver/Inc/Legacy \
  Drivers/CMSIS/Device/ST/STM32WBxx/Include Drivers/CMSIS/Include FATFS/Target FATFS/App \
  STM32_WPAN/App USB_Device/App USB_Device/Target Utilities/lpm/tiny_lpm Utilities/sequencer \
  Middlewares/Third_Party/FatFs/src Middlewares/ST/STM32_WPAN Middlewares/ST/STM32_WPAN/ble \
  Middlewares/ST/STM32_WPAN/interface/patterns/ble_thread \
  Middlewares/ST/STM32_WPAN/interface/patterns/ble_thread/tl \
  Middlewares/ST/STM32_WPAN/interface/patterns/ble_thread/shci \
  Middlewares/ST/STM32_WPAN/utilities Middlewares/ST/STM32_WPAN/ble/core \
  Middlewares/ST/STM32_WPAN/ble/core/auto Middlewares/ST/STM32_WPAN/ble/core/template \
  Middlewares/ST/STM32_WPAN/ble/svc/Inc Middlewares/ST/STM32_WPAN/ble/svc/Src \
  Middlewares/ST/STM32_USB_Device_Library/Core/Inc \
  Middlewares/ST/STM32_USB_Device_Library/Class/MSC/Inc FlySight; do
  INC="$INC -I$REPO/$d"
done

# Same source folders as .cproject. Tracked and new (not ignored) files.
SRC_DIRS="USB_Device STM32_WPAN Core FlySight Utilities FATFS Middlewares Drivers"
if [ "$HAVE_GIT" = 1 ]; then
  PATHSPECS=()
  for d in $SRC_DIRS; do PATHSPECS+=("$d/*.c"); done
  SRCS=$(git ls-files -co --exclude-standard "${PATHSPECS[@]}")
else
  # shellcheck disable=SC2086
  SRCS=$(find $SRC_DIRS -name '*.c' | LC_ALL=C sort)
fi

rm -rf "$OUT/obj"
mkdir -p "$OUT/obj"
: > "$OUT/warnings.txt"
export CFLAGS INC OUT

echo "Compiling $(echo "$SRCS" | wc -l | tr -d ' ') files..."
# shellcheck disable=SC2016
echo "$SRCS" | xargs -P "$JOBS" -n 1 sh -c '
  mkdir -p "$OUT/obj/$(dirname "$1")"
  arm-none-eabi-gcc $CFLAGS $INC -c "$1" -o "$OUT/obj/$1.o" 2>>"$OUT/warnings.txt" \
    || { echo "FAILED: $1" >&2; exit 255; }
' sh

arm-none-eabi-gcc $ARCH -c -x assembler-with-cpp Core/Startup/startup_stm32wb5mmghx.s \
  -o "$OUT/obj/startup.o"

# shellcheck disable=SC2086
arm-none-eabi-gcc $ARCH --specs=nano.specs --specs=nosys.specs $LDEXTRA -T STM32WB5MMGHX_FLASH.ld \
  -Wl,--gc-sections -Wl,-Map="$OUT/UserApp.map" -o "$OUT/UserApp.elf" \
  $(find "$OUT/obj" -name '*.o' | LC_ALL=C sort) -lc -lm

arm-none-eabi-objcopy -O binary "$OUT/UserApp.elf" "$OUT/UserApp.bin"
arm-none-eabi-size "$OUT/UserApp.elf"

echo "Warnings: $(grep -c 'warning:' "$OUT/warnings.txt" || true) (see $OUT/warnings.txt)"
echo "Firmware version: $GIT_TAG"
echo "Result: $OUT/UserApp.bin"

# ---- Encrypted update file ----

# An old APP.SFB does not agree with the new UserApp.bin, so delete it
rm -f "$OUT/APP.SFB"

if [ -z "$BATCH" ] && [ -z "$FLYSIGHT_TXT" ]; then
  if [ "$NO_SFB" = 1 ]; then
    echo "No APP.SFB (--no-sfb)."
  else
    echo "To make APP.SFB, add --batch NAME or --flysight-txt FILE. The script then remembers the batch."
  fi
  exit 0
fi

VENV=$CACHE/venv
if ! "$VENV/bin/python" -c "import coincurve, Crypto" 2>/dev/null; then
  echo "Making the Python environment in $VENV..."
  rm -rf "$VENV"
  mkdir -p "$(dirname "$VENV")"
  # coincurve must have a package for this Python version (for example PYTHON=python3.12)
  "${PYTHON:-python3}" -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --disable-pip-version-check coincurve pycryptodome
fi

if [ -n "$BATCH" ]; then
  SFB_OPTION=(--batch "$BATCH")
else
  SFB_OPTION=(--flysight-txt "$FLYSIGHT_TXT")
fi
SFB_LOG=$(mktemp)
trap 'rm -f "$SFB_LOG"' EXIT
"$VENV/bin/python" "$REPO/Scripts/make_sfb.py" "$OUT/UserApp.bin" "$OUT/APP.SFB" "${SFB_OPTION[@]}" \
  | tee "$SFB_LOG"

# Remember the batch for the next build. make_sfb.py writes "Batch: NAME (key file)".
USED_BATCH=$(sed -n 's/^Batch: \([A-Za-z0-9_]*\) .*/\1/p' "$SFB_LOG" | head -n 1)
if [ -n "$USED_BATCH" ]; then
  mkdir -p "$CACHE"
  echo "$USED_BATCH" > "$BATCH_FILE"
  echo "Remembered batch $USED_BATCH for the next build."
fi
echo "Copy APP.SFB to the FW folder of the FlySight. See Docs/firmware-update-and-recovery.md."
