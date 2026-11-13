#!/usr/bin/env bash
#
# create-write-file.sh - create the dedicated, non-sparse 10 GiB O_DIRECT write
# dataset for the M1 write matrix (docs/HANDOVER.md section 7 Step 2).
#
# The write file must live on the SAME NVMe as the read dataset (/dev/nvme1n1p1,
# ext4).  In this sandbox it is reached through the writable bind mount:
#
#   /home/david/projects/JUring/nvme-data/write_files/single_file_bench_w.bin
#
# It is *never* the read file.  Safety rules enforced here:
#   * refuse to run if the target resolves to the read dataset
#     (/mnt/TestData/read_files/single_file_bench.bin) or any path below
#     /mnt/TestData/read_files;
#   * by default the target must be below <repo>/nvme-data (override with
#     --allow-any-path only if you know what you are doing);
#   * require ext4/xfs and a source device containing nvme1n1p1 (override with
#     --allow-other-device for the documented loopback fallback);
#   * check free space before allocating (size + 2 GiB headroom);
#   * never overwrite an existing file unless --force is given.
#
# What it does (real run):
#   1. mkdir -p the parent directory, probe that it is writable;
#   2. fallocate -l <size> <file>          (allocates unwritten extents)
#   3. dd if=/dev/zero of=<file> bs=1M count=<size/1MiB> oflag=direct \
#         iflag=fullblock conv=notrunc,fsync status=progress
#         (materialises the extents through O_DIRECT; non-sparse)
#   4. sync -f <file>                      (flush file data + metadata)
#   5. verify stat size + allocated blocks (non-sparse), report filefrag
#      extent count, free space, fs type/source, and a machine-readable
#      CREATE_WRITE_FILE_RESULT line.
#
# Idempotent: if the file already exists with the exact size and >=99% of the
# blocks allocated, the script skips the work and prints action=skipped.
#
# Usage:
#   bench/write/create-write-file.sh                 # create or verify 10 GiB
#   bench/write/create-write-file.sh --dry-run       # print commands, no I/O
#   bench/write/create-write-file.sh --force         # recreate from scratch
#
# Exit status: 0 on success/skip, 1 on verification/runtime failure,
# 2 on usage error.
#
set -euo pipefail

PROG=$(basename -- "${BASH_SOURCE[0]}")
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../.." && pwd)

READ_FILE=${READ_FILE:-/mnt/TestData/read_files/single_file_bench.bin}
WRITE_DIR=${WRITE_DIR:-$REPO_ROOT/nvme-data}
WRITE_FILE=${WRITE_FILE:-$WRITE_DIR/write_files/single_file_bench_w.bin}
SESSION_DIR=${SESSION_DIR:-$REPO_ROOT/bench/results/m1-2026-11-13-write}
SIZE_SPEC=10g
FORCE=0
DRY_RUN=0
ALLOW_ANY_PATH=0
ALLOW_OTHER_DEVICE=0

usage() {
    cat <<'EOF'
create-write-file.sh - create/verify the non-sparse 10 GiB O_DIRECT write file

Usage:
  bench/write/create-write-file.sh [options]

Options:
  --file PATH            write file path
                         (default <repo>/nvme-data/write_files/single_file_bench_w.bin)
  --dir DIR              write directory, used only when --file is not given
                         (default <repo>/nvme-data)
  --size SIZE            file size, integer with k/M/G/T suffix (default 10g = 10 GiB)
  --session-dir DIR      where to append state/write-file.tsv
                         (default <repo>/bench/results/m1-2026-11-13-write)
  --force                remove and recreate an existing (even correct) file
  --dry-run              print the exact commands and checks; write nothing
  --allow-any-path       allow a target outside <repo>/nvme-data (safety off)
  --allow-other-device   allow a filesystem/source other than ext4 on nvme1n1p1
                         (e.g. the documented loopback fallback)
  -h, --help             this text

Safety:
  * never touches the read dataset; refuses if the target is the read file or
    lives below /mnt/TestData/read_files;
  * refuses to overwrite without --force;
  * refuses a target that is not on the same NVMe unless --allow-other-device.

Machine-readable output: one CREATE_WRITE_FILE_RESULT line on stdout
(action=created|skipped|dry-run, size_bytes, allocated_bytes, non_sparse,
extents, fstype, source, free_bytes_before/after, elapsed_s).
EOF
}

