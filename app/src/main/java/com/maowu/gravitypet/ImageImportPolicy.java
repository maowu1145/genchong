package com.maowu.gravitypet;

/** Pure sizing rules used before any imported image pixels are allocated. */
final class ImageImportPolicy {
    static final int TARGET_MAX_EDGE = 600;
    static final int MAX_SOURCE_EDGE = 50_000;
    static final long MAX_SOURCE_PIXELS = 200_000_000L;

    private ImageImportPolicy() {}

    static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("图片尺寸无效");
        }
        if (width > MAX_SOURCE_EDGE || height > MAX_SOURCE_EDGE
                || (long) width * height > MAX_SOURCE_PIXELS) {
            throw new IllegalArgumentException("图片尺寸过大");
        }
    }

    static int[] targetDimensions(int width, int height) {
        validateDimensions(width, height);
        float scale = Math.min(1f,
                TARGET_MAX_EDGE / (float) Math.max(width, height));
        return new int[]{Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale))};
    }

    static int sampleSize(int width, int height) {
        validateDimensions(width, height);
        int sample = 1;
        while (width / (sample * 2) >= TARGET_MAX_EDGE
                || height / (sample * 2) >= TARGET_MAX_EDGE) {
            sample *= 2;
        }
        return sample;
    }
}
