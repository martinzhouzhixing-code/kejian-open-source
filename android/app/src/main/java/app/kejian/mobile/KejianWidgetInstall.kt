package app.kejian.mobile

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** A pin request is not a successful installation. Only a bound ID confirms success. */
object KejianWidgetInstall {
    private fun prefs(context:Context)=context.getSharedPreferences("kejian_widget_host",Context.MODE_PRIVATE)
    fun compatibilityContainer(context:Context)=prefs(context).getBoolean("compatibility_container",false)
    fun setCompatibilityContainer(context:Context,enabled:Boolean){prefs(context).edit().putBoolean("compatibility_container",enabled).apply()}
    fun pinSupported(context:Context)=!compatibilityContainer(context)&&runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_APP_WIDGETS)&&AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
    }.getOrDefault(false)
    fun request(context:Context,index:Int):String {
        fun copy(zh:String,en:String)=if(AppLanguage.english)en else zh
        if(compatibilityContainer(context))return copy("卓易通中的安卓小组件不能直接添加到鸿蒙原生桌面；当前 APK 暂不提供鸿蒙原生服务卡片。","Android widgets inside Zhuoyitong cannot be added directly to the native HarmonyOS Home screen. This APK does not provide native HarmonyOS service cards.")
        val provider=KejianWidgets.providers.getOrNull(index)?:return copy("请选择小组件尺寸。","Select a widget size.")
        if(!pinSupported(context))return copy("当前运行环境不支持应用内添加。安卓桌面请使用小组件列表；鸿蒙 4.x 可查看服务卡片底部的窗口小工具。通过卓易通运行时，无法提供原生鸿蒙服务卡片。","This environment does not support in-app pinning. Use the Android launcher widget list, or Window widgets under Service cards on HarmonyOS 4.x. Running through Zhuoyitong does not provide native HarmonyOS cards.")
        return runCatching {
            val callback=PendingIntent.getBroadcast(context,700+index,Intent(context,WidgetPinResultReceiver::class.java).setAction(KejianWidgets.ACTION_PINNED),PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31)PendingIntent.FLAG_MUTABLE else 0)
            val accepted=AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context,provider),null,callback)
            if(accepted)copy("添加请求已发送，请在桌面弹窗确认。只有系统确认添加成功后，才会显示成功提示。","Request sent. Confirm in your launcher's dialog. Success is shown only after the system confirms the widget was added.")
            else copy("桌面未接受添加请求，请从桌面的小组件列表添加。","Your launcher did not accept the request. Add it from the launcher widget list.")
        }.getOrElse {copy("无法打开桌面添加窗口，请从桌面的小组件列表添加；若通过卓易通运行，请查看下方兼容性说明。","The launcher pin dialog is unavailable. Use its widget list, or read the compatibility note below if running through Zhuoyitong.")}
    }
}
