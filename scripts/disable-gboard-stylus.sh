#!/usr/bin/env bash
# Disables Gboard's "Use stylus to write in text fields" feature on a connected Android device.
# Run once after creating a new emulator, before running Appium tests.
#
# Usage:
#   ./scripts/disable-gboard-stylus.sh
#   ANDROID_UDID=192.168.80.1:5555 ./scripts/disable-gboard-stylus.sh

set -euo pipefail

ADB="${ADB:-adb}"
SERIAL="${ANDROID_UDID:-}"
if [[ -n "$SERIAL" ]]; then
    ADB_SERIAL=(-s "$SERIAL")
else
    ADB_SERIAL=()
fi
TMPFILE="$(mktemp /tmp/ui_dump_XXXXXX.xml)"

cleanup() { rm -f "$TMPFILE"; }
trap cleanup EXIT

adb_cmd() { "$ADB" "${ADB_SERIAL[@]}" "$@"; }

dump_ui() {
    adb_cmd shell uiautomator dump /sdcard/ui_temp.xml > /dev/null
    adb_cmd pull /sdcard/ui_temp.xml "$TMPFILE" > /dev/null 2>&1
}

# Returns "cx cy" for the first element matching the given text, or exits 1.
center_of_text() {
    local text="$1"
    local bounds
    bounds=$(grep -oP "text=\"${text}\"[^>]* bounds=\"(\\[[^\\]]*\\]){2}\"" "$TMPFILE" | \
             grep -oP 'bounds="\[[^\]]*\]\[[^\]]*\]"' | head -1) || true
    if [[ -z "$bounds" ]]; then
        return 1
    fi
    local x1 y1 x2 y2
    x1=$(echo "$bounds" | grep -oP '(?<=\[)\d+(?=,)' | head -1)
    y1=$(echo "$bounds" | grep -oP '(?<=,)\d+(?=\])' | head -1)
    x2=$(echo "$bounds" | grep -oP '(?<=\[)\d+(?=,)' | tail -1)
    y2=$(echo "$bounds" | grep -oP '(?<=,)\d+(?=\])' | tail -1)
    echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}

# Returns the center of the Switch widget that is checked (or unchecked).
center_of_switch() {
    local bounds
    bounds=$(grep -oP 'class="android\.widget\.Switch"[^>]* bounds="(\[[^\]]*\]){2}"' "$TMPFILE" | \
             grep -oP 'bounds="\[[^\]]*\]\[[^\]]*\]"' | head -1) || true
    if [[ -z "$bounds" ]]; then
        return 1
    fi
    local x1 y1 x2 y2
    x1=$(echo "$bounds" | grep -oP '(?<=\[)\d+(?=,)' | head -1)
    y1=$(echo "$bounds" | grep -oP '(?<=,)\d+(?=\])' | head -1)
    x2=$(echo "$bounds" | grep -oP '(?<=\[)\d+(?=,)' | tail -1)
    y2=$(echo "$bounds" | grep -oP '(?<=,)\d+(?=\])' | tail -1)
    echo "$(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))"
}

click_text() {
    local text="$1"
    dump_ui
    local pos
    if ! pos=$(center_of_text "$text"); then
        echo "ERROR: element not found: '$text'" >&2
        return 1
    fi
    # shellcheck disable=SC2086
    adb_cmd shell input tap $pos
    sleep 1
}

is_stylus_enabled() {
    dump_ui
    grep -q 'text="Use stylus to write in text fields"' "$TMPFILE" && \
    grep -q 'class="android.widget.Switch"[^>]*checked="true"' "$TMPFILE"
}

echo "Opening Gboard settings..."
adb_cmd shell am start -a android.settings.INPUT_METHOD_SETTINGS > /dev/null
sleep 2

echo "Navigating to Gboard..."
click_text "Gboard"

echo "Opening 'Write in text fields'..."
dump_ui
if ! center_of_text "Write in text fields" > /dev/null 2>&1; then
    echo "  Scrolling to find 'Write in text fields'..."
    adb_cmd shell input swipe 540 1400 540 600 500
    sleep 1
fi
click_text "Write in text fields"

echo "Checking stylus toggle state..."
if is_stylus_enabled; then
    echo "Disabling 'Use stylus to write in text fields'..."
    dump_ui
    pos=$(center_of_switch)
    # shellcheck disable=SC2086
    adb_cmd shell input tap $pos
    sleep 1
    if is_stylus_enabled; then
        echo "ERROR: toggle is still enabled after clicking" >&2
        exit 1
    fi
    echo "Done: stylus handwriting is now disabled."
else
    echo "Already disabled, no action needed."
fi
