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
#include <memory>
#include <mutex>
#include <pthread.h>
#include <unistd.h>
#include <vector>

#include "Processing.NDI.Advanced.h"

#define TAG "ndi_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
std::atomic<bool> g_ndiInitialized{false};

// Async sending requires the frame's memory to stay valid until the SDK is done with it — but
// NOT necessarily just until "the next call": per the SDK's own docs (Asynchronous Sending
// Completions), if a receiver has disconnected ungracefully, the SDK can hold a buffer until
// that connection internally times out, which is far longer than a handful of frames. A fixed
// small rotation (this app's original approach: 3 slots, reused blindly in order) can overwrite
// a buffer NDI still references for a stale connection — memory corruption that plausibly
// explains a real bug this exact mechanism produced: after a receiver disconnected, a *new*
// connection attempt would get zero video frames indefinitely, while this app's own encode/send
// loop kept reporting healthy throughput throughout (confirmed by direct reproduction).
//
// Fixed properly per the SDK's documented mechanism: a completion handler
// (NDIlib_send_set_video_async_completion) tells us exactly when each buffer is actually free,
// and the pool grows on demand instead of guessing a fixed slot count.
struct SendSlot {
    std::vector<uint8_t> buf;
    NDIlib_video_frame_v2_t frame{};
    std::atomic<bool> inUse{false};
};
std::mutex g_poolMutex;  // guards g_pool; touched both by the sender thread and the SDK's own
                         // completion-callback thread, which the docs say can be either
std::vector<std::unique_ptr<SendSlot>> g_pool;

// Called by the SDK (on its own thread — must stay quick and not lock permanently, per the
// docs) once a submitted frame's buffer is no longer needed by any connection, including
// stalled/disconnecting ones. Matches by pointer identity against the pool.
void onAsyncSendComplete(void* /*p_opaque*/, const NDIlib_video_frame_v2_t* p_video_data) {
    if (p_video_data == nullptr || p_video_data->p_data == nullptr) return;
    std::lock_guard<std::mutex> lock(g_poolMutex);
    for (auto& slot : g_pool) {
        if (slot->buf.data() == p_video_data->p_data) {
            slot->inUse.store(false);
            return;
        }
    }
}

// Returns a slot guaranteed not in use by the SDK (grows the pool if every existing slot is
// still claimed — e.g. several stalled/slow connections at once). No hard cap: at steady state
// with one healthy connection this settles to ~2 slots, and even a pathological case sized in
// the dozens is negligible memory next to one encoded frame's already-small footprint.
SendSlot* claimSlot() {
    std::lock_guard<std::mutex> lock(g_poolMutex);
    for (auto& slot : g_pool) {
        bool expected = false;
        if (slot->inUse.compare_exchange_strong(expected, true)) return slot.get();
    }
    g_pool.push_back(std::make_unique<SendSlot>());
    g_pool.back()->inUse.store(true);
    if (g_pool.size() > 8) {
        LOGE("send buffer pool grew to %zu — a receiver may be stalled/not disconnecting cleanly",
             g_pool.size());
    }
    return g_pool.back().get();
}

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

    // Own buffer-lifetime tracking (see SendSlot above) instead of the SDK's default "safe until
    // the next async call" assumption, which the docs say does not hold for stalled/ungracefully
    // disconnected receivers.
    NDIlib_send_set_video_async_completion(instance, nullptr, onAsyncSendComplete);

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

    // A slot the completion callback has confirmed free (see SendSlot/claimSlot above) — not a
    // blind rotation, since the SDK can legitimately hold a buffer well past a few frames' worth
    // of turnover if a receiver is stalled or disconnecting ungracefully.
    SendSlot* slot = claimSlot();

    const size_t total = sizeof(packet) + static_cast<size_t>(frameSize) + static_cast<size_t>(extraSize);
    slot->buf.resize(total);
    uint8_t* dst = slot->buf.data();
    memcpy(dst, &packet, sizeof(packet));
    memcpy(dst + sizeof(packet), reinterpret_cast<const uint8_t*>(frameBytes) + frameOffset, frameSize);
    if (extraBytes != nullptr && extraSize > 0) {
        memcpy(dst + sizeof(packet) + frameSize, extraBytes, extraSize);
    }

    NDIlib_video_frame_v2_t& videoFrame = slot->frame;
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
