// Thin JNI bridge between NdiSender.kt and the NDI Advanced SDK's compressed-frame ("NDI|HX")
// C API.
//
// Unlike standard NDI (see git history for the earlier raw-NV12 version of this file), NDI|HX
// doesn't do its own compression — it expects an already-compressed H.264/HEVC elementary
// stream and just tunnels it, prefixed with an NDIlib_compressed_packet_t header, over the NDI
// wire protocol. That's exactly what CameraCapture.kt's MediaCodec hardware encoder produces,
// so this bridge is mostly plumbing: build the header, hand MediaCodec's Annex-B NAL bytes to
// NDIlib_send_send_video_v2 as one contiguous buffer, done. No compression work happens on the
// CPU here — it's all in the phone's hardware H.264 encoder ASIC, same as EpocCam-streamer's
// approach, which is the whole reason this path exists (see the CPU/battery numbers that
// motivated switching off standard NDI).
//
// Deliberately synchronous (NDIlib_send_send_video_v2), not async. This app tried async sending
// (NDIlib_send_send_video_async_v2) for a while, on the theory that it would keep the encoder
// drain thread from blocking on the network path. Measured end to end in Millumin: no
// perceptible latency difference. It did, however, cause two distinct real bugs around buffer
// lifetime — async hands the SDK a pointer and returns immediately, and the buffer must stay
// valid until the SDK is actually done with it:
//   1. A fixed-size rotation (3 buffers, reused blindly in order) could be overwritten while
//      the SDK still referenced a buffer for a stalled/ungracefully-disconnected receiver,
//      corrupting what got sent and plausibly explaining a real "won't reconnect" bug.
//   2. The documented fix for that (NDIlib_send_set_video_async_completion, growing a pool and
//      reusing a slot only once the SDK confirms it via callback) turned out to have a worse
//      failure mode in this exact usage: with no active receiver connected, the completion
//      callback was never observed to fire at all, so the pool grew without bound — over
//      26,000 buffers and ~340MB in one real run before it was caught.
// With zero measured benefit and two real bugs from the async path, synchronous sending is the
// correct call: NDIlib_send_send_video_v2 copies/consumes the buffer before returning, so a
// single reused buffer is unambiguously safe to overwrite on the very next call. No pool, no
// completion tracking, no way for this class of bug to recur.

#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <pthread.h>
#include <unistd.h>
#include <vector>

#include "Processing.NDI.Advanced.h"

#define TAG "ndi_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
std::atomic<bool> g_ndiInitialized{false};

// So stdoutPumpLoop (a plain pthread, not JNI-attached) can call back into Kotlin when it sees
// the SDK's trial-limit message on stdout.
JavaVM* g_jvm = nullptr;
// Cached as a global ref from nativeCreate (called on a properly JNI-attached thread with the
// app's own classloader) rather than looked up fresh in the pump thread: FindClass from a
// thread the JVM didn't create (like a plain pthread attached via AttachCurrentThread) resolves
// against the bootstrap classloader, not the app's, and silently fails to find app classes —
// confirmed directly: the warning callback never fired and never logged anything, because
// FindClass was returning null and the code correctly no-op'd on that instead of crashing.
jclass g_ndiSenderClass = nullptr;

// Reused for every send. Safe because NDIlib_send_send_video_v2 is synchronous — the SDK is
// guaranteed done reading this buffer by the time the call returns, so overwriting it on the
// next call (from the same, serialized caller — see NdiSender.sendCompressedFrame's
// @Synchronized) can never race with anything still reading the previous contents.
std::vector<uint8_t> g_sendBuf;

// The NDI HX SDK reports stream-validation failures by printing to STDOUT (and says it will
// terminate an invalid stream). Android sends a native process's stdout/stderr to /dev/null,
// so those diagnostics are invisible by default — this pumps both through a pipe into logcat
// under the "ndi_stdout" tag so the SDK can actually tell us what it thinks is wrong.
int g_stdoutPipe[2];
pthread_t g_stdoutThread;

// Forwards a line seen on the SDK's stdout up to NdiSender.onNativeWarning(String) if it matches
// a known trial/licensing message, so the UI can show it instead of the failure looking like an
// unexplained network problem. Attaches this pthread to the JVM for the duration of the call —
// cheap and infrequent (this only fires on the rare lines that match), so no need to keep it
// attached permanently.
void notifyJavaOfWarning(const char* line) {
    if (!g_jvm || !g_ndiSenderClass) return;
    JNIEnv* env = nullptr;
    if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) return;

    jmethodID mid = env->GetStaticMethodID(g_ndiSenderClass, "onNativeWarning", "(Ljava/lang/String;)V");
    if (mid != nullptr) {
        jstring jline = env->NewStringUTF(line);
        env->CallStaticVoidMethod(g_ndiSenderClass, mid, jline);
        env->DeleteLocalRef(jline);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    g_jvm->DetachCurrentThread();
}