die() { printf '%s: error: %s\n' "$PROG" "$*" >&2; exit 1; }
warn() { printf '%s: warning: %s\n' "$PROG" "$*" >&2; }

# parse_size STRING -> bytes on stdout.  Integer only; k/M/G/T, KiB/GiB accepted.
parse_size() {
    local raw=$1 lower num mult
    lower=$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')
    num=${raw%%[a-zA-Z]*}
    [[ "$num" =~ ^[0-9]+$ ]] || return 1
    case "$lower" in
        *t|*tb|*tib) mult=1099511627776 ;;
        *g|*gb|*gib) mult=1073741824 ;;
        *m|*mb|*mib) mult=1048576 ;;
        *k|*kb|*kib) mult=1024 ;;
        *)           mult=1 ;;
    esac
    echo $(( num * mult ))
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --file)              [[ $# -ge 2 ]] || die "--file needs a value"; WRITE_FILE=$2; shift 2 ;;
        --dir)               [[ $# -ge 2 ]] || die "--dir needs a value"; WRITE_DIR=$2; shift 2 ;;
        --size)              [[ $# -ge 2 ]] || die "--size needs a value"; SIZE_SPEC=$2; shift 2 ;;
        --session-dir)       [[ $# -ge 2 ]] || die "--session-dir needs a value"; SESSION_DIR=$2; shift 2 ;;
        --force)             FORCE=1; shift ;;
        --dry-run)           DRY_RUN=1; shift ;;
        --allow-any-path)    ALLOW_ANY_PATH=1; shift ;;
        --allow-other-device) ALLOW_OTHER_DEVICE=1; shift ;;
        -h|--help)           usage; exit 0 ;;
        *) printf '%s: error: unknown option %s (try --help)\n' "$PROG" "$1" >&2; exit 2 ;;
    esac
done

SIZE_BYTES=$(parse_size "$SIZE_SPEC") || die "cannot parse --size '$SIZE_SPEC'"
(( SIZE_BYTES > 0 )) || die "--size must be > 0"
(( SIZE_BYTES % 1048576 == 0 )) || die "--size must be a multiple of 1 MiB (got $SIZE_BYTES bytes)"

canon() { realpath -m -- "$1"; }
WF=$(canon "$WRITE_FILE")
RF=$(canon "$READ_FILE")
ND=$(canon "$WRITE_DIR")

