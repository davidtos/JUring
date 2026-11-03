#!/usr/bin/env bash
# log_drive_state.sh: Logs NVMe temperature, throttle counters, and filesystem fill level
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 <out_file> [data_dir]" >&2; exit 1; fi
OUT_FILE="$1"; DATA_DIR="${2:-${JURING_DATA_DIR:-/home/david/testData}}"
mkdir -p "$(dirname "$OUT_FILE")"
MNT=$(findmnt -n -o TARGET --target "$DATA_DIR" 2>/dev/null || echo "/mnt/TestData")
DEV=$(findmnt -n -o SOURCE --target "$DATA_DIR" 2>/dev/null || echo "/dev/nvme1n1p1")
DF_LINE=$(df -h "$MNT" 2>/dev/null | tail -n 1 || echo "")
FILL_PCT=$(echo "$DF_LINE" | awk '{print $5}' | tr -d '%' || echo "0")
USED=$(echo "$DF_LINE" | awk '{print $3}' || echo "unknown")
AVAIL=$(echo "$DF_LINE" | awk '{print $4}' || echo "unknown")
TEMP_C="unknown"; CRIT_C="unknown"; ALARM="0"; THROTTLE_COUNT="unknown"; THROTTLE_TIME="unknown"
NVME_DEV="/dev/$(lsblk -no PKNAME "$DEV" 2>/dev/null || echo "nvme1")"
if command -v nvme &>/dev/null; then
    SMART_OUT=$(nvme smart-log "$NVME_DEV" 2>/dev/null || true)
    if [[ -n "$SMART_OUT" ]]; then
        TEMP_C=$(echo "$SMART_OUT" | awk '/^temperature/ {print $3}' | tr -d '[:alpha:]' || echo "unknown")
        THROTTLE_COUNT=$(echo "$SMART_OUT" | awk '/thm_temp1_trans_count/ {print $3}' || echo "0")
        THROTTLE_TIME=$(echo "$SMART_OUT" | awk '/thm_temp1_total_time/ {print $3}' || echo "0")
    fi
fi
if [[ "$TEMP_C" == "unknown" ]]; then
    for hwmon in /sys/class/hwmon/hwmon*; do
        if [[ -f "$hwmon/name" && $(cat "$hwmon/name") == "nvme" ]]; then
            if [[ -f "$hwmon/temp1_input" ]]; then
                RAW=$(cat "$hwmon/temp1_input")
                TEMP_C=$(awk -v r="$RAW" 'BEGIN {printf "%.1f", r / 1000.0}')
            fi
            if [[ -f "$hwmon/temp1_crit" ]]; then
                RAW_CRIT=$(cat "$hwmon/temp1_crit")
                CRIT_C=$(awk -v r="$RAW_CRIT" 'BEGIN {printf "%.1f", r / 1000.0}')
            fi
            if [[ -f "$hwmon/temp1_alarm" ]]; then
                ALARM=$(cat "$hwmon/temp1_alarm")
            fi
            break
        fi
    done
fi
jq -n --arg timestamp "$(date -Iseconds)" --arg mnt "$MNT" --arg dev "$DEV" --arg fill "$FILL_PCT" --arg used "$USED" --arg avail "$AVAIL" --arg temp "$TEMP_C" --arg crit "$CRIT_C" --arg alarm "$ALARM" --arg thm_count "$THROTTLE_COUNT" --arg thm_time "$THROTTLE_TIME" '{timestamp: $timestamp, mount_point: $mnt, device: $dev, filesystem_fill_percent: $fill, used_space: $used, available_space: $avail, temperature_c: $temp, temp_crit_c: $crit, temp_alarm: $alarm, thm_temp1_trans_count: $thm_count, thm_temp1_total_time: $thm_time}' > "$OUT_FILE"
echo "Logged drive state to $OUT_FILE (Temp: ${TEMP_C}°C, Fill: ${FILL_PCT}%)"
