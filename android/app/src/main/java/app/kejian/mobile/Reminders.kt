package app.kejian.mobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.*
import org.json.JSONObject

data class NotificationReport(val sent:Boolean,val title:String,val message:String,val settingsTarget:String?=null)

private fun deliveredDate(key:String):LocalDate?=runCatching {val fields=key.split('|');LocalDate.parse(fields[if(fields.firstOrNull()=="deadline")2 else 1])}.getOrNull()

object ReminderScheduler {
    const val CHANNEL="course_reminders"
    private const val BANNER_CHANNEL="course_banners"
    private fun english(context:Context)=runCatching {val raw=context.getSharedPreferences("kejian_data",Context.MODE_PRIVATE).getString("data",null)?:return@runCatching false;JSONObject(raw).optJSONObject("settings")?.optString("language")=="en"}.getOrDefault(false)
    fun activeChannel(context:Context)=if(context.getSharedPreferences("kejian_notification_ui",Context.MODE_PRIVATE).getBoolean("banners",false))BANNER_CHANNEL else CHANNEL
    fun enableBanners(context:Context):NotificationReport {
        createChannel(context)
        blockedReason(context)?.let {return it}
        // Explicit user opt-in only; never recreate a disabled channel or bypass system blocking.
        val manager=context.getSystemService(NotificationManager::class.java)
        val en=english(context)
        manager.createNotificationChannel(NotificationChannel(BANNER_CHANNEL,if(en)"Event banners"else"课前弹窗提醒",NotificationManager.IMPORTANCE_HIGH).apply {
            description=if(en)"User-enabled event banners, sound and vibration"else"用户启用的课前横幅、声音和振动提醒";enableVibration(true)
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION).build())
        })
        context.getSharedPreferences("kejian_notification_ui",Context.MODE_PRIVATE).edit().putBoolean("banners",true).apply()
        blockedReason(context)?.let {return it}
        return NotificationReport(false,"已选择弹窗提醒",bannerStatus(context)+"\n请发送测试通知。如果仍无横幅，请在渠道设置中打开“悬浮通知 / 横幅”。","channel")
    }
    fun bannerStatus(context:Context):String {
        blockedReason(context)?.let {return it.message}
        val en=english(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel=manager.getNotificationChannel(activeChannel(context))
        return when {
            (channel?.importance?:0)<NotificationManager.IMPORTANCE_HIGH -> if(en)"This channel is silent or normal, so banners cannot appear. Enable banners or raise its priority in system settings."else"当前是普通/静默渠道，不能弹出横幅。可点“启用弹窗提醒”或在系统渠道设置中提高重要性。"
            manager.currentInterruptionFilter!=NotificationManager.INTERRUPTION_FILTER_ALL -> if(en)"Do Not Disturb may suppress banners and sound."else"当前开启勿扰，系统可能抑制弹窗与声音。"
            channel?.sound==null -> if(en)"High priority is enabled, but this channel has no sound. Some phones also require floating notifications."else"已设为高优先级，但渠道无提示音；部分手机还需开启声音和悬浮通知。"
            else -> if(en)"High-priority reminders are active. Also allow floating notifications or banners in system settings."else"已使用高优先级渠道；请同时允许系统“悬浮通知 / 横幅”。"
        }
    }
    fun createChannel(context:Context){
        val en=english(context);context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,if(en)"Event reminders"else"课前提醒",NotificationManager.IMPORTANCE_HIGH).apply { description=if(en)"Advance reminders with event locations"else"按你设定的时间提前提醒，包含课堂地址";enableVibration(true) })
    }
    fun exactAllowed(context:Context)=Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun notificationAllowed(context:Context)=blockedReason(context)==null
    fun blockedReason(context:Context):NotificationReport? {
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return NotificationReport(false,"需要通知权限","尚未允许课间发送通知。请允许权限后重新测试。","app")
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled())return NotificationReport(false,"系统通知已关闭","请在系统通知设置中开启课间的通知，再重新测试。","app")
        val channel=manager.getNotificationChannel(activeChannel(context))
        if(channel?.importance==NotificationManager.IMPORTANCE_NONE)return NotificationReport(false,"课前提醒渠道已关闭","应用通知已允许，但“课前提醒”渠道被关闭。请进入渠道设置打开。","channel")
        if(Build.VERSION.SDK_INT>=28&&channel?.group!=null&&manager.getNotificationChannelGroup(channel.group)?.isBlocked==true)return NotificationReport(false,"通知分组已关闭","请在系统通知设置中打开对应通知分组。","app")
        return null
    }
    private fun pending(context:Context,id:String,date:LocalDate?=null,minutes:Int=10):PendingIntent = PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).apply {
        data=Uri.parse("kejian://reminder/$id");putExtra("courseId",id);putExtra("date",date?.toString());putExtra("reminderMinutes",minutes)
    },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun deadlinePending(context:Context,id:String,date:LocalDate?=null,minute:Int?=null):PendingIntent = PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).apply {
        data=Uri.parse("kejian://deadline/$id");putExtra("deadlineId",id);putExtra("date",date?.toString());putExtra("deadlineMinute",minute?:-1)
    },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun setAlarm(alarms:AlarmManager,whenMillis:Long,intent:PendingIntent,context:Context){
        try { if(exactAllowed(context))alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,intent) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,intent) }
        catch(_:SecurityException){alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,whenMillis,intent)}
    }
    fun reschedule(context:Context,data:AppData=CourseStore(context).load(),now:ZonedDateTime=ZonedDateTime.now()) {
        createChannel(context)
        val alarms=context.getSystemService(AlarmManager::class.java)
        val prefs=context.getSharedPreferences("kejian_alarms",Context.MODE_PRIVATE)
        prefs.getStringSet("ids",emptySet()).orEmpty().forEach { stored ->
            when {
                stored.startsWith("deadline|") -> stored.substringAfter('|').let {alarms.cancel(deadlinePending(context,it));if(data.deadlines.none {d->d.id==it})context.getSystemService(NotificationManager::class.java).cancel(it,2)}
                stored.startsWith("course|") -> stored.substringAfter('|').let {alarms.cancel(pending(context,it));if(data.courses.none {c->c.id==it})context.getSystemService(NotificationManager::class.java).cancel(it,1)}
                else -> {alarms.cancel(pending(context,stored));alarms.cancel(deadlinePending(context,stored))}
            }
        }
        val ids=mutableSetOf<String>()
        val delivered=prefs.getStringSet("delivered",emptySet()).orEmpty()
        if(data.settings.reminders&&notificationAllowed(context))for(c in data.courses.filter { it.reminder }){
            val next=c.dates(data.settings.termStart).map { Occurrence(c,it) }.filter { it.startAt(now.zone).isAfter(now)&&"${c.id}|${it.date}|${c.start}" !in delivered }.minByOrNull { it.startAt(now.zone).toInstant() }?:continue
            val whenMillis=maxOf(now.toInstant().toEpochMilli()+1000,next.startAt(now.zone).minusMinutes(data.settings.reminderMinutes.toLong()).toInstant().toEpochMilli());val intent=pending(context,c.id,next.date,data.settings.reminderMinutes)
            setAlarm(alarms,whenMillis,intent,context)
            ids.add("course|${c.id}")
        }
        if(data.settings.reminders&&notificationAllowed(context))for(d in data.deadlines.filter {it.reminder}){
            val minute=d.dueMinute?:9*60
            val moment=d.dueDate.atTime(minute/60,minute%60).atZone(now.zone)
            val key="deadline|${d.id}|${d.dueDate}|$minute"
            if(!moment.isAfter(now)||key in delivered)continue
            setAlarm(alarms,moment.toInstant().toEpochMilli(),deadlinePending(context,d.id,d.dueDate,minute),context)
            ids.add("deadline|${d.id}")
        }
        prefs.edit().putStringSet("ids",ids).apply()
        scheduleWidgetTick(context,data,now)
    }
    private fun scheduleWidgetTick(context:Context,data:AppData,now:ZonedDateTime){
        val tick=PendingIntent.getBroadcast(context,5,Intent(context,ReminderReceiver::class.java).setAction("app.kejian.WIDGET_TICK"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms=context.getSystemService(AlarmManager::class.java);alarms.cancel(tick)
        if(!KejianWidgets.hasWidgets(context))return
        val boundary=upcoming(data,now).flatMap { listOf(it.startAt(now.zone).minusHours(24),it.startAt(now.zone),it.endAt(now.zone)) }.filter { it.isAfter(now) }.minOrNull()?:now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC, boundary.toInstant().toEpochMilli(),tick)
    }
    fun notify(context:Context,course:Course,minutes:Int=10,test:Boolean=false):NotificationReport {
        createChannel(context)
        blockedReason(context)?.let { return it }
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).apply { data=Uri.parse("kejian://course/${course.id}");putExtra("courseId",course.id) },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val en=english(context)
        val title=if(test)if(en)"Kejian test notification"else"课间测试通知" else if(minutes>0)if(en)"${course.name} starts in $minutes min"else"${course.name}将在 $minutes 分钟后开始" else if(en)"${course.name} has started"else"${course.name}已经开始"
        val place=if(en&&course.address.isBlank()&&course.room.isBlank())"No location"else course.place
        val detail="${timeText(course.start)}–${timeText(course.end)}\n${if(en)"Location"else"课堂地址"}：$place"+(if(course.teacher.isNotBlank())"\n${if(en)"Host / teacher"else"教师"}：${course.teacher}" else "")
        val notification=NotificationCompat.Builder(context,activeChannel(context)).setSmallIcon(R.drawable.ic_notification).setLargeIcon(NotificationArtwork.large(context)).setContentTitle(title).setContentText(course.place).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).setOnlyAlertOnce(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try {
            NotificationManagerCompat.from(context).notify(if(test)"test" else course.id,1,notification)
            val manager=context.getSystemService(NotificationManager::class.java)
            NotificationReport(true,if(test)"测试通知已提交系统"else "课前提醒已发送",bannerStatus(context)+"\n测试时保持屏幕亮起，并检查屏幕顶部横幅；锁屏时显示方式由系统决定。应用无法越过勿扰或关闭的通知设置。","channel")

        }catch(_:SecurityException){NotificationReport(false,"通知权限被系统拒绝","通知权限可能刚被关闭，请在系统设置中允许课间发送通知。","app")}
        catch(e:Exception){NotificationReport(false,"通知未能发送",e.message?:"系统拒绝发送，请检查权限。","app")}
    }
    fun notifyDeadline(context:Context,deadline:Deadline):NotificationReport {
        createChannel(context)
        blockedReason(context)?.let {return it}
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).apply {data=Uri.parse("kejian://deadline/${deadline.id}");putExtra("deadlineId",deadline.id)},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val en=english(context);val time=deadline.dueMinute?.let(::timeText)?:if(en)"today"else"今天"
        val detail=buildString {append("${if(en)"Due"else"截止时间"}：${deadline.dueDate} $time");if(deadline.details.isNotBlank())append("\n${deadline.details}")}
        val notification=NotificationCompat.Builder(context,activeChannel(context)).setSmallIcon(R.drawable.ic_notification).setLargeIcon(NotificationArtwork.large(context)).setContentTitle(if(en)"${deadline.title} is due"else"${deadline.title} 截止").setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL).setOnlyAlertOnce(false).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try {NotificationManagerCompat.from(context).notify(deadline.id,2,notification);NotificationReport(true,"截止提醒已发送",deadline.title,"channel")}
        catch(_:SecurityException){NotificationReport(false,"通知权限被系统拒绝","请在系统设置中允许课间发送通知。","app")}
        catch(e:Exception){NotificationReport(false,"截止提醒未能发送",e.message?:"系统拒绝发送，请检查权限。","app")}
    }
}

