#!/usr/bin/env bash
set -euo pipefail

OUT_FILE="${1:-env.json}"
DATA_DIR="${2:-/home/david/testData}"
if [[ ! -d "$DATA_DIR" ]]; then
    DATA_DIR="/mnt/TestData"
fi
if [[ ! -d "$DATA_DIR" ]]; then
    DATA_DIR="."
fi

DATE="$(date +%F)"
TIMESTAMP="$(date -Iseconds)"
UNAME_A="$(uname -a)"
KERNEL="$(uname -r)"

# CPU Model
CPU_MODEL="unknown"
if [[ -f /proc/cpuinfo ]]; then
    CPU_MODEL="$(grep -m1 'model name' /proc/cpuinfo | sed 's/model name[ 	]*:[ 	]*//' || echo 'unknown')"
fi
if [[ "$CPU_MODEL" == "unknown" ]] && command -v lscpu &>/dev/null; then
    CPU_MODEL="$(lscpu | grep 'Model name' | sed 's/Model name:[ 	]*//' || echo 'unknown')"
fi

# CPU Cores
CPU_CORES="$(nproc 2>/dev/null || echo 1)"

# Governor
CPU_GOVERNOR="unknown"
if [[ -f /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor ]]; then
    CPU_GOVERNOR="$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor)"
fi

# SMT
SMT="unknown"
if [[ -f /sys/devices/system/cpu/smt/active ]]; then
    SMT_VAL="$(cat /sys/devices/system/cpu/smt/active)"
    if [[ "$SMT_VAL" == "1" ]]; then
        SMT="enabled"
    elif [[ "$SMT_VAL" == "0" ]]; then
        SMT="disabled"
    fi
fi

# RAM
RAM="unknown"
if [[ -f /proc/meminfo ]]; then
    RAM="$(grep -i 'MemTotal:' /proc/meminfo | awk '{print $2, $3}' || echo 'unknown')"
fi

# Filesystem & Mount Info
MOUNT_DEVICE="unknown"
FILESYSTEM="unknown"
MOUNT_OPTIONS="unknown"
MOUNT_POINT="unknown"

if command -v findmnt &>/dev/null; then
    FMINFO="$(findmnt -n -o SOURCE,FSTYPE,OPTIONS,TARGET --target "$DATA_DIR" 2>/dev/null || true)"
    if [[ -n "$FMINFO" ]]; then
        MOUNT_DEVICE="$(echo "$FMINFO" | awk '{print $1}')"
        FILESYSTEM="$(echo "$FMINFO" | awk '{print $2}')"
        MOUNT_OPTIONS="$(echo "$FMINFO" | awk '{print $3}')"
        MOUNT_POINT="$(echo "$FMINFO" | awk '{print $4}')"
    fi
fi

# NVMe Model & Firmware
NVME_MODEL="unknown"
NVME_FIRMWARE="unknown"
if [[ "$MOUNT_DEVICE" != "unknown" ]]; then
    DEV_NAME="$(basename "$MOUNT_DEVICE")"
    PARENT_DEV="$(echo "$DEV_NAME" | sed -E 's/p[0-9]+$//')"
    if [[ -f "/sys/block/$PARENT_DEV/device/model" ]]; then
        NVME_MODEL="$(cat "/sys/block/$PARENT_DEV/device/model" | xargs)"
    fi
    if [[ -f "/sys/block/$PARENT_DEV/device/firmware_rev" ]]; then
        NVME_FIRMWARE="$(cat "/sys/block/$PARENT_DEV/device/firmware_rev" | xargs)"
    fi
fi

# Java Version
JAVA_VERSION="unknown"
if command -v java &>/dev/null; then
    JAVA_VERSION="$(java -version 2>&1 | head -n 1)"
fi

# fio Version
FIO_VERSION="unknown"
if command -v fio &>/dev/null; then
    FIO_VERSION="$(fio --version 2>/dev/null || echo 'unknown')"
fi

# jq Version
JQ_VERSION="unknown"
if command -v jq &>/dev/null; then
    JQ_VERSION="$(jq --version 2>/dev/null || echo 'unknown')"
