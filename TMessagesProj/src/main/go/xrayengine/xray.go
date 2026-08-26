package main

/*
#cgo android LDFLAGS: -llog
#include <stdlib.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <jni.h>
#endif

static void xrayAndroidLog(char *msg) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "SovietXray", "%s", msg);
#endif
}

#ifdef __ANDROID__
static const char* xrayJStringGet(JNIEnv *env, jstring value) {
    if (value == NULL) {
        return NULL;
    }
    return (*env)->GetStringUTFChars(env, value, NULL);
}

static void xrayJStringRelease(JNIEnv *env, jstring value, const char *chars) {
    if (value != NULL && chars != NULL) {
        (*env)->ReleaseStringUTFChars(env, value, chars);
    }
}

static jstring xrayNewString(JNIEnv *env, const char *chars) {
    return (*env)->NewStringUTF(env, chars == NULL ? "" : chars);
}
#endif
*/
import "C"

import (
	"fmt"
	"strings"
	"sync"
	"unsafe"

	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	// Register every inbound/outbound/transport/security handler (vless, socks,
	// tls, reality, ws, grpc, ...). Without this blank import the JSON config
	// fails to load because none of the protocols are known.
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	instanceMu sync.Mutex
	instance   *core.Instance
	statsMgr   stats.Manager
)

// Counter names Xray registers for the "proxy" outbound when the config asks
// for per-outbound stats. Uplink is what the device sends, downlink what it
// receives.
const (
	uplinkCounter   = "outbound>>>proxy>>>traffic>>>uplink"
	downlinkCounter = "outbound>>>proxy>>>traffic>>>downlink"
)

// counterValue reads one counter, tolerating a missing stats manager or an
// unregistered counter (both are normal before the first byte flows).
func counterValue(name string) int64 {
	if statsMgr == nil {
		return 0
	}
	c := statsMgr.GetCounter(name)
	if c == nil {
		return 0
	}
	return c.Value()
}

func logMsg(msg string) {
	c := C.CString(msg)
	C.xrayAndroidLog(c)
	C.free(unsafe.Pointer(c))
}

// startXray (re)starts the Xray core with the given JSON config. Returns an
// empty string on success, otherwise a human-readable error message.
//
// The whole body runs under a defer/recover so a panic inside
// core.StartInstance (or anywhere else) is turned into a returned error string
// instead of silently unwinding and killing the host process. The start attempt
// and its outcome are ALWAYS logged via logMsg so device logs can pinpoint the
// failure even when core.StartInstance neither returns nor logs anything.
func startXray(configJSON string) (result string) {
	instanceMu.Lock()
	defer instanceMu.Unlock()

	defer func() {
		if r := recover(); r != nil {
			result = fmt.Sprintf("xray panic: %v", r)
			logMsg("xray start panic: " + result)
		}
	}()

	logMsg("xray starting")

	configJSON = strings.TrimSpace(configJSON)
	if configJSON == "" {
		logMsg("xray start failed: empty xray config")
		return "empty xray config"
	}

	if instance != nil {
		_ = instance.Close()
		instance = nil
	}

	inst, err := core.StartInstance("json", []byte(configJSON))
	if err != nil {
		msg := err.Error()
		if msg == "" {
			msg = "unknown xray start error"
		}
		logMsg("xray start failed: " + msg)
		return msg
	}
	instance = inst
	// Present only when the config carries a "stats" section and a policy that
	// enables outbound stats; a nil manager just means no traffic numbers.
	if mgr, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager); ok {
		statsMgr = mgr
	} else {
		statsMgr = nil
	}
	logMsg("xray started")
	return ""
}

func stopXray() {
	instanceMu.Lock()
	defer instanceMu.Unlock()
	if instance != nil {
		_ = instance.Close()
		instance = nil
		statsMgr = nil
		logMsg("xray stopped")
	}
}

// trafficStats returns "uplinkBytes,downlinkBytes" for the proxy outbound.
// Counters are cumulative for the lifetime of the current engine instance.
func trafficStats() string {
	instanceMu.Lock()
	defer instanceMu.Unlock()
	return fmt.Sprintf("%d,%d", counterValue(uplinkCounter), counterValue(downlinkCounter))
}

func isRunning() bool {
	instanceMu.Lock()
	defer instanceMu.Unlock()
	return instance != nil
}

// ---- Plain C exports (usable without JNI, handy for testing) ----

//export StartXray
func StartXray(cConfig *C.char) *C.char {
	return C.CString(startXray(C.GoString(cConfig)))
}

//export StopXray
func StopXray() {
	stopXray()
}

//export IsXrayRunning
func IsXrayRunning() C.int {
	if isRunning() {
		return 1
	}
	return 0
}

//export XrayStats
func XrayStats() *C.char {
	return C.CString(trafficStats())
}

//export FreeXrayString
func FreeXrayString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

// ---- Raw JNI exports (bound directly by System.loadLibrary + native methods) ----

func jstringToGoString(env *C.JNIEnv, s C.jstring) string {
	// The C helper already returns NULL for a NULL jstring, so no Go-side nil
	// check is needed (C.jstring is not directly comparable to nil in cgo).
	chars := C.xrayJStringGet(env, s)
	if chars == nil {
		return ""
	}
	defer C.xrayJStringRelease(env, s, chars)
	return C.GoString(chars)
}

//export Java_sovietgram_com_proxy_NativeXrayBridge_start
func Java_sovietgram_com_proxy_NativeXrayBridge_start(env *C.JNIEnv, clazz C.jclass, jConfig C.jstring) C.jstring {
	result := startXray(jstringToGoString(env, jConfig))
	cResult := C.CString(result)
	defer C.free(unsafe.Pointer(cResult))
	return C.xrayNewString(env, cResult)
}

//export Java_sovietgram_com_proxy_NativeXrayBridge_stop
func Java_sovietgram_com_proxy_NativeXrayBridge_stop(env *C.JNIEnv, clazz C.jclass) {
	stopXray()
}

//export Java_sovietgram_com_proxy_NativeXrayBridge_isRunning
func Java_sovietgram_com_proxy_NativeXrayBridge_isRunning(env *C.JNIEnv, clazz C.jclass) C.jboolean {
	if isRunning() {
		return 1
	}
	return 0
}

//export Java_sovietgram_com_proxy_NativeXrayBridge_stats
func Java_sovietgram_com_proxy_NativeXrayBridge_stats(env *C.JNIEnv, clazz C.jclass) C.jstring {
	cResult := C.CString(trafficStats())
	defer C.free(unsafe.Pointer(cResult))
	return C.xrayNewString(env, cResult)
}

func main() {}
