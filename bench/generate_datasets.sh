#!/usr/bin/env bash
# Generates non-sparse, 4KB-aligned benchmark datasets for JUring and FileChannel benchmarks.
set -euo pipefail

DATA_DIR="${JURING_DATA_DIR:-./bench-data}"
NUM_FILES=100
FILE_SIZE_MB=4
SINGLE_FILE_GB=1

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dir)
            DATA_DIR="$2"
            shift 2
            ;;
        --files)
            NUM_FILES="$2"
            shift 2
            ;;
        --file-size-mb)
            FILE_SIZE_MB="$2"
            shift 2
            ;;
        --single-file-gb)
            SINGLE_FILE_GB="$2"
            shift 2
            ;;
        --full)
            NUM_FILES=2211
            FILE_SIZE_MB=4
            SINGLE_FILE_GB=10
            shift
            ;;
        --quick)
            NUM_FILES=50
            FILE_SIZE_MB=1
            SINGLE_FILE_GB=1
            shift
            ;;
        --help|-h)
            echo "Usage: $0 [options]"
            echo "  --dir <path>          Output directory (default: ./bench-data or \$JURING_DATA_DIR)"
            echo "  --files <N>           Number of multi-files to create (default: 100)"
            echo "  --file-size-mb <N>    Size per multi-file in MB (default: 4)"
            echo "  --single-file-gb <N>  Size of single_file_bench.bin in GB (default: 1)"
            echo "  --full                Full publication dataset (2211 files @ 4MB, 10GB single file)"
            echo "  --quick               Quick smoke dataset (50 files @ 1MB, 1GB single file)"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

READ_DIR="$DATA_DIR/text_files"
WRITE_DIR="$DATA_DIR/write_files"

mkdir -p "$READ_DIR" "$WRITE_DIR"

echo "=================================================================================="
echo "                   JUring Benchmark Dataset Generator                            "
echo "=================================================================================="
echo " Data Directory:      $DATA_DIR"
echo " Multi-Files (Read):  $NUM_FILES files x ${FILE_SIZE_MB}MB in $READ_DIR"
echo " Single-File (Write): ${SINGLE_FILE_GB}GB in $WRITE_DIR/single_file_bench.bin"
echo " Non-sparse content:  Pseudorandom byte patterns, 4KB sector aligned"
echo "----------------------------------------------------------------------------------"

python3 - <<EOF
import os
import sys

read_dir = "$READ_DIR"
write_dir = "$WRITE_DIR"
num_files = int("$NUM_FILES")
file_size_mb = int("$FILE_SIZE_MB")
single_file_gb = float("$SINGLE_FILE_GB")

# Create a 4MB pseudorandom chunk
chunk_size = 4 * 1024 * 1024
chunk = os.urandom(chunk_size)

# 1. Generate multi-files in read_dir
file_bytes = file_size_mb * 1024 * 1024
print(f"Generating {num_files} multi-files ({file_size_mb} MB each)...", flush=True)
for i in range(1, num_files + 1):
    path = os.path.join(read_dir, f"file_{i:04d}.bin")
    with open(path, "wb") as f:
        written = 0
        while written < file_bytes:
            to_write = min(chunk_size, file_bytes - written)
            f.write(chunk[:to_write])
            written += to_write

print(f"Successfully created {num_files} multi-files in {read_dir}.", flush=True)

# 2. Generate single_file_bench.bin in write_dir
single_bytes = int(single_file_gb * 1024 * 1024 * 1024)
single_path = os.path.join(write_dir, "single_file_bench.bin")
print(f"Generating single benchmark file ({single_file_gb:.1f} GB) at {single_path}...", flush=True)
with open(single_path, "wb") as f:
    written = 0
    while written < single_bytes:
        to_write = min(chunk_size, single_bytes - written)
        f.write(chunk[:to_write])
        written += to_write
    f.flush()
    os.fsync(f.fileno())

print(f"Successfully created {single_path} ({os.path.getsize(single_path)} bytes).", flush=True)
EOF

echo "----------------------------------------------------------------------------------"
echo "Dataset generation complete in $DATA_DIR."
echo "You can now run benchmarks pointing to this directory:"
echo "  ./bench.sh --data-dir $DATA_DIR --quick"
echo "=================================================================================="
