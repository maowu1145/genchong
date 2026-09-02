package com.maowu.gravitypet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RestingPairWakePolicyTest {
    @Test
    public void fixedStepGravityResidueDoesNotWakeRestingPair() {
        assertFalse(RestingPairWakePolicy.shouldWake(
                false, false, 56f, 56f));
        assertFalse(RestingPairWakePolicy.shouldWake(
                false, false, 92f, 60f));
    }

    @Test
    public void realImpactOrDraggingStillWakesPair() {
        assertTrue(RestingPairWakePolicy.shouldWake(
                false, false, 180f, 56f));
        assertTrue(RestingPairWakePolicy.shouldWake(
                true, false, 0f, 0f));
    }

    @Test
    public void spawnStabilizationDoesNotWakeOnOverlap() {
        assertFalse(RestingPairWakePolicy.shouldWake(
                false, true, 900f, 0f));
    }
}
