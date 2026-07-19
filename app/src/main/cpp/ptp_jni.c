#include <jni.h>
#include <libpict.h>

// libpict's no_usb.c backend (selected via PTP_NO_USB=ON, since this app has
// no libusb/USB backend yet) does not implement the full cl_backend.h
// contract: lib.c unconditionally calls these two hooks from ptp_close()/
// ptpusb_free_device_list(). No-op stubs are enough for the create/destroy
// smoke surface; Task 3 replaces these once a real transport is wired in.
void ptp_comm_deinit(struct PtpRuntime *r) { (void)r; }
void ptpusb_free_device_list_entry(void *ptr) { (void)ptr; }

JNIEXPORT jlong JNICALL
Java_io_github_canonwirelessraw_ptp_PtpNative_create(JNIEnv *env, jobject thiz) {
    return (jlong)(intptr_t)ptp_new(PTP_IP);
}

JNIEXPORT void JNICALL
Java_io_github_canonwirelessraw_ptp_PtpNative_destroy(JNIEnv *env, jobject thiz, jlong rt) {
    ptp_close((struct PtpRuntime *)(intptr_t)rt);
}
