package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootHotspotStartPolicyTest {
    @Test fun hotspotStartsOnlyForAnEnabledBootLaunchWithPermissionAndAnOffAp() {
        assertTrue(BootHotspotStartPolicy.shouldStart(
            launchEnabled = true, hotspotEnabled = true, canWrite = true, alreadyOn = false,
        ))
        assertFalse(BootHotspotStartPolicy.shouldStart(
            launchEnabled = false, hotspotEnabled = true, canWrite = true, alreadyOn = false,
        ))
        assertFalse(BootHotspotStartPolicy.shouldStart(
            launchEnabled = true, hotspotEnabled = false, canWrite = true, alreadyOn = false,
        ))
        assertFalse(BootHotspotStartPolicy.shouldStart(
            launchEnabled = true, hotspotEnabled = true, canWrite = false, alreadyOn = false,
        ))
        assertFalse(BootHotspotStartPolicy.shouldStart(
            launchEnabled = true, hotspotEnabled = true, canWrite = true, alreadyOn = true,
        ))
    }
}
