#!/usr/bin/env python3
"""
Make an encrypted FlySight 2 firmware update file (APP.SFB) from UserApp.bin.

The encryption uses Deploy/deploy_firmware.py without changes. This is the
same code that makes the official firmware files.

Select the hardware batch with one of these options:
  --batch B7               Use Deploy/Public_Keys/pub_key_b7.bin.
  --flysight-txt FILE      Find the batch from Pubkey_X in FLYSIGHT.TXT.

Needs the Python packages coincurve and pycryptodome.
"""

import argparse
import os
import re
import sys
import tempfile

sys.dont_write_bytecode = True  # Do not write __pycache__ into Deploy/

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPLOY = os.path.join(REPO, "Deploy")
KEYS = os.path.join(DEPLOY, "Public_Keys")
SFB_HEADER_SIZE = 512
B1_PUBKEY_X = "f" * 64


def fail(message):
    print(f"make_sfb: {message}", file=sys.stderr)
    sys.exit(1)


def batch_keys():
    """Return {batch: (key file, Pubkey_X)} for all key files."""
    keys = {}
    for name in sorted(os.listdir(KEYS)):
        m = re.fullmatch(r"pub_key_(\w+)\.bin", name)
        if not m:
            continue
        path = os.path.join(KEYS, name)
        with open(path, "rb") as f:
            data = f.read()
        # Uncompressed SEC1 point: 0x04 || X (32 bytes) || Y (32 bytes)
        if len(data) != 65 or data[0] != 0x04:
            fail(f"{path} is not an uncompressed public key")
        keys[m.group(1).upper()] = (path, data[1:33].hex())
    return keys


def pubkey_x_from_flysight_txt(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.match(r"\s*Pubkey_X\s*:\s*([0-9a-fA-F]{64})", line)
            if m:
                return m.group(1).lower()
    fail(f"no Pubkey_X in {path}. The firmware on the device is older than v2023.09.22.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("firmware", help="UserApp.bin")
    parser.add_argument("output", help="output file, for example Release/APP.SFB")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--batch", help="hardware batch, for example B7")
    group.add_argument("--flysight-txt", help="FLYSIGHT.TXT of the target device")
    args = parser.parse_args()

    keys = batch_keys()
    known = ", ".join(keys)

    if args.batch:
        batch = args.batch.upper()
        if batch == "B1":
            fail("batch B1 has no published public key. A custom firmware is not possible.")
        if batch not in keys:
            fail(f"unknown batch {args.batch}. Known batches: {known}")
    else:
        pubkey_x = pubkey_x_from_flysight_txt(args.flysight_txt)
        if pubkey_x == B1_PUBKEY_X:
            fail("the device is from batch B1. Its public key is not published. A custom firmware is not possible.")
        matches = [b for b, (_, x) in keys.items() if x == pubkey_x]
        if not matches:
            fail(f"Pubkey_X {pubkey_x} is not in {KEYS}. Get the new key file from the upstream develop branch.")
        batch = matches[0]
        print(f"Batch from {args.flysight_txt}: {batch}")

    key_file = keys[batch][0]

    try:
        sys.path.insert(0, DEPLOY)
        from deploy_firmware import encrypt_firmware_with_key
    except ImportError as e:
        fail(f"{e}. Install the packages coincurve and pycryptodome.")

    with tempfile.TemporaryDirectory() as tmp:
        encrypt_firmware_with_key(args.firmware, "custom", key_file, batch, output_dir=tmp)
        with open(os.path.join(tmp, f"{batch}_custom.sfb"), "rb") as f:
            sfb = f.read()

    # Check the result before it is used on a device
    fw_size = os.path.getsize(args.firmware)
    if not sfb.startswith(b"SFU1") or len(sfb) != fw_size + SFB_HEADER_SIZE:
        fail("the result does not have the expected SFB format")

    with open(args.output, "wb") as f:
        f.write(sfb)
    print(f"Batch: {batch} ({os.path.relpath(key_file, REPO)})")
    print(f"Result: {args.output} ({len(sfb)} bytes)")


if __name__ == "__main__":
    main()
