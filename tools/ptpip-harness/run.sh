#!/bin/sh
# Builds the real native PTP code for macOS and runs every scenario against fake_camera.py.
set -eu
H="$(cd "$(dirname "$0")" && pwd)"
REPO="${REPO:-$(cd "$H/../.." && pwd)}"
CPP="$REPO/app/src/main/cpp"
JDK="${JDK:-${JAVA_HOME:?set JAVA_HOME to a JDK (for jni.h)}}"
L="$CPP/libpict/src"
OUT="$(mktemp -d)"
cc -O1 -w -c -o "$OUT/ip.o" -Dread=bionic_read -I"$L" "$L/ip.c"
cc -O1 -w -o "$OUT/driver" -I"$H/stub" -I"$L" -I"$JDK/include" -I"$JDK/include/$(uname | tr "[:upper:]" "[:lower:]")" \
  "$H/driver.c" "$CPP/ptp_jni.c" \
  "$L/operations.c" "$L/packet.c" "$L/enums.c" "$L/data.c" "$L/enum_dump.c" "$L/lib.c" \
  "$L/canon.c" "$L/liveview.c" "$L/ml.c" "$L/conv.c" "$L/generic.c" "$L/transport.c" \
  "$OUT/ip.o" "$H/bionic_read.c" "$L/log.c" "$L/no_usb.c" "$L/canon_adv.c" -lpthread

run() {
  scenario="$1"; shift
  port=$((20000 + $$ % 1000 + ${#scenario}))
  python3 -I "$H/fake_camera.py" "$port" "$scenario" >/dev/null 2>&1 &
  cam=$!
  sleep 0.5
  echo "== $scenario: $*"
  "$OUT/driver" "$port" "$@" 2>/dev/null || true
  kill "$cam" 2>/dev/null || true
  wait "$cam" 2>/dev/null || true
}

run ok                connect open partial partial disconnect
run eof_mid_packet    connect open partial disconnect
run stall_mid_packet  connect open partial disconnect
run silent_init       connect
run slow_once         connect open partial partial
run init_fail_n:1     connect connect open partial
run slow_once         connect open partial partial disconnect connect open partial
run eof_mid_packet    disconnect connect open partial disconnect connect open