# ---------------------------------------------------------------- safety gates
[[ "$WF" == "$RF" ]] && die "refusing: write file resolves to the read dataset ($RF)"
case "$WF" in
    /mnt/TestData/read_files/*) die "refusing: write file is below the read-only read dataset ($WF)" ;;
esac
if [[ "$WF" != "$ND"/* ]]; then
    if (( ALLOW_ANY_PATH )); then
        warn "write file is outside $ND ($WF); --allow-any-path given"
    else
        die "refusing: write file must be below $ND (got $WF); pass --allow-any-path to override"
    fi
fi

# nearest existing ancestor of the target (for df/findmnt before mkdir)
PARENT=$(dirname -- "$WF")
PROBE_DIR=$PARENT
while [[ ! -e "$PROBE_DIR" ]]; do
    PROBE_DIR=$(dirname -- "$PROBE_DIR")
done

# ---------------------------------------------------------------- environment
# Stacked mounts can show more than one line here; the last one is the topmost
# (= effective) mount, so take tail -n1 for every findmnt query.
FSTYPE=$(findmnt -no FSTYPE -T "$PROBE_DIR" 2>/dev/null | tail -n1 || true)
SOURCE=$(findmnt -no SOURCE -T "$PROBE_DIR" 2>/dev/null | tail -n1 || true)
MOUNT_OPTS=$(findmnt -no OPTIONS -T "$PROBE_DIR" 2>/dev/null | tail -n1 || true)
FREE_BEFORE=$(df -B1 --output=avail "$PROBE_DIR" 2>/dev/null | tail -n1 | tr -d ' ' || true)
[[ -n "$FREE_BEFORE" ]] || FREE_BEFORE=0

if (( ! ALLOW_OTHER_DEVICE )); then
    [[ "$FSTYPE" == "ext4" || "$FSTYPE" == "xfs" ]] ||
        die "refusing: write path is on '$FSTYPE' (want ext4/xfs); pass --allow-other-device to override"
    case "$SOURCE" in
        *nvme1n1p1*) : ;;
        *) die "refusing: write path source '$SOURCE' is not /dev/nvme1n1p1 (the data NVMe); pass --allow-other-device to override" ;;
    esac
fi

# ---------------------------------------------------------------- existing file
EXISTS=0
SKIP_ACTION=0
if [[ -e "$WF" ]]; then
    EXISTS=1
    read -r CUR_SIZE CUR_BLOCKS < <(stat -c '%s %b' -- "$WF")
    CUR_ALLOC=$(( CUR_BLOCKS * 512 ))
    if (( CUR_SIZE == SIZE_BYTES && CUR_ALLOC >= SIZE_BYTES * 99 / 100 )); then
        if (( FORCE == 0 )); then
            SKIP_ACTION=1
        fi
    elif (( FORCE == 0 )); then
        die "existing file is not the expected dataset (size=$CUR_SIZE want $SIZE_BYTES, allocated=$CUR_ALLOC); pass --force to recreate"
    fi
fi

if (( SKIP_ACTION && ! DRY_RUN )); then
    EXTENTS=$(filefrag -- "$WF" 2>/dev/null | sed 's/.*: //' || true)
    [[ -n "$EXTENTS" ]] || EXTENTS="unknown"
    mkdir -p -- "$SESSION_DIR/state"
    printf '%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        "action=skipped path=$WF size_bytes=$CUR_SIZE allocated_bytes=$CUR_ALLOC extents=$EXTENTS" \
        >>"$SESSION_DIR/state/write-file.tsv"
    printf 'CREATE_WRITE_FILE_RESULT action=skipped path=%s size_bytes=%d allocated_bytes=%d non_sparse=1 extents="%s" fstype=%s source=%s free_bytes_before=%s target_bytes=%d force=0\n' \
        "$WF" "$CUR_SIZE" "$CUR_ALLOC" "$EXTENTS" "${FSTYPE:-unknown}" "${SOURCE:-unknown}" "$FREE_BEFORE" "$SIZE_BYTES"
    exit 0
fi

(( FREE_BEFORE >= SIZE_BYTES + 2 * 1073741824 )) ||
    die "not enough free space: have ${FREE_BEFORE} bytes, need >= $(( SIZE_BYTES + 2 * 1073741824 )); refusing to create"

DD_COUNT=$(( SIZE_BYTES / 1048576 ))

if (( DRY_RUN )); then
    printf '# dry-run: create-write-file.sh\n'
    printf '#   write_file=%s\n' "$WF"
    printf '#   read_file=%s (never touched)\n' "$RF"
    printf '#   size_bytes=%d (%s) dd_count_1MiB=%d\n' "$SIZE_BYTES" "$SIZE_SPEC" "$DD_COUNT"
    printf '#   fstype=%s source=%s mount_options=%s free_bytes=%s\n' \
        "${FSTYPE:-unknown}" "${SOURCE:-unknown}" "${MOUNT_OPTS:-unknown}" "$FREE_BEFORE"
    if (( EXISTS )); then
        printf '#   existing file: size=%s allocated=%s force=%s\n' "$CUR_SIZE" "$CUR_ALLOC" "$FORCE"
    else
        printf '#   existing file: none\n'
    fi
    if (( SKIP_ACTION )); then
        printf '# would skip: existing file is already the expected non-sparse dataset (pass --force to recreate)\n'
        printf 'CREATE_WRITE_FILE_RESULT action=dry-run-skip path=%s size_bytes=%d target_bytes=%d force=0\n' \
            "$WF" "$CUR_SIZE" "$SIZE_BYTES"
        exit 0
    fi
    printf '# would run (real run executes these):\n'
    printf '%s\n' "mkdir -p -- $(printf '%q' "$PARENT")"
    if (( EXISTS && FORCE )); then
        printf '%s\n' "rm -f -- $(printf '%q' "$WF")"
    fi
    printf '%s\n' "fallocate -l $SIZE_BYTES -- $(printf '%q' "$WF")"
    printf '%s\n' "dd if=/dev/zero of=$(printf '%q' "$WF") bs=1M count=$DD_COUNT oflag=direct iflag=fullblock conv=notrunc,fsync status=progress"
    printf '%s\n' "sync -f $(printf '%q' "$WF")"
    printf '%s\n' "stat -c '%s %b' -- $(printf '%q' "$WF")   # verify size + allocated blocks"
    printf '%s\n' "filefrag -- $(printf '%q' "$WF")        # record extent count"
    printf 'CREATE_WRITE_FILE_RESULT action=dry-run path=%s size_bytes=%d non_sparse=expected_1 extents=not-measured fstype=%s source=%s free_bytes_before=%s target_bytes=%d\n' \
        "$WF" "$SIZE_BYTES" "${FSTYPE:-unknown}" "${SOURCE:-unknown}" "$FREE_BEFORE" "$SIZE_BYTES"
    exit 0
fi

# ---------------------------------------------------------------- writability probe
mkdir -p -- "$PARENT" || die "cannot create directory: $PARENT"
PROBE="$PARENT/.create-write-file.probe.$$"
if ! : >"$PROBE" 2>/dev/null; then
    die "write path is not writable: $PARENT"
fi
rm -f -- "$PROBE"

if (( EXISTS && FORCE )); then
    printf '%s: removing existing file (--force): %s\n' "$PROG" "$WF" >&2
    rm -f -- "$WF" || die "cannot remove existing file: $WF"
fi

START_S=$SECONDS
printf '%s: fallocate %d bytes -> %s\n' "$PROG" "$SIZE_BYTES" "$WF" >&2
fallocate -l "$SIZE_BYTES" -- "$WF" || die "fallocate failed (unsupported filesystem?); use a real allocated file, not a sparse one"

printf '%s: writing %d MiB of zeros with O_DIRECT (dd bs=1M oflag=direct, conv=notrunc,fsync) ...\n' \
    "$PROG" "$DD_COUNT" >&2
dd if=/dev/zero of="$WF" bs=1M count="$DD_COUNT" oflag=direct iflag=fullblock \
    conv=notrunc,fsync status=progress || die "dd O_DIRECT write failed"

printf '%s: fsync (sync -f) ...\n' "$PROG" >&2
sync -f -- "$WF" 2>/dev/null || sync
ELAPSED=$(( SECONDS - START_S ))

# ---------------------------------------------------------------- verification
read -r FIN_SIZE FIN_BLOCKS < <(stat -c '%s %b' -- "$WF")
FIN_ALLOC=$(( FIN_BLOCKS * 512 ))
(( FIN_SIZE == SIZE_BYTES )) || die "verification failed: size=$FIN_SIZE, want $SIZE_BYTES"
if (( FIN_ALLOC < SIZE_BYTES * 99 / 100 )); then
    die "verification failed: file is sparse/partly allocated (allocated=$FIN_ALLOC of $SIZE_BYTES)"
fi
EXTENTS=$(filefrag -- "$WF" 2>/dev/null | sed 's/.*: //' || true)
[[ -n "$EXTENTS" ]] || EXTENTS="unknown"
FREE_AFTER=$(df -B1 --output=avail "$PARENT" 2>/dev/null | tail -n1 | tr -d ' ' || true)
[[ -n "$FREE_AFTER" ]] || FREE_AFTER=0

mkdir -p -- "$SESSION_DIR/state"
printf '%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "action=created path=$WF size_bytes=$FIN_SIZE allocated_bytes=$FIN_ALLOC extents=$EXTENTS fstype=$FSTYPE source=$SOURCE free_bytes_before=$FREE_BEFORE free_bytes_after=$FREE_AFTER elapsed_s=$ELAPSED" \
    >>"$SESSION_DIR/state/write-file.tsv"

printf 'CREATE_WRITE_FILE_RESULT action=created path=%s size_bytes=%d allocated_bytes=%d non_sparse=1 extents="%s" fstype=%s source=%s free_bytes_before=%s free_bytes_after=%s target_bytes=%d elapsed_s=%d\n' \
    "$WF" "$FIN_SIZE" "$FIN_ALLOC" "$EXTENTS" "$FSTYPE" "$SOURCE" "$FREE_BEFORE" "$FREE_AFTER" "$SIZE_BYTES" "$ELAPSED"
exit 0
