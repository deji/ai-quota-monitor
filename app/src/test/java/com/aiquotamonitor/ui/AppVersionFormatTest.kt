package com.aiquotamonitor.ui

import com.aiquotamonitor.BuildConfig
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionFormatTest {
    @Test
    fun `BuildConfig VERSION_NAME matches semantic version X_Y_Z-aaaa format`() {
        val pattern = Regex("""^\d+\.\d+\.\d+-[0-9a-f]{4}$""")
        assertTrue(
            "Expected BuildConfig.VERSION_NAME ('${BuildConfig.VERSION_NAME}') to match X.Y.Z-aaaa",
            pattern.matches(BuildConfig.VERSION_NAME)
        )
        assertNotEquals("1.1.0-0000", BuildConfig.VERSION_NAME)
    }
}