fi

# perf Version & perf_event_paranoid
PERF_VERSION="unknown"
if command -v perf &>/dev/null; then
    PERF_VERSION="$(perf --version 2>/dev/null || echo 'unknown')"
fi
PERF_PARANOID="unknown"
if [[ -f /proc/sys/kernel/perf_event_paranoid ]]; then
    PERF_PARANOID="$(cat /proc/sys/kernel/perf_event_paranoid)"
fi

# HZ and Accounting config
HZ_CONFIG="unknown"
BOOT_CONFIG="/boot/config-$KERNEL"
if [[ -f "$BOOT_CONFIG" ]]; then
    HZ_VAL="$(grep -E '^CONFIG_HZ=[0-9]+' "$BOOT_CONFIG" || true)"
    VIRT_VAL="$(grep -E '^CONFIG_VIRT_CPU_ACCOUNTING_GEN=y' "$BOOT_CONFIG" || true)"
    HZ_CONFIG="${HZ_VAL:-unknown}${VIRT_VAL:+, $VIRT_VAL}"
elif [[ -f /proc/config.gz ]] && command -v zgrep &>/dev/null; then
    HZ_VAL="$(zgrep -E '^CONFIG_HZ=[0-9]+' /proc/config.gz || true)"
    VIRT_VAL="$(zgrep -E '^CONFIG_VIRT_CPU_ACCOUNTING_GEN=y' /proc/config.gz || true)"
    HZ_CONFIG="${HZ_VAL:-unknown}${VIRT_VAL:+, $VIRT_VAL}"
fi

CMDLINE="$(cat /proc/cmdline 2>/dev/null || echo 'unknown')"

jq -n \
  --arg date "$DATE" \
  --arg timestamp "$TIMESTAMP" \
  --arg uname "$UNAME_A" \
  --arg kernel "$KERNEL" \
  --arg cpu_model "$CPU_MODEL" \
  --argjson cpu_cores "$CPU_CORES" \
  --arg cpu_governor "$CPU_GOVERNOR" \
  --arg smt "$SMT" \
  --arg ram "$RAM" \
  --arg nvme_model "$NVME_MODEL" \
  --arg nvme_firmware "$NVME_FIRMWARE" \
  --arg filesystem "$FILESYSTEM" \
  --arg mount_point "$MOUNT_POINT" \
  --arg mount_device "$MOUNT_DEVICE" \
  --arg mount_options "$MOUNT_OPTIONS" \
  --arg java_version "$JAVA_VERSION" \
  --arg fio_version "$FIO_VERSION" \
  --arg jq_version "$JQ_VERSION" \
  --arg perf_version "$PERF_VERSION" \
  --arg perf_event_paranoid "$PERF_PARANOID" \
  --arg hz_config "$HZ_CONFIG" \
  --arg cmdline "$CMDLINE" \
  --arg perfnorm_events "task-clock,cycles,instructions,cycles:u,cycles:k" \
  --arg syscall_events "raw_syscalls:sys_enter,syscalls:sys_enter_io_uring_enter,syscalls:sys_enter_pread64,syscalls:sys_enter_pwrite64,syscalls:sys_enter_futex" \
  '{
    date: $date,
    timestamp: $timestamp,
    uname: $uname,
    kernel: $kernel,
    cpu_model: $cpu_model,
    cpu_cores: $cpu_cores,
    cpu_governor: $cpu_governor,
    smt: $smt,
    ram: $ram,
    nvme_model: $nvme_model,
    nvme_firmware: $nvme_firmware,
    filesystem: $filesystem,
    mount_point: $mount_point,
    mount_device: $mount_device,
    mount_options: $mount_options,
    java_version: $java_version,
    fio_version: $fio_version,
    jq_version: $jq_version,
    perf_version: $perf_version,
    perf_event_paranoid: $perf_event_paranoid,
    hz_config: $hz_config,
    cmdline: $cmdline,
    perfnorm_events: $perfnorm_events,
    syscall_events: $syscall_events
  }' > "$OUT_FILE"