class ReminderReceiver: BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent){
        val data=runCatching { CourseStore(context).load() }.getOrNull()?:return
        if(intent.action!="app.kejian.WIDGET_TICK"){
            val deadlineId=intent.getStringExtra("deadlineId")
            if(deadlineId!=null){
                val d=data.deadlines.find {it.id==deadlineId};val expectedDate=runCatching {LocalDate.parse(intent.getStringExtra("date"))}.getOrNull();val minute=intent.getIntExtra("deadlineMinute",-1)
                if(d!=null&&expectedDate==d.dueDate&&data.settings.reminders&&d.reminder){
                    val now=ZonedDateTime.now();val effectiveMinute=d.dueMinute?:9*60;val due=d.dueDate.atTime(effectiveMinute/60,effectiveMinute%60).atZone(now.zone);val key="deadline|${d.id}|${d.dueDate}|$effectiveMinute";val prefs=context.getSharedPreferences("kejian_alarms",Context.MODE_PRIVATE);val delivered=prefs.getStringSet("delivered",emptySet()).orEmpty()
                    if(minute==effectiveMinute&&Duration.between(due,now).toMinutes() in -5..10&&key !in delivered&&ReminderScheduler.notificationAllowed(context)&&ReminderScheduler.notifyDeadline(context,d).sent)prefs.edit().putStringSet("delivered",(delivered.filter {item->deliveredDate(item)?.let {it>=now.toLocalDate().minusDays(7)}==true}+key).toSet()).apply()
                }
            }
            val id=intent.getStringExtra("courseId");val c=data.courses.find { it.id==id }
            val date=runCatching { LocalDate.parse(intent.getStringExtra("date")) }.getOrNull()
            if(c!=null&&date!=null&&data.settings.reminders&&c.reminder&&c.occurs(date,data.settings.termStart)){
                val now=ZonedDateTime.now();val start=Occurrence(c,date).startAt(now.zone)
                val minutes=Duration.between(now,start).toMinutes().toInt()
                val prefs=context.getSharedPreferences("kejian_alarms",Context.MODE_PRIVATE)
                val delivered=prefs.getStringSet("delivered",emptySet()).orEmpty()
                val key="${c.id}|$date|${c.start}"
                if(minutes in -5..(data.settings.reminderMinutes+5)&&key !in delivered&&ReminderScheduler.notificationAllowed(context)){
                    if(ReminderScheduler.notify(context,c,minutes.coerceAtLeast(0)).sent)prefs.edit().putStringSet("delivered",(delivered.filter {item->deliveredDate(item)?.let {it>=now.toLocalDate().minusDays(7)}==true}+key).toSet()).apply()
                }
            }
        }
        KejianWidgets.refreshAll(context);ReminderScheduler.reschedule(context,data)
    }
}
class SystemReceiver: BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent){
    if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))return
    runCatching { val data=CourseStore(context).load();ReminderScheduler.reschedule(context,data);KejianWidgets.refreshAll(context) }
}}
