package com.maowu.gravitypet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImageImportPolicyTest {
    @Test
    public void oneHundredMegapixelImageIsSampledBeforeDecode() {
        assertArrayEquals(new int[]{600, 400},
                ImageImportPolicy.targetDimensions(15_000, 10_000));
        assertEquals(16, ImageImportPolicy.sampleSize(15_000, 10_000));
    }

    @Test
    public void extremeAspectRatioKeepsAtLeastOnePixel() {
        assertArrayEquals(new int[]{600, 1},
                ImageImportPolicy.targetDimensions(50_000, 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidDimensions() {
        ImageImportPolicy.validateDimensions(-1, 300);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessivePixelCount() {
        ImageImportPolicy.validateDimensions(50_000, 50_000);
    }
}
