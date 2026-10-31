package com.iltan.settle.data.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.iltan.settle.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DriveSandboxTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDebugFoldersOfThePhoneAndTheEmulatorMayBeReset() {
        // This test runs on the debug variant, whose resources name the debug folder.
        assertTrue(DriveSandbox.isSandboxFolder(context.getString(R.string.drive_root_folder)))
        assertTrue(
            DriveSandbox.isSandboxFolder(context.getString(R.string.drive_root_folder_emulator))
        )
    }

    @Test
    fun theRealFolderNeverMayBeReset() {
        assertFalse(DriveSandbox.isSandboxFolder("Settle"))
        assertFalse(DriveSandbox.isSandboxFolder("My photos (debug)"))
    }
}
