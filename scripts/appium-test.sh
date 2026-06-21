#!/bin/sh
set -e

isVmwareEnvironment() {
    if command -v systemd-detect-virt >/dev/null 2>&1 && systemd-detect-virt --quiet --vm && [ "$(systemd-detect-virt 2>/dev/null)" = "vmware" ]; then
        return 0
    fi
    if [ -r /sys/class/dmi/id/product_name ] && grep -qi 'vmware' /sys/class/dmi/id/product_name; then
        return 0
    fi
    return 1
}

vmwareHostIp() {
    command -v ip >/dev/null 2>&1 || return 1
    local_ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{ for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit } }')
    [ -z "$local_ip" ] && local_ip=$(ip -4 addr show scope global 2>/dev/null | awk '/inet / { sub(/\/.*/, "", $2); print $2; exit }')
    [ -z "$local_ip" ] && return 1
    echo "$local_ip" | awk -F. 'NF == 4 { print $1 "." $2 "." $3 ".1" }'
}

findEmulatorSerials() {
    for device_serial in $(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }'); do
        is_emulator=$(adb -s "$device_serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')
        if [ "$is_emulator" = "1" ]; then
            echo "$device_serial"
            return 0
        fi
    done
    return 1
}

tryConnectHostEmulator() {
    host=$(vmwareHostIp) || return 1
    echo "VMware environment detected; trying host emulator via adb connect $host..."
    for port in 5555 5557 5559 5561 5563 5565; do
        target="$host:$port"
        connect_output=$(adb connect "$target" 2>&1 || true)
        echo "  adb connect $target: $connect_output"
        if findEmulatorSerials >/dev/null 2>&1; then
            return 0
        fi
    done
    return 1
}

checkEmulatorReady() {
    if ! command -v adb >/dev/null 2>&1; then
        echo "ERROR: adb is not available."
        exit 1
    fi

    emulator_serial=$(findEmulatorSerials 2>/dev/null || true)

    if [ -z "$emulator_serial" ]; then
        if isVmwareEnvironment && tryConnectHostEmulator; then
            emulator_serial=$(findEmulatorSerials 2>/dev/null || true)
        fi
        if [ -z "$emulator_serial" ]; then
            echo "ERROR: No running Android emulator found."
            isVmwareEnvironment && echo "VMware environment detected, but no host emulator accepted adb connections."
            exit 1
        fi
    fi

    boot_completed=$(adb -s "$emulator_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    if [ "$boot_completed" != "1" ]; then
        echo "ERROR: Emulator $emulator_serial has not completed boot."
        exit 1
    fi

    echo "Emulator ready: $emulator_serial"
    EMULATOR_SERIAL="$emulator_serial"
}

checkEmulatorReady

echo "Installing debug APK..."
ANDROID_SERIAL="$EMULATOR_SERIAL" ./gradlew :androidApp:installDebug "$@"

echo "Running Appium tests..."
ANDROID_UDID="$EMULATOR_SERIAL" ./gradlew :appiumTests:test "$@"
