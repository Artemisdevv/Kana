package com.artemis.ebookreader.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsLinksTest {
    @Test
    fun requiresHttpsAndRepositoryScopedIssueLinks() {
        val repo = "https://github.com/example/project"
        assertEquals(repo, validRepositoryAddress(repo))
        assertEquals("$repo/issues/new", validRepositoryAddress("$repo/issues/new", repo))
        assertNull(validRepositoryAddress("http://github.com/example/project"))
        assertNull(validRepositoryAddress("https://github.com.evil.test/example/project"))
        assertNull(validRepositoryAddress("https://example@github.com/example/project"))
        assertNull(validRepositoryAddress("https://github.com/settings/profile", repo))
        assertNull(validRepositoryAddress("https://github.com/example/other/issues", repo))
        assertNull(validRepositoryAddress(null))
    }
}
