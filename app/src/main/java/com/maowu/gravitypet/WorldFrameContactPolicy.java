package com.maowu.gravitypet;

/** Keeps display callbacks without a fixed physics step from publishing stale contact data. */
final class WorldFrameContactPolicy {
    private WorldFrameContactPolicy() {}

    static boolean shouldPublishSolvedContacts(int completedSubsteps) {
        return completedSubsteps > 0;
    }
}
