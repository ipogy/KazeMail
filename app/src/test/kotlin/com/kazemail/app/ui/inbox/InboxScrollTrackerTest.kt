package com.kazemail.app.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxScrollTrackerTest {

    @Test
    fun preservesScrollPositionAcrossBackNavigationAndEmptyLayoutFrames() {
        val tracker = InboxScrollTracker()
        assertEquals(0, tracker.savedIndex)
        assertEquals(0, tracker.savedOffset)
        assertEquals(0, tracker.resetEpoch)

        // User scrolls down to item #42 in Inbox
        tracker.updateFromLayout(totalItemsCount = 100, firstVisibleIndex = 42, firstVisibleOffset = 180)
        assertEquals(42, tracker.savedIndex)
        assertEquals(180, tracker.savedOffset)

        // Transient empty frames (e.g., search overlay or loading) must not wipe the scroll bookmark
        tracker.updateFromLayout(totalItemsCount = 0, firstVisibleIndex = 0, firstVisibleOffset = 0)
        assertEquals(42, tracker.savedIndex)
        assertEquals(180, tracker.savedOffset)

        // Simulates opening MessageDetailScreen and navigating back: resetEpoch did not advance
        val lastHandledEpochOnReturn = 0
        assertFalse(tracker.resetEpoch != lastHandledEpochOnReturn)
        assertEquals(42, tracker.savedIndex)
        assertEquals(180, tracker.savedOffset)

        // Switching folder/page advances resetEpoch and clears bookmark back to top
        val nextEpoch = tracker.markContextReset()
        assertEquals(1, nextEpoch)
        assertTrue(tracker.resetEpoch != lastHandledEpochOnReturn)
        assertEquals(0, tracker.savedIndex)
        assertEquals(0, tracker.savedOffset)
    }
}
