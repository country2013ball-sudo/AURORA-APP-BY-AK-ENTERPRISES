#include <jni.h>
#include <cstdint>
#include <cmath>
#include <algorithm>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "AuroraNative", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) printf(__VA_ARGS__)
#endif

/**
 * Aurora Engine Native Graphics & Frame Interpolation Core
 * Developed by AK Enterprises for ARM64 Mali-G57 & compatible Android GPUs.
 *
 * Implements:
 * 1. Block-based Motion Estimation ( Sad8x8 luma search ) for experimental LSFG-inspired interpolation.
 * 2. Motion-compensated frame blending between Frame(t-1) and Frame(t) at configurable alpha (0.25..0.75).
 * 3. Contrast-Adaptive Sharpening (CAS) spatial upscale filter for preserving edge crispness.
 */
namespace aurora {

inline uint8_t clamp8(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

inline int lumaFromRgba(uint32_t px) {
    int r = (px >> 16) & 0xFF;
    int g = (px >> 8) & 0xFF;
    int b = px & 0xFF;
    // Fast integerBT.601 luma approximation: (77*R + 150*G + 29*B) >> 8
    return (77 * r + 150 * g + 29 * b) >> 8;
}

// Compute average motion magnitude (in pixels*100) and synthesize an intermediate frame
int interpolateFramesRgba(
    const uint32_t* prevFrame,
    const uint32_t* currFrame,
    uint32_t* outFrame,
    int width,
    int height,
    float alpha
) {
    if (!prevFrame || !currFrame || !outFrame || width <= 0 || height <= 0) {
        return -1;
    }

    const int blockSize = 8;
    const int searchRadius = 4;
    long totalMotionSad = 0;
    int blockCount = 0;
    const int invAlphaFixed = static_cast<int>((1.0f - alpha) * 256.0f);
    const int alphaFixed = 256 - invAlphaFixed;

    for (int by = 0; by < height; by += blockSize) {
        for (int bx = 0; bx < width; bx += blockSize) {
            int bestDx = 0;
            int bestDy = 0;
            int bestSad = 0x7FFFFFFF;

            // Only run full search on interior blocks to avoid boundary branch overhead
            if (bx >= searchRadius && by >= searchRadius &&
                bx + blockSize + searchRadius < width &&
                by + blockSize + searchRadius < height) {

                for (int dy = -searchRadius; dy <= searchRadius; dy += 2) {
                    for (int dx = -searchRadius; dx <= searchRadius; dx += 2) {
                        int sad = 0;
                        for (int py = 0; py < blockSize; py += 2) {
                            const int yCurr = by + py;
                            const int yPrev = yCurr + dy;
                            const uint32_t* currRow = currFrame + yCurr * width + bx;
                            const uint32_t* prevRow = prevFrame + yPrev * width + (bx + dx);
                            for (int px = 0; px < blockSize; px += 2) {
                                sad += std::abs(lumaFromRgba(currRow[px]) - lumaFromRgba(prevRow[px]));
                            }
                        }
                        if (sad < bestSad) {
                            bestSad = sad;
                            bestDx = dx;
                            bestDy = dy;
                        }
                    }
                }
                totalMotionSad += bestSad;
                blockCount++;
            }

            // Synthesize block pixels along estimated motion vector
            const int shiftX = static_cast<int>(bestDx * alpha);
            const int shiftY = static_cast<int>(bestDy * alpha);

            const int maxY = std::min(by + blockSize, height);
            const int maxX = std::min(bx + blockSize, width);

            for (int y = by; y < maxY; ++y) {
                const int srcPrevY = std::clamp(y + shiftY, 0, height - 1);
                const int srcCurrY = std::clamp(y - (bestDy - shiftY), 0, height - 1);
                for (int x = bx; x < maxX; ++x) {
                    const int srcPrevX = std::clamp(x + shiftX, 0, width - 1);
                    const int srcCurrX = std::clamp(x - (bestDx - shiftX), 0, width - 1);

                    uint32_t p0 = prevFrame[srcPrevY * width + srcPrevX];
                    uint32_t p1 = currFrame[srcCurrY * width + srcCurrX];

                    int r = (((p0 >> 16) & 0xFF) * invAlphaFixed + ((p1 >> 16) & 0xFF) * alphaFixed) >> 8;
                    int g = (((p0 >> 8) & 0xFF) * invAlphaFixed + ((p1 >> 8) & 0xFF) * alphaFixed) >> 8;
                    int b = ((p0 & 0xFF) * invAlphaFixed + (p1 & 0xFF) * alphaFixed) >> 8;

                    outFrame[y * width + x] = (0xFFu << 24) | (static_cast<uint32_t>(r) << 16) |
                                              (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(b);
                }
            }
        }
    }

    return blockCount > 0 ? static_cast<int>(totalMotionSad / blockCount) : 0;
}

// Contrast-Adaptive Sharpening (CAS) spatial filter for upscaled frames
void applyContrastAdaptiveSharpening(
    const uint32_t* inputFrame,
    uint32_t* outputFrame,
    int width,
    int height,
    float sharpness
) {
    if (!inputFrame || !outputFrame || width < 3 || height < 3) return;

    const int sharpWeight = static_cast<int>(std::clamp(sharpness, 0.0f, 1.0f) * 64.0f);

    // Copy border pixels directly
    std::memcpy(outputFrame, inputFrame, width * sizeof(uint32_t));
    std::memcpy(outputFrame + (height - 1) * width, inputFrame + (height - 1) * width, width * sizeof(uint32_t));

    for (int y = 1; y < height - 1; ++y) {
        outputFrame[y * width] = inputFrame[y * width];
        outputFrame[y * width + (width - 1)] = inputFrame[y * width + (width - 1)];

        for (int x = 1; x < width - 1; ++x) {
            const int idx = y * width + x;
            uint32_t center = inputFrame[idx];
            uint32_t north = inputFrame[idx - width];
            uint32_t south = inputFrame[idx + width];
            uint32_t west = inputFrame[idx - 1];
            uint32_t east = inputFrame[idx + 1];

            int cLuma = lumaFromRgba(center);
            int nLuma = lumaFromRgba(north);
            int sLuma = lumaFromRgba(south);
            int wLuma = lumaFromRgba(west);
            int eLuma = lumaFromRgba(east);

            int minLuma = std::min({cLuma, nLuma, sLuma, wLuma, eLuma});
            int maxLuma = std::max({cLuma, nLuma, sLuma, wLuma, eLuma});
            int contrast = maxLuma - minLuma;

            // Adaptive weight: sharpen moderate contrast edges while avoiding noise or clipping
            int localWeight = (sharpWeight * (255 - contrast)) >> 8;

            int lapR = 4 * ((center >> 16) & 0xFF) -
                       (((north >> 16) & 0xFF) + ((south >> 16) & 0xFF) +
                        ((west >> 16) & 0xFF) + ((east >> 16) & 0xFF));
            int lapG = 4 * ((center >> 8) & 0xFF) -
                       (((north >> 8) & 0xFF) + ((south >> 8) & 0xFF) +
                        ((west >> 8) & 0xFF) + ((east >> 8) & 0xFF));
            int lapB = 4 * (center & 0xFF) -
                       ((north & 0xFF) + (south & 0xFF) + (west & 0xFF) + (east & 0xFF));

            uint8_t outR = clamp8(((center >> 16) & 0xFF) + ((lapR * localWeight) >> 8));
            uint8_t outG = clamp8(((center >> 8) & 0xFF) + ((lapG * localWeight) >> 8));
            uint8_t outB = clamp8((center & 0xFF) + ((lapB * localWeight) >> 8));

            outputFrame[idx] = (0xFFu << 24) | (static_cast<uint32_t>(outR) << 16) |
                               (static_cast<uint32_t>(outG) << 8) | static_cast<uint32_t>(outB);
        }
    }
}

} // namespace aurora

extern "C"
JNIEXPORT jstring JNICALL
Java_com_akenterprises_auroraengine_graphics_NativeFrameProcessor_nativeGetEngineVersion(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF("Aurora Native Optical-Flow & CAS Engine v1.0 (ARM64/NEON Ready)");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_akenterprises_auroraengine_graphics_NativeFrameProcessor_nativeInterpolateFrames(
    JNIEnv* env,
    jobject /* this */,
    jintArray prevPixels,
    jintArray currPixels,
    jintArray outPixels,
    jint width,
    jint height,
    jfloat alpha
) {
    if (!prevPixels || !currPixels || !outPixels) return -1;
    jsize len = env->GetArrayLength(prevPixels);
    if (len < width * height) return -2;

    jint* prevPtr = env->GetIntArrayElements(prevPixels, nullptr);
    jint* currPtr = env->GetIntArrayElements(currPixels, nullptr);
    jint* outPtr = env->GetIntArrayElements(outPixels, nullptr);

    int avgSad = aurora::interpolateFramesRgba(
        reinterpret_cast<const uint32_t*>(prevPtr),
        reinterpret_cast<const uint32_t*>(currPtr),
        reinterpret_cast<uint32_t*>(outPtr),
        width,
        height,
        alpha
    );

    env->ReleaseIntArrayElements(prevPixels, prevPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(currPixels, currPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(outPixels, outPtr, 0);
    return avgSad;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_akenterprises_auroraengine_graphics_NativeFrameProcessor_nativeApplyCasSharpening(
    JNIEnv* env,
    jobject /* this */,
    jintArray inPixels,
    jintArray outPixels,
    jint width,
    jint height,
    jfloat sharpness
) {
    if (!inPixels || !outPixels) return -1;
    jsize len = env->GetArrayLength(inPixels);
    if (len < width * height) return -2;

    jint* inPtr = env->GetIntArrayElements(inPixels, nullptr);
    jint* outPtr = env->GetIntArrayElements(outPixels, nullptr);

    aurora::applyContrastAdaptiveSharpening(
        reinterpret_cast<const uint32_t*>(inPtr),
        reinterpret_cast<uint32_t*>(outPtr),
        width,
        height,
        sharpness
    );

    env->ReleaseIntArrayElements(inPixels, inPtr, JNI_ABORT);
    env->ReleaseIntArrayElements(outPixels, outPtr, 0);
    return 0;
}
