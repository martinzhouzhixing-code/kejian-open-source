package app.kejian.mobile

import java.io.File
import java.security.MessageDigest

internal data class UpdatePackageIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)
internal enum class UpdateValidationFailure { MISSING, HASH, INVALID_APK, PACKAGE, VERSION, SIGNATURE }

/** Compare the actual APK with both the release manifest and the installed application. */
internal fun validateUpdateIdentity(
    installed: UpdatePackageIdentity,
    downloaded: UpdatePackageIdentity?,
    expectedVersionCode: Long,
): UpdateValidationFailure? = when {
    downloaded == null -> UpdateValidationFailure.INVALID_APK
    downloaded.packageName != installed.packageName -> UpdateValidationFailure.PACKAGE
    downloaded.versionCode != expectedVersionCode || downloaded.versionCode <= installed.versionCode -> UpdateValidationFailure.VERSION
    installed.signers.isEmpty() || downloaded.signers.isEmpty() || downloaded.signers != installed.signers -> UpdateValidationFailure.SIGNATURE
    else -> null
}

internal fun updateSha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(128 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

internal fun updateValidationMessage(failure: UpdateValidationFailure, english: Boolean): String = when (failure) {
    UpdateValidationFailure.MISSING -> if (english) "The downloaded package is missing. Download the update again in Settings." else "已下载的安装包已丢失，请在设置中重新下载更新。"
    UpdateValidationFailure.HASH -> if (english) "The package is incomplete or does not match this release. Download it again in Settings." else "安装包不完整或与此版本不一致，请在设置中重新下载。"
    UpdateValidationFailure.INVALID_APK -> if (english) "This file is not a readable Android installation package. Download it again in Settings." else "下载的文件不是有效的 Android 安装包，请在设置中重新下载。"
    UpdateValidationFailure.PACKAGE -> if (english) "This package belongs to a different application. It was not installed." else "此安装包的应用标识不匹配，已停止安装。"
    UpdateValidationFailure.VERSION -> if (english) "This package is not the advertised newer version. Check for updates again in Settings." else "安装包并非提示的新版本，请在设置中重新检查更新。"
    UpdateValidationFailure.SIGNATURE -> if (english) "The update signature does not match the installed app. Your data is safe; do not uninstall the app. Please contact support." else "更新包签名与已安装的应用不一致，无法覆盖安装。请勿卸载以免丢失本地数据，请联系开发者。"
}
