# Local patches to vendored libpict

We vendor `petabyt/libpict` (Apache-2.0) under `app/src/main/cpp/libpict/` and
normally do **not** modify it — app-specific quirks live in `app/src/main/cpp/ptp_jni.c`.
The exceptions below are genuine upstream bugs that block the plain PTP/IP (Wi-Fi)
path this app depends on. Each is marked with a `LOCAL PATCH (canon-wireless-raw)`
comment in the source. Re-apply after any libpict update; consider upstreaming.

## 1. `src/transport.c` — `ptp_send_packet` missing the `PTP_IP` case

**Symptom:** On a real Canon EOS R5 over Wi-Fi, the first `ptp_open_session` after a
successful PTP/IP handshake aborted the whole app with `SIGABRT`
(`ptp_panic("illegal connection_type") -> abort()`), backtrace
`ptp_open_session -> ptp_send -> ptp_send_packet -> ptp_panic`.

**Cause:** `ptp_send_packet()` branched only on `PTP_USB` and `PTP_IP_USB`
(Fujifilm's TCP-with-USB-framing mode); the plain `PTP_IP` mode — which
`ptp_new(PTP_IP)` selects and which the rest of libpict (packet.c, lib.c:234)
handles correctly — fell into the `else` and called `ptp_panic()`. The bundled
`examples/wifi.c` uses exactly this path, so it was evidently never run against a
camera to the point of opening a session.

**Fix:** treat `PTP_IP` like `PTP_IP_USB` for byte transport (both use
`ptpip_cmd_write`); packet framing already diverges by `connection_type` elsewhere.
One-line change to the `else if` condition.