void* stdoutPumpLoop(void*) {
    char buf[512];
    ssize_t n;
    while ((n = read(g_stdoutPipe[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;   // logcat adds its own newline
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_WARN, "ndi_stdout", buf);
        // Substring match on wording confirmed from the SDK's actual output (see README) rather
        // than the whole line, so small wording variations across SDK versions don't silently
        // stop surfacing this.
        if (strstr(buf, "designed for development use") != nullptr ||
            strstr(buf, "commercial use license") != nullptr) {
            notifyJavaOfWarning(buf);
        }
    }
    return nullptr;
}

void redirectStdioToLogcat() {
    setvbuf(stdout, nullptr, _IOLBF, 0);   // line-buffered so messages arrive promptly
    setvbuf(stderr, nullptr, _IONBF, 0);
    if (pipe(g_stdoutPipe) != 0) { LOGE("stdout pipe() failed"); return; }
    dup2(g_stdoutPipe[1], STDOUT_FILENO);
    dup2(g_stdoutPipe[1], STDERR_FILENO);
    pthread_create(&g_stdoutThread, nullptr, stdoutPumpLoop, nullptr);
    pthread_detach(g_stdoutThread);
    LOGI("stdout/stderr redirected to logcat (tag: ndi_stdout)");
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeCreate(
        JNIEnv* env, jobject /*thiz*/, jstring jSourceName, jstring jConfigJson) {
    if (g_ndiSenderClass == nullptr) {
        jclass localCls = env->FindClass("com/exmachina/ndicamerastreamer/NdiSender");
        if (localCls != nullptr) {
            g_ndiSenderClass = static_cast<jclass>(env->NewGlobalRef(localCls));
            env->DeleteLocalRef(localCls);
        } else {
            LOGE("FindClass(NdiSender) failed — trial-limit UI warning will not fire");
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
    }

    if (!g_ndiInitialized.exchange(true)) {
        redirectStdioToLogcat();   // before NDIlib_initialize, so we catch its output too
        if (!NDIlib_initialize()) {
            LOGE("NDIlib_initialize failed (no NDI-capable CPU features?)");
            g_ndiInitialized.store(false);
            return 0;
        }
        LOGI("NDIlib_initialize OK");
    }

    const char* sourceName = env->GetStringUTFChars(jSourceName, nullptr);

    NDIlib_send_create_t createSettings{};
    createSettings.p_ndi_name = sourceName;
    createSettings.p_groups = nullptr;
    // clock_video=false: with clocking on, NDI rate-limits sends to the declared frame rate,
    // which means holding frames back — pure added latency for a source like ours that is
    // already paced by the camera's own clock. The SDK docs say as much: "if you have a device
    // which has it's own video clock that delivers video at the correct rate that you would
    // specify these as false."
    createSettings.clock_video = false;
    createSettings.clock_audio = false;

    // p_config_data overrides the "machine name" half of the advertised source name
    // (MACHINE_NAME (SOURCE_NAME)). Android has no real network hostname — gethostname()
    // returns the literal string "localhost" on every device — so without this override every
    // Android sender on the LAN advertises itself as "LOCALHOST (...)". Per NDI's own docs, a
    // machine-name clash "is incompatible with mDNS and can cause all other sources not to work
    // correctly"; this matched a real symptom (source discovered instantly, but no video
    // frames arriving for tens of seconds) that a plain hostname of "localhost" plausibly
    // explains, since some mDNS resolvers special-case "localhost" as always-loopback and won't
    // resolve it over multicast at all. See NdiSender.kt for where the unique per-device name
    // comes from.
    const char* configJson = jConfigJson ? env->GetStringUTFChars(jConfigJson, nullptr) : nullptr;

    NDIlib_send_instance_t instance = NDIlib_send_create_v2(&createSettings, configJson);
    env->ReleaseStringUTFChars(jSourceName, sourceName);
    if (configJson) env->ReleaseStringUTFChars(jConfigJson, configJson);

    if (!instance) {
        LOGE("NDIlib_send_create_v2 failed");
        return 0;
    }
    LOGI("NDI|HX sender created");
    return reinterpret_cast<jlong>(instance);
}

// Sends one already-encoded H.264 access unit. [frame] holds the Annex-B NAL bytes for this
// frame (start-coded, VCL NALs only — no SPS/PPS in here). [extra] holds SPS+PPS (also
// Annex-B, concatenated) and must be non-null exactly on keyframes; pass an empty array
// otherwise. [ptsHns]/[isKeyframe] map straight onto NDIlib_compressed_packet_t's pts/flags.
extern "C" JNIEXPORT void JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeSendCompressedFrame(
        JNIEnv* env, jobject /*thiz*/, jlong ptr,
        jint width, jint height, jint fpsN, jint fpsD,
        jboolean isPreview, jboolean isKeyframe, jlong ptsHns,
        jbyteArray jFrame, jint frameOffset, jint frameSize,
        jbyteArray jExtra, jint extraSize) {
    auto instance = reinterpret_cast<NDIlib_send_instance_t>(ptr);
    if (instance == nullptr) return;

    jbyte* frameBytes = env->GetByteArrayElements(jFrame, nullptr);
    if (frameBytes == nullptr) return;
    jbyte* extraBytes = nullptr;
    if (extraSize > 0 && jExtra != nullptr) {
        extraBytes = env->GetByteArrayElements(jExtra, nullptr);
    }

    NDIlib_compressed_packet_t packet{};
    packet.version = sizeof(NDIlib_compressed_packet_t);
    packet.fourCC = NDIlib_compressed_FourCC_type_H264;
    packet.pts = ptsHns;
    packet.dts = ptsHns;  // no B-frames in this pipeline, so decode order == presentation order
    packet.reserved = 0;
    packet.flags = isKeyframe ? NDIlib_compressed_packet_t::flags_keyframe
                               : NDIlib_compressed_packet_t::flags_none;
    packet.data_size = static_cast<uint32_t>(frameSize);
    packet.extra_data_size = static_cast<uint32_t>(extraSize);

    const size_t total = sizeof(packet) + static_cast<size_t>(frameSize) + static_cast<size_t>(extraSize);
    g_sendBuf.resize(total);
    uint8_t* dst = g_sendBuf.data();
    memcpy(dst, &packet, sizeof(packet));
    memcpy(dst + sizeof(packet), reinterpret_cast<const uint8_t*>(frameBytes) + frameOffset, frameSize);
    if (extraBytes != nullptr && extraSize > 0) {
        memcpy(dst + sizeof(packet) + frameSize, extraBytes, extraSize);
    }

    NDIlib_video_frame_v2_t videoFrame{};
    videoFrame.xres = width;
    videoFrame.yres = height;
    // An NDI|HX source is required to publish BOTH a full-bandwidth ("program") stream and a
    // 640-wide preview stream, each tagged with its own FourCC so receivers can pick one; see
    // CameraCapture.kt, which runs two MediaCodec encoders for exactly this. These tags are
    // quality-tier labels, not bitrate knobs — actual bitrate is each encoder's CBR target.
    videoFrame.FourCC = (NDIlib_FourCC_video_type_e)(isPreview
        ? NDIlib_FourCC_video_type_ex_H264_lowest_bandwidth
        : NDIlib_FourCC_video_type_ex_H264_highest_bandwidth);
    videoFrame.frame_rate_N = fpsN;
    videoFrame.frame_rate_D = fpsD;
    videoFrame.picture_aspect_ratio = static_cast<float>(width) / static_cast<float>(height);
    videoFrame.frame_format_type = NDIlib_frame_format_type_progressive;
    videoFrame.timecode = ptsHns;
    videoFrame.p_data = dst;
    videoFrame.data_size_in_bytes = static_cast<int>(total);
    videoFrame.p_metadata = nullptr;
    videoFrame.timestamp = 0;

    // Synchronous: the SDK has finished reading g_sendBuf by the time this returns, so it is
    // safe to overwrite on the next call. See the file header for why this replaced async.
    NDIlib_send_send_video_v2(instance, &videoFrame);

    env->ReleaseByteArrayElements(jFrame, frameBytes, JNI_ABORT);
    if (extraBytes != nullptr) {
        env->ReleaseByteArrayElements(jExtra, extraBytes, JNI_ABORT);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeIsKeyframeRequired(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto instance = reinterpret_cast<NDIlib_send_instance_t>(ptr);
    if (instance == nullptr) return JNI_FALSE;
    return NDIlib_send_is_keyframe_required(instance, nullptr) ? JNI_TRUE : JNI_FALSE;
}

// Blocks until NDI's keyframe requirement changes (or the timeout elapses). Event-driven, so
// an I-frame request can be serviced within the SDK's 100ms HX-compliance window rather than
// waiting out a polling interval — the SDK validates this and terminates streams that miss it.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeWaitForKeyframeRequest(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr, jint timeoutMs) {
    auto instance = reinterpret_cast<NDIlib_send_instance_t>(ptr);
    if (instance == nullptr) return JNI_FALSE;
    return NDIlib_send_wait_for_keyframe_request(
        instance, static_cast<uint32_t>(timeoutMs), nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeDestroy(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ptr) {
    auto instance = reinterpret_cast<NDIlib_send_instance_t>(ptr);
    if (instance != nullptr) {
        NDIlib_send_destroy(instance);
        LOGI("NDI sender destroyed");
    }
}
