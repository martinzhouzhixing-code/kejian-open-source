package app.kejian.mobile

import android.content.Context
import android.content.SharedPreferences
import java.io.PrintWriter
import java.io.StringWriter

internal fun SharedPreferences.safeBoolean(key:String,default:Boolean)=runCatching {getBoolean(key,default)}.getOrElse {edit().remove(key).commit();default}
internal fun SharedPreferences.safeLong(key:String,default:Long)=runCatching {getLong(key,default)}.getOrElse {edit().remove(key).commit();default}
internal fun SharedPreferences.safeInt(key:String,default:Int)=runCatching {getInt(key,default)}.getOrElse {edit().remove(key).commit();default}

/** Preserves the course database while repairing auxiliary state after a failed first frame. */
class StartupHealth(private val context:Context){
    private val prefs=context.getSharedPreferences("kejian_health",Context.MODE_PRIVATE)
    fun begin():Boolean {
        val unfinished=prefs.safeBoolean("launchInProgress",false)
        val attempts=if(unfinished)prefs.safeInt("consecutiveIncomplete",0)+1 else 0
        prefs.edit().putBoolean("launchInProgress",true).putInt("consecutiveIncomplete",attempts.coerceAtMost(9)).putLong("launchStartedAt",System.currentTimeMillis()).commit()
        return unfinished
    }
    fun repairAuxiliaryState(){
        // Only repair type-corrupted UI flags. Course data and encrypted account state are never
        // discarded just because a previous process did not reach its first stable frame.
        val experience=context.getSharedPreferences("kejian_experience",Context.MODE_PRIVATE)
        experience.safeBoolean("tutorialPending",false)
        experience.safeBoolean("accountPromptShown",true)
        experience.safeLong("dismissedUpdateCode",-1L)
    }
    fun stable(){prefs.edit().putBoolean("launchInProgress",false).putInt("consecutiveIncomplete",0).putLong("lastStableAt",System.currentTimeMillis()).commit()}
    fun recordStartupFailure(error:Throwable){
        val writer=StringWriter();error.printStackTrace(PrintWriter(writer))
        prefs.edit().putLong("lastCrashAt",System.currentTimeMillis()).putString("lastCrashThread","startup").putString("lastCrash",writer.toString().take(8_000)).commit()
    }
    fun recoveryMessage():String {
        val count=prefs.safeInt("consecutiveIncomplete",1).coerceAtLeast(1)
        return "检测到上次启动未完成，已进入兼容恢复（第 $count 次）。本地课表和账号登录状态均已保留。"
    }
}

/** Keeps a small local stack trace so startup failures can be diagnosed without collecting user data. */
object CrashDiagnostics {
    private var installed=false
    @Synchronized fun install(context:Context){
        if(installed)return;installed=true
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler {thread,error->
            runCatching {
                val writer=StringWriter();error.printStackTrace(PrintWriter(writer))
                context.getSharedPreferences("kejian_health",Context.MODE_PRIVATE).edit()
                    .putLong("lastCrashAt",System.currentTimeMillis()).putString("lastCrashThread",thread.name.take(80)).putString("lastCrash",writer.toString().take(8_000)).commit()
            }
            previous?.uncaughtException(thread,error)
        }
    }
}
