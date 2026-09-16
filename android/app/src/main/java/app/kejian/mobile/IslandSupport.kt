package app.kejian.mobile

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle

/** Capability inspection only. Sending focus notifications needs vendor scene approval. */
object IslandSupport {
    fun inspect(context:Context):String {
        val vendor=Build.MANUFACTURER.lowercase()
        return when {
            vendor in listOf("xiaomi","redmi","poco") -> {
                val version=runCatching {android.provider.Settings.System.getInt(context.contentResolver,"notification_focus_protocol",0)}.getOrDefault(0)
                val result=runCatching {context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"),"canShowFocus",null,Bundle().apply {putString("package",context.packageName)})?.getBoolean("canShowFocus",false)}
                val protocol=when {version>=3->"系统具备超级岛协议";version>=1->"系统具备焦点通知协议";else->"未检测到可用协议"}
                val permission=when(result.getOrNull()){true->"系统报告应用有焦点通知权限";false->"本应用尚无焦点通知权限";null->"未能读取应用的焦点通知权限"}
                "$protocol；$permission。课程场景尚未获得厂商接入审核，因此本版仍发送标准横幅通知。"
            }
            vendor in listOf("oppo","oneplus","realme") -> "OPPO 流体云需接入意图共享服务并申请场景、服务标识。本版尚无获批凭据，使用标准横幅提醒。"
            vendor in listOf("vivo","iqoo") -> "vivo 原子岛需要厂商场景接入与协议配置；本版未获得课程场景接入，使用标准横幅提醒。"
            vendor=="honor" -> "荣耀灵动胶囊需要在 YOYO 全局触达中配置场景、素材并通过审核；本版尚未获批，使用标准横幅提醒。"
            vendor=="huawei" -> "华为 Live View Kit 的 HarmonyOS 接口不能直接用于本 Android 安装包；本版使用标准通知。"
            else -> "本版使用 Android 标准横幅提醒。Android 实时更新要求进行中的用户活动，普通待上课日程不直接启用；其他厂商仍需单独审核适配。"
        }
    }
}
