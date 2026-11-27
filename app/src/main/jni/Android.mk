# Siren's built-in RTL-SDR driver: libusb (LGPL-2.1+) and librtlsdr (GPL-2.0+), statically
# linked into libsirensdr.so with a small rtl_tcp-style server (siren_sdr.c).
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := usb
LOCAL_SRC_FILES := \
  libusb/libusb/core.c libusb/libusb/descriptor.c libusb/libusb/hotplug.c libusb/libusb/io.c \
  libusb/libusb/sync.c libusb/libusb/strerror.c libusb/libusb/os/linux_usbfs.c \
  libusb/libusb/os/events_posix.c libusb/libusb/os/threads_posix.c libusb/libusb/os/linux_netlink.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libusb $(LOCAL_PATH)/libusb/libusb $(LOCAL_PATH)/libusb/libusb/os
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/libusb/libusb
LOCAL_CFLAGS := -fvisibility=hidden -pthread -Wno-everything
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := rtlsdr
LOCAL_SRC_FILES := rtl-sdr/src/librtlsdr.c rtl-sdr/src/tuner_e4k.c rtl-sdr/src/tuner_fc0012.c \
  rtl-sdr/src/tuner_fc0013.c rtl-sdr/src/tuner_fc2580.c rtl-sdr/src/tuner_r82xx.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/rtl-sdr/include
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/rtl-sdr/include
LOCAL_CFLAGS := -Drtlsdr_STATIC -Wno-everything
LOCAL_STATIC_LIBRARIES := usb
include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := sirensdr
LOCAL_SRC_FILES := siren_sdr.c
LOCAL_CFLAGS := -Drtlsdr_STATIC -Wall
LOCAL_STATIC_LIBRARIES := rtlsdr usb
LOCAL_LDLIBS := -llog
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_SHARED_LIBRARY)
