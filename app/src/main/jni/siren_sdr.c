/*
 * Siren's built-in RTL-SDR driver: opens the dongle from the USB file descriptor Android gave the
 * app (rtlsdr_open_fd) and serves it as an rtl_tcp server on 127.0.0.1, so Siren's existing
 * rtl_tcp client (RtlTcpMonitor) works unchanged - and so does a remote rtl_tcp host.
 * The protocol is rtl_tcp's: a 12-byte header ("RTL0", tuner type, gain count), then 8-bit I/Q;
 * the client sends 5-byte commands (1 byte command, 4 bytes big-endian value).
 * Other apps can reach 127.0.0.1 too, so a client must first send the 16-byte secret Siren made
 * for this run (token); anything else is disconnected before it hears or controls the dongle.
 * GPL-2.0-or-later, like the librtlsdr it builds on.
 */
#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <poll.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
#include "rtl-sdr.h"

#define TAG "SirenSdr"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static volatile int stop_flag = 0;
static volatile int listen_sock = -1;
static volatile int client_sock = -1;

struct worker { rtlsdr_dev_t *dev; int sock; volatile int done; };

static void apply(rtlsdr_dev_t *dev, uint8_t cmd, uint32_t v) {
    switch (cmd) {
    case 0x01: rtlsdr_set_center_freq(dev, v); break;
    case 0x02: rtlsdr_set_sample_rate(dev, v); break;
    case 0x03: rtlsdr_set_tuner_gain_mode(dev, (int)v); break;
    case 0x04: rtlsdr_set_tuner_gain(dev, (int)v); break;
    case 0x05: rtlsdr_set_freq_correction(dev, (int)v); break;
    case 0x07: rtlsdr_set_testmode(dev, (int)v); break;
    case 0x08: rtlsdr_set_agc_mode(dev, (int)v); break;
    case 0x09: rtlsdr_set_direct_sampling(dev, (int)v); break;
    case 0x0a: rtlsdr_set_offset_tuning(dev, (int)v); break;
    case 0x0d: { /* gain by index */
        int count = rtlsdr_get_tuner_gains(dev, NULL);
        if (count > 0 && v < (uint32_t)count) { /* unsigned: a huge index must not pass */
            int *gains = malloc(sizeof(int) * (size_t)count);
            if (gains && rtlsdr_get_tuner_gains(dev, gains) == count) rtlsdr_set_tuner_gain(dev, gains[v]);
            free(gains);
        }
        break;
    }
    case 0x0e: rtlsdr_set_bias_tee(dev, (int)v); break;
    default: break;
    }
}

static void *commands(void *arg) {
    struct worker *w = arg;
    uint8_t buf[5];
    while (!w->done && !stop_flag) {
        size_t got = 0;
        while (got < 5) {
            ssize_t r = recv(w->sock, buf + got, 5 - got, 0);
            if (r <= 0) { w->done = 1; return NULL; }
            got += (size_t)r;
        }
        uint32_t v = ((uint32_t)buf[1] << 24) | ((uint32_t)buf[2] << 16) | ((uint32_t)buf[3] << 8) | buf[4];
        apply(w->dev, buf[0], v);
    }
    return NULL;
}

static int send_all(int sock, const uint8_t *p, size_t n) {
    while (n > 0) {
        ssize_t r = send(sock, p, n, MSG_NOSIGNAL);
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}

/* Reads exactly n bytes, or fails (timeout, closed). */
static int recv_all(int sock, uint8_t *p, size_t n) {
    while (n > 0) {
        ssize_t r = recv(sock, p, n, 0);
        if (r <= 0) return -1;
        p += r; n -= (size_t)r;
    }
    return 0;
}

/* Called before the serving thread starts, so a stop that comes early isn't lost. */
JNIEXPORT void JNICALL Java_app_sirenscout_SdrDriver_nativePrepare(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    stop_flag = 0;
}

/* Returns 0 when stopped, or a negative error (-1: no dongle at that descriptor, -2: socket). */
JNIEXPORT jint JNICALL Java_app_sirenscout_SdrDriver_nativeServe(JNIEnv *env, jclass cls, jint usb_fd, jint port, jbyteArray jtoken) {
    (void)cls;
    uint8_t token[16];
    if ((*env)->GetArrayLength(env, jtoken) != 16) return -3;
    (*env)->GetByteArrayRegion(env, jtoken, 0, 16, (jbyte *)token);
    rtlsdr_dev_t *dev = NULL;
    int r = rtlsdr_open_fd(&dev, usb_fd);
    if (r < 0 || !dev) { LOGW("rtlsdr_open_fd failed: %d", r); return -1; }
    rtlsdr_set_sample_rate(dev, 1024000);
    rtlsdr_set_tuner_gain_mode(dev, 0);
    rtlsdr_reset_buffer(dev);

    int s = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    struct sockaddr_in addr = {0};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (bind(s, (struct sockaddr *)&addr, sizeof addr) < 0 || listen(s, 1) < 0) {
        LOGW("can't listen on port %d: %s", port, strerror(errno));
        close(s); rtlsdr_close(dev); return -2;
    }
    listen_sock = s;
    LOGI("serving the dongle on 127.0.0.1:%d", port);
    uint8_t *buf = malloc(262144);
    while (!stop_flag) {
        struct pollfd pfd = { s, POLLIN, 0 };
        if (poll(&pfd, 1, 500) <= 0) continue;
        int c = accept(s, NULL, NULL);
        if (c < 0) continue;
        /* Only Siren knows this run's secret: other apps on the phone are cut off here. */
        struct timeval tv = { 3, 0 };
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
        uint8_t got[16];
        uint8_t diff = 0;
        if (recv_all(c, got, 16) < 0) diff = 1;
        else for (int i = 0; i < 16; i++) diff |= (uint8_t)(got[i] ^ token[i]);
        if (diff) { LOGW("rejected a client without the secret"); close(c); continue; }
        struct timeval none = { 0, 0 };
        setsockopt(c, SOL_SOCKET, SO_RCVTIMEO, &none, sizeof none);
        client_sock = c;
        uint8_t header[12] = { 'R', 'T', 'L', '0' };
        uint32_t tuner = htonl((uint32_t)rtlsdr_get_tuner_type(dev));
        uint32_t gains = htonl((uint32_t)rtlsdr_get_tuner_gains(dev, NULL));
        memcpy(header + 4, &tuner, 4); memcpy(header + 8, &gains, 4);
        if (send_all(c, header, sizeof header) == 0) {
            struct worker w = { dev, c, 0 };
            pthread_t t;
            pthread_create(&t, NULL, commands, &w);
            rtlsdr_reset_buffer(dev);
            while (!stop_flag && !w.done) {
                int n = 0;
                if (rtlsdr_read_sync(dev, buf, 262144, &n) < 0) { LOGW("dongle read failed (unplugged?)"); stop_flag = 1; break; }
                if (n > 0 && send_all(c, buf, (size_t)n) < 0) break;
            }
            w.done = 1;
            shutdown(c, SHUT_RDWR);
            pthread_join(t, NULL);
        }
        close(c);
        client_sock = -1;
    }
    free(buf);
    close(s);
    listen_sock = -1;
    rtlsdr_close(dev);
    LOGI("stopped");
    return 0;
}

JNIEXPORT void JNICALL Java_app_sirenscout_SdrDriver_nativeStop(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    stop_flag = 1;
    int c = client_sock;
    if (c >= 0) shutdown(c, SHUT_RDWR);
}
