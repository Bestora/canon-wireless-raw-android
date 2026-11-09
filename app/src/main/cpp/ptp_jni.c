#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>
#include <libpict.h>

#define LOG_TAG "PtpNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define RT(x) ((struct PtpRuntime *)(intptr_t)(x))

// ponytail: single connection at a time (PtpNative calls are Mutex-serialized
// upstream). A file-scope last-error is enough; swap for a per-runtime map only
// if this ever drives more than one camera concurrently.
static int g_last_error = 0;
static int set_err(int e) { g_last_error = e; return e; }

// --- teardown hooks required by libpict's lib.c ptp_close()/list free ---

// ptp_close() calls this. The PTP/IP backend (ip.c) allocates r->comm_priv in
// init_comm() the first time ptpip_connect() runs; a no-op here would leak it
// (Task 2 carry-over). ptpip_device_close() closes the cmd/event/video sockets
// (safe if already 0), then we free the calloc'd PtpCommPriv block itself.
void ptp_comm_deinit(struct PtpRuntime *r) {
    if (r == NULL || r->comm_priv == NULL) return;
    ptpip_device_close(r);
    free(r->comm_priv);
    r->comm_priv = NULL;
}

// No USB backend in this app; device list is never populated, so nothing to free.
void ptpusb_free_device_list_entry(void *ptr) { (void)ptr; }

// --- custom PTP/IP init command request carrying our pairing GUID ---

// Based on libpict/src/operations.c ptpip_init_command_request() (which hardcodes
// 0xffffffff GUIDs), with two corrections needed for real Canon cameras:
//   1. the caller's 16-byte pairing GUID replaces the hardcoded one, so the camera
//      re-recognizes this phone across pairings, and
//   2. the packet is sent at its TRUE variable length instead of sizeof(struct):
//      libpict's PtpIpInitPacket fixes device_name[8] and pins the version fields at
//      a fixed offset, so a real (UTF-16LE) device name gets truncated mid-string and
//      the version fields are never transmitted. Canon's init command request is
//      variable length: header(8) + GUID(16) + UTF-16LE name incl. null terminator +
//      version(4). We build it into r->data (~1 MB, ample) and send exactly that many
//      bytes. ACK handling is byte-for-byte the upstream logic.
// ptpip_cmd_read() returns 0 for EOF *and* for a SO_RCVTIMEO timeout, so 0 is a
// failure here, never "no data yet".
static int read_full(struct PtpRuntime *r, uint8_t *buf, uint32_t n) {
    for (uint32_t got = 0; got < n;) {
        int x = ptpip_cmd_read(r, buf + got, n - got);
        if (x <= 0) return -1;
        got += (uint32_t)x;
    }
    return 0;
}

// Returns 0 on ACK, PTP_CHECK_CODE when the camera answered INIT_FAIL (unknown GUID,
// confirmation pending on the camera), PTP_IO_ERR when it never gave a valid answer.
static int init_command_request_guid(struct PtpRuntime *r, const uint8_t *guid, const char *name) {
    size_t name_len = strlen(name);
    if (name_len > 32) {
        LOGE("init request: device name too long (%zu chars, max 32)", name_len);
        return PTP_RUNTIME_ERR; // reject, never truncate
    }

    // length(4) + type(4) + GUID(16) + UTF-16LE name incl. null terminator + version(4)
    uint32_t total = 8 + 16 + (uint32_t)(2 * (name_len + 1)) + 4;

    uint8_t *buf = (uint8_t *)r->data;
    memset(buf, 0, total);

    struct PtpIpInitPacket *p = (struct PtpIpInitPacket *)buf;
    p->length = total;
    p->type = PTPIP_INIT_COMMAND_REQ;

    // guid1..guid4 are 4 contiguous packed uint32 (ptp.h uses #pragma pack(1)),
    // i.e. exactly 16 bytes at offset 8. Copy the raw pairing bytes verbatim.
    memcpy(&p->guid1, guid, 16);

    // UTF-16LE name at offset 24 (== offsetof device_name), 2*(name_len+1) bytes; the
    // terminator's low byte comes from the memset above.
    ptp_write_unicode_string((char *)buf + 24, name);

    // Version 1.0 immediately after the name terminator. Byte order exactly as libpict's
    // struct initializes it: major_ver=0, minor_ver=1  ->  00 00 01 00 (LE u32 0x00010000).
    uint8_t *ver = buf + 24 + 2 * (name_len + 1);
    ver[0] = 0; ver[1] = 0; // major_ver (uint16 LE) = 0
    ver[2] = 1; ver[3] = 0; // minor_ver (uint16 LE) = 1

    if (ptpip_cmd_write(r, r->data, p->length) != (int)p->length) return PTP_IO_ERR;

    // Read the 8-byte header (length + type), then the rest of the answer. The
    // camera-supplied length is bounded first: <8 would underflow, >data_length
    // would overflow r->data.
    struct PtpIpHeader *hdr = (struct PtpIpHeader *)r->data;
    if (read_full(r, buf, 8)) return PTP_IO_ERR;
    if (hdr->length < 8 || hdr->length > r->data_length) return PTP_IO_ERR;
    if (read_full(r, buf + 8, hdr->length - 8)) return PTP_IO_ERR;

    if (hdr->type == PTPIP_INIT_FAIL) return PTP_CHECK_CODE;
    if (hdr->type != PTPIP_INIT_COMMAND_ACK) return PTP_IO_ERR;
    return 0;
}

