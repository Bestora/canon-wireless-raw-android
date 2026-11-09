// Drives the real JNI entry points of ptp_jni.c against fake_camera.py, with a fake JNIEnv.
// usage: driver <port> <steps...>   steps: connect | open | partial | disconnect
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define J(name) Java_de_bestora_canonwirelessrawandroid_ptp_PtpNative_##name
jlong J(create)(JNIEnv *, jobject);
jint J(connect)(JNIEnv *, jobject, jlong, jstring, jint, jbyteArray, jstring);
jint J(openSession)(JNIEnv *, jobject, jlong);
jbyteArray J(getPartialObject)(JNIEnv *, jobject, jlong, jint, jlong, jint);
jint J(lastError)(JNIEnv *, jobject, jlong);
void J(disconnect)(JNIEnv *, jobject, jlong);

struct arr { jsize len; jbyte data[]; };

static jsize GetArrayLength(JNIEnv *e, jarray a) { (void)e; return ((struct arr *)a)->len; }
static void GetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, jbyte *out) {
    (void)e; memcpy(out, ((struct arr *)a)->data + s, (size_t)n);
}
static jbyteArray NewByteArray(JNIEnv *e, jsize n) {
    (void)e; struct arr *a = malloc(sizeof *a + (size_t)n); a->len = n; return (jbyteArray)a;
}
static void SetByteArrayRegion(JNIEnv *e, jbyteArray a, jsize s, jsize n, const jbyte *in) {
    (void)e; memcpy(((struct arr *)a)->data + s, in, (size_t)n);
}
static const char *GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { (void)e; (void)c; return (const char *)s; }
static void ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) { (void)e; (void)s; (void)c; }

static const char *current = "start";
static void on_alarm(int sig) {
    (void)sig;
    char msg[128];
    int n = snprintf(msg, sizeof msg, "HANG: step '%s' did not return within 20 s\n", current);
    write(1, msg, (size_t)n);
    _exit(2);
}

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

int main(int argc, char **argv) {
    struct JNINativeInterface_ fns;
    memset(&fns, 0, sizeof fns);
    fns.GetArrayLength = GetArrayLength;
    fns.GetByteArrayRegion = GetByteArrayRegion;
    fns.NewByteArray = NewByteArray;
    fns.SetByteArrayRegion = SetByteArrayRegion;
    fns.GetStringUTFChars = GetStringUTFChars;
    fns.ReleaseStringUTFChars = ReleaseStringUTFChars;
    JNIEnv env = &fns;

    signal(SIGALRM, on_alarm);
    int port = atoi(argv[1]);
    jlong rt = J(create)(&env, NULL);
    struct arr *guid = (struct arr *)NewByteArray(&env, 16);
    memset(guid->data, 0xAB, 16);

    for (int i = 2; i < argc; i++) {
        current = argv[i];
        alarm(20);
        double t0 = now();
        if (!strcmp(argv[i], "connect")) {
            jint rc = J(connect)(&env, NULL, rt, (jstring) "127.0.0.1", port, (jbyteArray)guid, (jstring) "Canon Wireless RAW");
            printf("connect rc=%d", rc);
        } else if (!strcmp(argv[i], "open")) {
            printf("open rc=%d", J(openSession)(&env, NULL, rt));
        } else if (!strcmp(argv[i], "partial")) {
            jbyteArray b = J(getPartialObject)(&env, NULL, rt, 1, 0, 4096);
            if (b) printf("partial ok len=%d", ((struct arr *)b)->len);
            else printf("partial FAILED lastError=%d", J(lastError)(&env, NULL, rt));
        } else if (!strcmp(argv[i], "disconnect")) {
            J(disconnect)(&env, NULL, rt);
            printf("disconnect done");
        }
        printf("  (%.1f s)\n", now() - t0);
        fflush(stdout);
        alarm(0);
    }
    return 0;
}
