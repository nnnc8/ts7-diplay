package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiPlayBootstrapTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val directory get() = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)

    @Test fun missingLocalProvisioningBlocksWithoutInstallingAssetsOrChangingPreferences() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)
        AirPlayPersistence.saveDebugLogsEnabled(context, true)
        assertFalse(directory.exists())

        val error = assertThrows(AuthBlockedException::class.java) { DiPlayBootstrap.ensure(context) }

        assertEquals("AUTH_BLOCKED", error.message)
        assertNull(error.cause)
        assertFalse(directory.exists())
        assertEquals(MfiTarget.LOCAL, AirPlayPersistence.loadMfiTarget(context))
        assertTrue(AirPlayPersistence.loadDebugLogsEnabled(context))
    }

    @Test fun explicitlySelectedExternalProvidersDoNotRequireOrInstallLocalIdentity() {
        for (target in listOf(MfiTarget.REMOTE, MfiTarget.I2C, MfiTarget.USB_CH341)) {
            AirPlayPersistence.saveMfiTarget(context, target)
            DiPlayBootstrap.ensure(context)
            assertEquals(target, AirPlayPersistence.loadMfiTarget(context))
            assertFalse(directory.exists())
        }
    }

    @Test fun anEmptyLocalDirectoryDoesNotOverrideTheSelectedExternalProvider() {
        assertTrue(directory.mkdirs())
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.REMOTE)

        DiPlayBootstrap.ensure(context)

        assertEquals(MfiTarget.REMOTE, AirPlayPersistence.loadMfiTarget(context))
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun changingBackToLocalChecksProvisioningAgainRatherThanReusingReadiness() {
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.REMOTE)
        DiPlayBootstrap.ensure(context)
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)

        assertThrows(AuthBlockedException::class.java) { DiPlayBootstrap.ensure(context) }
        assertFalse(directory.exists())
    }

    @Test fun invalidLocalDirectoryFailsWithOnlyTheFixedPublicCode() {
        assertTrue(directory.mkdirs())
        AirPlayPersistence.saveMfiTarget(context, MfiTarget.LOCAL)

        val error = assertThrows(AuthBlockedException::class.java) { DiPlayBootstrap.ensure(context) }

        assertEquals("AUTH_BLOCKED", error.message)
        assertNull(error.cause)
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
