package app.kejian.mobile

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(val version:String,val versionCode:Long,val downloadUrl:String,val notes:String,val sha256:String="")

object CurrentReleaseNotes {
    const val version:String="2.4.0"
    const val versionCode:Long=63L
    val items get()=if(AppLanguage.english)listOf(
        "Empty weekends collapse automatically; choose the daily time range in Settings.",
        "Compact cards prioritize names and locations; expand to read details and save weekly notes.",
        "Slower guided steps with a separate caption area.",
        "Widget photos keep their aspect ratio when resized."
    )else listOf(
        "周末无课自动收起；在设置中自定义每日显示时间。",
        "紧凑课程优先展示名称和地点；展开详情即可保存本周备注。",
        "教程放慢节奏，文案与演示分区显示。",
        "桌面组件照片等比裁切，缩放时不再压扁。"
    )
}

fun isNewerRelease(remoteCode:Long,currentCode:Long)=remoteCode>currentCode

class AppUpdateChecker(private val context:Context){
    private val endpoint="https://kejian.im/api/v1/release"
    val currentCode:Long get(){
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        return if(Build.VERSION.SDK_INT>=28)info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    suspend fun check():AppUpdateInfo?=withContext(Dispatchers.IO){
        val connection=(URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod="GET";connectTimeout=8_000;readTimeout=10_000;useCaches=false
            setRequestProperty("Accept","application/json");setRequestProperty("User-Agent","Kejian-Android-Update/$currentCode")
        }
        try{
            if(connection.responseCode !in 200..299)return@withContext null
            val bytes=connection.inputStream.use {input->
                val out=ByteArrayOutputStream();val chunk=ByteArray(4096);var total=0
                while(true){val n=input.read(chunk);if(n<0)break;total+=n;require(total<=64_000){"版本信息过大"};out.write(chunk,0,n)};out.toByteArray()
            }
            val json=JSONObject(String(bytes,Charsets.UTF_8));val code=json.getLong("versionCode")
            if(!isNewerRelease(code,currentCode))null else AppUpdateInfo(json.getString("version"),code,json.getString("downloadUrl"),json.optString("notes","发现课间新版本"),json.optString("sha256").lowercase())
        }finally{connection.disconnect()}
    }
}

/** Only the trusted website is opened; API download URLs never trigger an in-app download. */
object AppUpdateWebsite {
    const val URL="https://github.com/martinzhouzhixing-code/kejian-open-source/releases"
    fun intent()=Intent(Intent.ACTION_VIEW,Uri.parse(URL)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun open(context:Context){context.startActivity(intent())}

    /** Retire only the old updater's own task/session. Recordings and user exports are untouched. */
    fun retireLegacyUpdater(context:Context){
        val prefs=context.getSharedPreferences("kejian_update_download",Context.MODE_PRIVATE)
        val id=prefs.getLong("id",-1L)
        if(id>=0)runCatching {(context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)}
        val session=prefs.getInt("install_session",-1)
        if(session>=0)runCatching {context.packageManager.packageInstaller.abandonSession(session)}
        runCatching {(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(8620)}
        prefs.edit().clear().apply()
    }
}