// --- lifecycle ---

JNIEXPORT jlong JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_create(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)(intptr_t)ptp_new(PTP_IP);
}

JNIEXPORT void JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_destroy(JNIEnv *env, jobject thiz, jlong rt) {
    (void)env; (void)thiz;
    // ptp_close() -> ptp_comm_deinit() (sockets + comm_priv) + free(data) + free(r)
    ptp_close(RT(rt));
}

// --- connection: ptpip_connect + custom GUID handshake + event channel ---

JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_connect(JNIEnv *env, jobject thiz, jlong rt,
                                                      jstring ip, jint port,
                                                      jbyteArray guid, jstring name) {
    (void)thiz;
    struct PtpRuntime *r = RT(rt);
    if (r == NULL) return set_err(-1);

    if (guid == NULL || (*env)->GetArrayLength(env, guid) < 16) {
        LOGE("connect: GUID must be 16 bytes");
        return set_err(-5);
    }
    uint8_t guid_buf[16];
    (*env)->GetByteArrayRegion(env, guid, 0, 16, (jbyte *)guid_buf);

    const char *ip_c = (*env)->GetStringUTFChars(env, ip, NULL);
    const char *name_c = (*env)->GetStringUTFChars(env, name, NULL);
    if (ip_c == NULL || name_c == NULL) {
        if (ip_c) (*env)->ReleaseStringUTFChars(env, ip, ip_c);
        if (name_c) (*env)->ReleaseStringUTFChars(env, name, name_c);
        return set_err(-1);
    }

    int result = 0;
    int rc = ptpip_connect(r, ip_c, port, 0);
    if (rc) {
        LOGE("connect: ptpip_connect failed rc=%d", rc);
        result = -1;
        goto done;
    }
    rc = init_command_request_guid(r, guid_buf, name_c);
    if (rc) {
        LOGE("connect: init_command_request_guid failed rc=%d", rc);
        result = rc == PTP_CHECK_CODE ? -2 : -6;
        goto done;
    }
    rc = ptpip_connect_events(r, ip_c, port);
    if (rc) {
        LOGE("connect: ptpip_connect_events failed rc=%d", rc);
        result = -3;
        goto done;
    }
    rc = ptpip_init_events(r);
    if (rc) {
        LOGE("connect: ptpip_init_events failed rc=%d", rc);
        result = -4;
        goto done;
    }
    LOGI("connect: established to %s:%d", ip_c, (int)port);

done:
    // On any failure, close whatever sockets ptpip_connect/_events opened so the next
    // connect() (e.g. the "-2 confirm on camera and retry" path) doesn't leak fds by
    // overwriting them. ptpip_device_close only close()s non-zero fds and re-zeros them,
    // so it's safe on the -1 path (fds still 0) and never double-closes. Success keeps
    // its sockets open.
    if (result != 0) ptpip_device_close(r);
    (*env)->ReleaseStringUTFChars(env, ip, ip_c);
    (*env)->ReleaseStringUTFChars(env, name, name_c);
    return set_err(result);
}

