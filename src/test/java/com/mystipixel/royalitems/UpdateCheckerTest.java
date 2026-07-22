package com.mystipixel.royalitems;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The version compare — pure, so proven without a network or a server. */
class UpdateCheckerTest {

    @Test
    void detectsANewerRelease() {
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.0"));
        assertTrue(UpdateChecker.isNewer("v1.0.0", "0.9.9"));
        assertTrue(UpdateChecker.isNewer("0.1.1", "0.1.0"));
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"));
    }

    @Test
    void sameOrOlderIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"));
        assertFalse(UpdateChecker.isNewer("v0.1.0", "0.1.0"));
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.2.0"));
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"));
    }

    @Test
    void handlesUnevenSegmentCounts() {
        assertTrue(UpdateChecker.isNewer("1.0.0.1", "1.0.0"));
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"));
    }
}
