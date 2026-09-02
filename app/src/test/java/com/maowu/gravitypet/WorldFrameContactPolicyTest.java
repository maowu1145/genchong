package com.maowu.gravitypet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WorldFrameContactPolicyTest {
    @Test
    public void onlyAFrameWithSolvedPhysicsPublishesContactState() {
        assertFalse(WorldFrameContactPolicy.shouldPublishSolvedContacts(0));
        assertTrue(WorldFrameContactPolicy.shouldPublishSolvedContacts(1));
        assertTrue(WorldFrameContactPolicy.shouldPublishSolvedContacts(4));
    }
}