// --- session / EOS mode wrappers (libpict return code passed through) ---

JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_openSession(JNIEnv *env, jobject thiz, jlong rt) {
    (void)env; (void)thiz;
    // Canon's mobile SDK always opens session 0x41 (CPtpipDevice::EstablishPtpipConnection);
    // ptp_open_session() increments r->session before sending.
    RT(rt)->session = 0x40;
    return set_err(ptp_open_session(RT(rt)));
}

JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_eosSetRemoteMode(JNIEnv *env, jobject thiz, jlong rt, jint mode) {
    (void)env; (void)thiz;
    return set_err(ptp_eos_set_remote_mode(RT(rt), mode));
}

JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_eosSetEventMode(JNIEnv *env, jobject thiz, jlong rt, jint mode) {
    (void)env; (void)thiz;
    return set_err(ptp_eos_set_event_mode(RT(rt), mode));
}

// libpict keeps a pointer to the last parsed device info (r->di) and reads it later, so it must
// outlive the call. ponytail: one static block, same single-connection ceiling as g_last_error.
static struct PtpDeviceInfo g_device_info;

// GetDeviceInfo; returns the camera model, NULL on failure. Checks for a data phase before parsing:
// libpict's parser ptp_panic()s (abort) on a response without one.
JNIEXPORT jstring JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_getDeviceInfo(JNIEnv *env, jobject thiz, jlong rt) {
    (void)thiz;
    struct PtpRuntime *r = RT(rt);
    struct PtpCommand cmd;
    cmd.code = PTP_OC_GetDeviceInfo;
    cmd.param_length = 0;
    int rc = ptp_send(r, &cmd);
    if (rc) {
        set_err(rc);
        return NULL;
    }
    if (((struct PtpIpHeader *)r->data)->type != PTPIP_DATA_PACKET_START) {
        set_err(PTP_IO_ERR);
        return NULL;
    }
    memset(&g_device_info, 0, sizeof g_device_info);
    ptp_parse_device_info(r, &g_device_info);
    set_err(0);
    return (*env)->NewStringUTF(env, g_device_info.model);
}

// Serial number from the last GetDeviceInfo (empty before the first one).
JNIEXPORT jstring JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_deviceSerial(JNIEnv *env, jobject thiz, jlong rt) {
    (void)thiz; (void)rt;
    return (*env)->NewStringUTF(env, g_device_info.serial_number);
}

// Canon EOS GetEvent (0x9116): the camera queues state changes and expects the host to poll.
// Returns the raw event records (empty when the camera sent no data phase), NULL on failure.
JNIEXPORT jbyteArray JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_eosGetEvent(JNIEnv *env, jobject thiz, jlong rt) {
    (void)thiz;
    struct PtpRuntime *r = RT(rt);
    int rc = ptp_eos_get_event(r);
    if (rc) {
        set_err(rc);
        return NULL;
    }
    set_err(0);
    struct PtpIpHeader *h = (struct PtpIpHeader *)r->data;
    unsigned int len = h->type == PTPIP_DATA_PACKET_START ? ptp_get_payload_length(r) : 0;
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)ptp_get_payload(r));
    }
    return out;
}

// Canon's mobile SDK pings the camera with DoNothing (0x902F, no params) every 5 s over the command
// channel (CMobilePtpHelper::DispatchPingCommand) and treats a failed ping as a disconnect.
JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_canonPing(JNIEnv *env, jobject thiz, jlong rt) {
    (void)env; (void)thiz;
    struct PtpCommand cmd;
    cmd.code = PTP_OC_CANON_DoNothing;
    cmd.param_length = 0;
    return set_err(ptp_send(RT(rt), &cmd));
}

// --- payload wrappers (return NULL on failure) ---

