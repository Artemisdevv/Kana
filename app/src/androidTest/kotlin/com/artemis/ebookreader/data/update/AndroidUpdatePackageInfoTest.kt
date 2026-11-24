package com.artemis.ebookreader.data.update

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdatePackageInfoTest {
    @Test
    fun readsInstalledIdentityAndRejectsCorruptAndDebugArchives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = AndroidUpdatePackageInfo(context)
        assertEquals("com.artemis.ebookreader", packages.installed.packageName)
        assertTrue(packages.installed.versionCode > 0)
        assertTrue(packages.installed.signers.isNotEmpty())
        val invalid = File.createTempFile("invalid-update-", ".apk", context.cacheDir)
        try {
            invalid.writeText("This is not an APK.")
            assertNull(packages.inspect(invalid))
        } finally {
            invalid.delete()
        }
        assertNull(packages.inspect(File(context.applicationInfo.sourceDir)))
    }
}
