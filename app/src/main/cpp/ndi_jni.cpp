// Thin JNI bridge between NdiSender.kt and the NDI Advanced SDK's compressed-frame ("NDI|HX")
// C API.
//
// Unlike standard NDI (see git history for the earlier raw-NV12 version of this file), NDI|HX
// doesn't do its own compression — it expects an already-compressed H.264/HEVC elementary
// stream and just tunnels it, prefixed with an NDIlib_compressed_packet_t header, over the NDI
// wire protocol. That's exactly what CameraCapture.kt's MediaCodec hardware encoder produces,
// so this bridge is mostly plumbing: build the header, hand MediaCodec's Annex-B NAL bytes to
// NDIlib_send_send_video_async_v2 as one contiguous buffer, done. No compression work happens
// on the CPU here — it's all in the phone's hardware H.264 encoder ASIC, same as
// EpocCam-streamer's approach, which is the whole reason this path exists (see the CPU/battery
// numbers that motivated switching off standard NDI).

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

// Async sending requires the frame's memory to stay valid until the *next* async call on this
// sender, so neither a stack-allocated packet header nor a JNI array pointer (released on
// return) can be used. Each send is assembled into one contiguous, persistently-owned buffer —
// [compressed_packet_t][H.264 data][SPS/PPS] — exactly the layout the SDK's own non-scatter
// example builds, which also lets us drop the scatter-gather list entirely. Three slots rotate
// so a buffer is never rewritten while the SDK may still be reading it (two would satisfy the
// documented rule; the third is margin).
struct SendSlot {
    std::vector<uint8_t> buf;
    NDIlib_video_frame_v2_t frame{};
};
constexpr int kSendSlots = 3;
SendSlot g_slots[kSendSlots];
int g_slotIdx = 0;

// The NDI HX SDK reports stream-validation failures by printing to STDOUT (and says it will
// terminate an invalid stream). Android sends a native process's stdout/stderr to /dev/null,
// so those diagnostics are invisible by default — this pumps both through a pipe into logcat
// under the "ndi_stdout" tag so the SDK can actually tell us what it thinks is wrong.
int g_stdoutPipe[2];
pthread_t g_stdoutThread;

void* stdoutPumpLoop(void*) {
    char buf[512];
    ssize_t n;
    while ((n = read(g_stdoutPipe[0], buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;   // logcat adds its own newline
        buf[n] = '\0';
        __android_log_write(ANDROID_LOG_WARN, "ndi_stdout", buf);
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

extern "C" JNIEXPORT jlong JNICALL
Java_com_exmachina_ndicamerastreamer_NdiSender_nativeCreate(
        JNIEnv* env, jobject /*thiz*/, jstring jSourceName) {
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

    // v2 + null config data == default Advanced SDK settings; a vendor id would normally go
    // in p_config_data for a shipped product, not needed for local testing.
    NDIlib_send_instance_t instance = NDIlib_send_create_v2(&createSettings, nullptr);
    env->ReleaseStringUTFChars(jSourceName, sourceName);

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

    // Assemble into a rotating persistent slot (see SendSlot above). Callers are serialized by
    // NdiSender.sendCompressedFrame, so the rotation needs no extra locking.
    SendSlot& slot = g_slots[g_slotIdx];
    g_slotIdx = (g_slotIdx + 1) % kSendSlots;

    const size_t total = sizeof(packet) + static_cast<size_t>(frameSize) + static_cast<size_t>(extraSize);
    slot.buf.resize(total);
    uint8_t* dst = slot.buf.data();
    memcpy(dst, &packet, sizeof(packet));
    memcpy(dst + sizeof(packet), reinterpret_cast<const uint8_t*>(frameBytes) + frameOffset, frameSize);
    if (extraBytes != nullptr && extraSize > 0) {
        memcpy(dst + sizeof(packet) + frameSize, extraBytes, extraSize);
    }

    NDIlib_video_frame_v2_t& videoFrame = slot.frame;
    videoFrame = NDIlib_video_frame_v2_t{};
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

    // Async: returns without waiting for the frame to be handed off, so the encoder drain
    // thread isn't blocked on the network path. Matches the SDK's own HX reference sender.
    NDIlib_send_send_video_async_v2(instance, &videoFrame);

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
        // Flush: a null async send waits for in-flight buffers to be released, so no slot is
        // still being read when we tear down.
        NDIlib_send_send_video_async_v2(instance, nullptr);
        NDIlib_send_destroy(instance);
        LOGI("NDI sender destroyed");
    }
}