JNIEXPORT jintArray JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_getStorageIds(JNIEnv *env, jobject thiz, jlong rt) {
    (void)thiz;
    struct PtpArray *arr = NULL;
    int rc = ptp_get_storage_ids(RT(rt), &arr);
    if (rc || arr == NULL) {
        set_err(rc ? rc : PTP_IO_ERR);
        free(arr);
        return NULL;
    }
    jintArray out = (*env)->NewIntArray(env, (jsize)arr->length);
    if (out != NULL) {
        // PtpArray.data is uint32_t[]; jint is int32_t — same width.
        (*env)->SetIntArrayRegion(env, out, 0, (jsize)arr->length, (const jint *)arr->data);
    }
    free(arr);
    set_err(0);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_getObjectHandles(JNIEnv *env, jobject thiz, jlong rt,
                                                               jint storageId, jint format, jint parent) {
    (void)thiz;
    struct PtpArray *arr = NULL;
    int rc = ptp_get_object_handles(RT(rt), storageId, format, parent, &arr);
    if (rc || arr == NULL) {
        set_err(rc ? rc : PTP_IO_ERR);
        free(arr);
        return NULL;
    }
    jintArray out = (*env)->NewIntArray(env, (jsize)arr->length);
    if (out != NULL) {
        (*env)->SetIntArrayRegion(env, out, 0, (jsize)arr->length, (const jint *)arr->data);
    }
    free(arr);
    set_err(0);
    return out;
}

JNIEXPORT jobjectArray JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_getObjectInfo(JNIEnv *env, jobject thiz, jlong rt, jint handle) {
    (void)thiz;
    struct PtpObjectInfo oi;
    memset(&oi, 0, sizeof(oi));
    int rc = ptp_get_object_info(RT(rt), (uint32_t)handle, &oi);
    if (rc) {
        set_err(rc);
        return NULL;
    }

    jclass strCls = (*env)->FindClass(env, "java/lang/String");
    if (strCls == NULL) return NULL;
    jobjectArray out = (*env)->NewObjectArray(env, 6, strCls, NULL);
    if (out == NULL) return NULL;

    // [1]=compressedSize, [3]=objFormat, [4]=parentObj, [5]=assocType — all decimal.
    char c_size[16], c_fmt[16], c_parent[16], c_assoc[16];
    snprintf(c_size, sizeof c_size, "%u", oi.compressed_size);
    snprintf(c_fmt, sizeof c_fmt, "%u", (unsigned)oi.obj_format);
    snprintf(c_parent, sizeof c_parent, "%u", oi.parent_obj);
    snprintf(c_assoc, sizeof c_assoc, "%u", (unsigned)oi.assoc_type);

    // filename and date_created are camera-supplied ASCII (8.3 names / PTP strings).
    const char *vals[6] = {
        oi.filename, c_size, oi.date_created, c_fmt, c_parent, c_assoc
    };
    for (int i = 0; i < 6; i++) {
        jstring s = (*env)->NewStringUTF(env, vals[i]);
        (*env)->SetObjectArrayElement(env, out, i, s);
        (*env)->DeleteLocalRef(env, s); // no-op if s == NULL; keeps the local ref table small
    }
    set_err(0);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_getPartialObject(JNIEnv *env, jobject thiz, jlong rt,
                                                               jint handle, jlong offset, jint maxLen) {
    (void)thiz;
    // libpict takes unsigned int offset; CR3s are < 4 GB so a >UINT32_MAX offset
    // (or a negative offset/length) is a caller bug — refuse it.
    if (offset < 0 || (uint64_t)offset > UINT32_MAX || maxLen < 0) {
        set_err(-1);
        return NULL;
    }
    struct PtpRuntime *r = RT(rt);
    int rc = ptp_get_partial_object(r, (uint32_t)handle, (unsigned int)offset, (unsigned int)maxLen);
    if (rc) {
        set_err(rc);
        return NULL;
    }
    unsigned int len = ptp_get_payload_length(r);
    const uint8_t *payload = ptp_get_payload(r);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)len);
    if (out != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)len, (const jbyte *)payload);
    }
    set_err(0);
    return out;
}

// --- control ---

JNIEXPORT void JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_disconnect(JNIEnv *env, jobject thiz, jlong rt) {
    (void)env; (void)thiz;
    struct PtpRuntime *r = RT(rt);
    if (r == NULL) return;
    ptp_close_session(r);    // best-effort; ignore rc (may already be down / killed)
    ptpip_device_close(r);   // close cmd + event + video sockets; comm_priv freed at destroy()
}

JNIEXPORT jint JNICALL
Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_lastError(JNIEnv *env, jobject thiz, jlong rt) {
    (void)env; (void)thiz; (void)rt;
    return g_last_error;
}
