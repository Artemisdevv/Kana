package com.artemis.ebookreader.feature.settings

import java.net.URI

internal object SettingsLinks {
    private val repositoryUrl: String? = null
    private val issueUrl: String? = null

    val repository: String? get() = validRepositoryAddress(repositoryUrl)
    val issues: String? get() = repository?.let { validRepositoryAddress(issueUrl, it) }
}

internal fun validRepositoryAddress(value: String?, repository: String? = null): String? {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("github.com", ignoreCase = true) ||
        uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null
    ) return null
    val path = uri.rawPath.trimEnd('/')
    if (repository == null) {
        if (!Regex("/[A-Za-z0-9_-]+/[A-Za-z0-9_.-]+").matches(path) || path.substringAfterLast('/') in setOf(".", "..")) return null
    } else {
        val base = URI(repository).rawPath.trimEnd('/')
        if (path !in setOf("$base/issues", "$base/issues/new", "$base/issues/new/choose")) return null
    }
    return uri.toString()
}
