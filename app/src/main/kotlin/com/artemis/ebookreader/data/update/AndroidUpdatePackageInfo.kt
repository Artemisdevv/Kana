package com.artemis.ebookreader.data.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

internal class AndroidUpdatePackageInfo(context: Context) : UpdatePackageInfo {
    private val manager = context.packageManager
    override val sdk: Int get() = Build.VERSION.SDK_INT
    @Suppress("DEPRECATION")
    private val signingFlags = if (sdk >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    override val installed = manager.getPackageInfo(context.packageName, signingFlags).identity()

    @Suppress("DEPRECATION")
    override fun inspect(file: File): ApkIdentity? {
        val info = manager.getPackageArchiveInfo(file.absolutePath, signingFlags) ?: return null
        val application = info.applicationInfo ?: return null
        if (application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 || !info.splitNames.isNullOrEmpty()) return null
        return info.identity()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.identity(): ApkIdentity {
        val signatures = if (sdk >= 28) signingInfo?.apkContentsSigners else signatures
        val digest = MessageDigest.getInstance("SHA-256")
        return ApkIdentity(
            packageName, PackageInfoCompat.getLongVersionCode(this), versionName,
            applicationInfo?.minSdkVersion ?: 0,
            signatures.orEmpty().mapTo(mutableSetOf()) { digest.digest(it.toByteArray()).hex() },
        )
    }
}

class UpdateApkProvider : FileProvider()
