package app.kejian.mobile

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/** Both hosts run in the application process. A task kind has exactly one coroutine owner. */
internal object BackgroundWorkCoordinator {
    private val active=ConcurrentHashMap<String,Job>()
    suspend fun run(kind:String,block:suspend ()->Unit):Boolean {
        val owner=currentCoroutineContext()[Job]?:error("Background work requires a Job")
        if(active.putIfAbsent(kind,owner)!=null)return false
        try {block();return true}finally {active.remove(kind,owner)}
    }
    fun cancel(kind:String){active[kind]?.cancel()}
    fun active(kind:String)=active.containsKey(kind)
}

internal object BackgroundWorkNotifications {
    private const val CHANNEL="kejian_background_tasks"
    private const val PROGRESS=8610
    fun progress(context:Context){
        val messages=listOfNotNull(BackgroundTaskRuntime.audioStatus.takeIf {BackgroundTaskRuntime.audioRunning},
            BackgroundTaskRuntime.documentStatus.takeIf {BackgroundTaskRuntime.documentRunning})
        if(messages.isEmpty())return
        show(context,PROGRESS,taskCopy("课间后台任务","Kejian background task"),messages.joinToString("\n"),true)
    }
    fun clearProgress(context:Context){runCatching {context.getSystemService(NotificationManager::class.java).cancel(PROGRESS)}}
    fun finished(context:Context,kind:String,title:String,body:String){show(context,if(kind=="audio")8612 else 8613,title,body,false)}
    private fun show(context:Context,id:Int,title:String,body:String,ongoing:Boolean){
        runCatching {
            val manager=context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL,taskCopy("后台处理","Background processing"),NotificationManager.IMPORTANCE_DEFAULT))
            val builder=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(ongoing).setOnlyAlertOnce(ongoing).setAutoCancel(!ongoing)
                .setContentIntent(PendingIntent.getActivity(context,8618,Intent(context,MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_RECORDING,BackgroundTaskRuntime.audioRunning||id==8612)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            if(ongoing&&BackgroundTaskRuntime.audioRunning)BackgroundTaskResults.pendingAudio(context)?.let {pending->
                builder.addAction(0,taskCopy("暂停传输","Pause transfer"),PendingIntent.getService(context,8616,
                    Intent(context,BackgroundTaskService::class.java).setAction(BackgroundTaskService.ACTION_PAUSE_AUDIO).putExtra("requestId",pending.requestId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.notify(id,builder.build())
        }
    }
}
