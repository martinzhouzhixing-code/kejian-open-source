package app.kejian.mobile

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.*
import kotlin.math.roundToInt

class QuoteWidget:BaseCourseWidget()
class WeatherWidget:BaseCourseWidget()
data class WeatherCity(val name:String,val lat:Double,val lon:Double)
object UtilityWidgets {
    private const val JOB=23001
    const val NOTIFICATION=23002
    val cities=listOf(WeatherCity("北京",39.90,116.40),WeatherCity("上海",31.23,121.47),WeatherCity("广州",23.13,113.26),WeatherCity("深圳",22.54,114.06),WeatherCity("杭州",30.27,120.15),WeatherCity("南京",32.06,118.80),WeatherCity("武汉",30.59,114.31),WeatherCity("成都",30.57,104.07),WeatherCity("重庆",29.56,106.55),WeatherCity("西安",34.34,108.94),WeatherCity("天津",39.08,117.20),WeatherCity("郑州",34.75,113.63),WeatherCity("长沙",28.23,112.94),WeatherCity("合肥",31.82,117.23),WeatherCity("福州",26.07,119.30),WeatherCity("厦门",24.48,118.09),WeatherCity("济南",36.65,117.12),WeatherCity("青岛",36.07,120.38),WeatherCity("沈阳",41.81,123.43),WeatherCity("大连",38.91,121.61),WeatherCity("哈尔滨",45.80,126.53),WeatherCity("长春",43.82,125.32),WeatherCity("南昌",28.68,115.86),WeatherCity("南宁",22.82,108.32),WeatherCity("昆明",24.88,102.83),WeatherCity("贵阳",26.65,106.63),WeatherCity("海口",20.02,110.35),WeatherCity("太原",37.87,112.55),WeatherCity("石家庄",38.04,114.51),WeatherCity("呼和浩特",40.84,111.75),WeatherCity("兰州",36.06,103.83),WeatherCity("西宁",36.62,101.78),WeatherCity("银川",38.49,106.23),WeatherCity("乌鲁木齐",43.83,87.62),WeatherCity("拉萨",29.65,91.14),WeatherCity("香港",22.32,114.17),WeatherCity("澳门",22.20,113.54),WeatherCity("台北",25.03,121.57))
    fun prefs(c:Context)=c.getSharedPreferences("utility_widgets",0)
    fun city(c:Context):WeatherCity? {val p=prefs(c);val name=p.getString("city",null)?:return null;return WeatherCity(name,p.getFloat("lat",0f).toDouble(),p.getFloat("lon",0f).toDouble())}
    fun setCity(c:Context,city:WeatherCity){prefs(c).edit().putString("city",city.name).putFloat("lat",city.lat.toFloat()).putFloat("lon",city.lon.toFloat()).remove("weather").remove("weatherTime").remove("nextFetch").remove("lastModified").apply();schedule(c)}
    fun schedule(c:Context){
        val scheduler=c.getSystemService(JobScheduler::class.java)
        if(!prefs(c).getBoolean("lockscreen",false)){scheduler.cancel(JOB);return}
        if(scheduler.getPendingJob(JOB)==null)scheduler.schedule(JobInfo.Builder(JOB,ComponentName(c,UtilityWidgetJob::class.java)).setPeriodic(60*60*1000L).setPersisted(true).build())
    }
    /** Hourly cache, bounded response, finite timeouts. Does not request GPS or run a foreground service. */
    fun refreshWeather(c:Context):Boolean {
        val selected=city(c)?:return false;val p=prefs(c);val now=System.currentTimeMillis()
        if(now<p.getLong("nextFetch",0))return false
        p.edit().putLong("nextFetch",now+60*60*1000L).apply()
        val connection=URL("https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${String.format(java.util.Locale.US,"%.4f",selected.lat)}&lon=${String.format(java.util.Locale.US,"%.4f",selected.lon)}").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=15_000;connection.readTimeout=20_000
            connection.setRequestProperty("User-Agent","Kejian/2.3 https://kejian.im/")
            p.getString("lastModified",null)?.let {connection.setRequestProperty("If-Modified-Since",it)}
            if(connection.responseCode==304){p.edit().putLong("nextFetch",maxOf(now+60*60*1000L,connection.expiration)).remove("weatherError").apply();return false}
            require(connection.responseCode in 200..299){"天气服务暂不可用"}
            val bytes=connection.inputStream.use {input->val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(8192);while(true){val n=input.read(chunk);if(n<0)break;require(out.size()+n<=2_000_000);out.write(chunk,0,n)};out.toByteArray()}
            val series=JSONObject(bytes.toString(Charsets.UTF_8)).getJSONObject("properties").getJSONArray("timeseries")
            val time=Instant.now();val row=(0 until series.length()).map {series.getJSONObject(it)}.minByOrNull {kotlin.math.abs(Duration.between(time,Instant.parse(it.getString("time"))).seconds)}?:error("没有天气数据")
            if(city(c)!=selected)return false
            p.edit().putString("lastModified",connection.getHeaderField("Last-Modified")).putString("weather",row.toString()).putLong("weatherTime",now).putLong("nextFetch",maxOf(now+60*60*1000L,connection.expiration)).remove("weatherError").apply()
            return true
        }catch(e:Exception){p.edit().putBoolean("weatherError",true).apply();return false}finally{connection.disconnect()}
    }
    fun render(c:Context,data:AppData,width:Int,height:Int,id:Int,kind:String,tutorial:Boolean=false):RemoteViews {
        val s=data.settings;val en=s.language=="en";var palette=AppPalettes.forContext(c,s.themeMode,s.skin)
        if((ProductAccess.supporterAppearance||tutorial)&&s.widgetBackgroundMode=="image"&&s.widgetBackgroundUri!=null){val ink=WidgetMaterials.textColor(s.copy(widgetImageLuminance=s.widgetImageLuminance?:WidgetMaterials.imageLuminance(c,s.widgetBackgroundUri)));palette=palette.copy(ink=ink,brand=ink,muted=ink)}
        val views=RemoteViews(c.packageName,R.layout.widget_empty)
        views.setImageViewBitmap(android.R.id.background,WidgetMaterials.background(c,s,palette,width,height,tutorial))
        val inset=WidgetGeometry.insets(s.widgetStyle,width,height);val density=c.resources.displayMetrics.density
        views.setViewPadding(R.id.widget_empty_content,(inset.left*density).roundToInt(),(inset.top*density).roundToInt(),(inset.right*density).roundToInt(),(inset.bottom*density).roundToInt())
        listOf(R.id.widget_title,R.id.widget_empty_label,R.id.widget_empty_quote,R.id.widget_empty_divider).forEach {views.setTextColor(it,palette.ink)}
        val small=height<130||width<130
        views.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,if(small)12f else if(kind=="weather")24f else 18f)
        views.setInt(R.id.widget_title,"setMaxLines",if(small)3 else 5)
        views.setViewVisibility(R.id.widget_empty_divider,if(small)android.view.View.GONE else android.view.View.VISIBLE)
        if(kind=="quote"){
            val quotes=if(en)listOf("Know thyself." to "Delphic maxim","Well begun is half done." to "Aristotle","The unexamined life is not worth living." to "Socrates") else listOf("学而不思则罔，思而不学则殆。" to "《论语》","不积跬步，无以至千里。" to "《荀子》","纸上得来终觉浅，绝知此事要躬行。" to "陆游","会当凌绝顶，一览众山小。" to "杜甫","千里之行，始于足下。" to "《道德经》","非学无以广才，非志无以成学。" to "诸葛亮","长风破浪会有时，直挂云帆济沧海。" to "李白")
            val quote=quotes[Math.floorMod(LocalDate.now().toEpochDay(),quotes.size.toLong()).toInt()]
            views.setTextViewText(R.id.widget_empty_label,if(en)"KEJIAN · DAILY QUOTE" else "课间 · 今日名句")
            views.setTextViewText(R.id.widget_title,quote.first);views.setTextViewText(R.id.widget_empty_quote,quote.second)
        }else{
            val p=prefs(c);val row=runCatching {JSONObject(p.getString("weather","").orEmpty())}.getOrNull()
            val detail=row?.optJSONObject("data")?.optJSONObject("instant")?.optJSONObject("details")
            val updated=p.getLong("weatherTime",0);val stale=System.currentTimeMillis()-updated>3*60*60*1000L
            val temp=detail?.optDouble("air_temperature",Double.NaN)?.takeIf {it.isFinite()}
            views.setTextViewText(R.id.widget_empty_label,(city(c)?.name?:if(en)"Select city in Widgets" else "请在小组件页选择城市")+if(stale&&temp!=null){if(en)" · Older forecast" else " · 较早预报"}else "")
            views.setTextViewText(R.id.widget_title,temp?.let {"${it.roundToInt()}°C"}?:if(en)"Awaiting forecast" else "等待天气更新")
            val timeLabel=if(updated>0)Instant.ofEpochMilli(updated).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)else "—"
            views.setTextViewText(R.id.widget_empty_quote,(if(en)"MET Norway · forecast $timeLabel" else "MET Norway · 预报 $timeLabel")+if(p.getBoolean("weatherError",false)){if(en)" · Offline" else " · 离线"}else "")
        }
        val open=PendingIntent.getActivity(c,230000+id,Intent(c,MainActivity::class.java).putExtra("openWidgets",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root,open);return views
    }
    fun notifyAgenda(c:Context){
        val manager=c.getSystemService(NotificationManager::class.java)
        if(!prefs(c).getBoolean("lockscreen",false)){manager.cancel(NOTIFICATION);return}
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        manager.createNotificationChannel(NotificationChannel("agenda_card","课间 · 锁屏日程",NotificationManager.IMPORTANCE_LOW))
        val data=runCatching {CourseStore(c).load()}.getOrNull()?:return;val en=data.settings.language=="en"
        val entries=upcoming(data).take(3)
        val text=entries.joinToString("\n"){"${it.date.monthValue}/${it.date.dayOfMonth} ${timeText(it.course.start)}  ${it.course.name}"}.ifEmpty {if(en)"No upcoming classes" else "暂时没有后续课程"}
        val open=PendingIntent.getActivity(c,NOTIFICATION,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val hidden=NotificationCompat.Builder(c,"agenda_card").setSmallIcon(R.drawable.ic_calendar).setContentTitle(if(en)"Kejian schedule" else "课间日程").setContentText(if(en)"Unlock to view your schedule" else "解锁后查看日程").build()
        val notification=NotificationCompat.Builder(c,"agenda_card").setSmallIcon(R.drawable.ic_calendar).setContentTitle(if(en)"Your upcoming schedule" else "接下来的安排").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setShowWhen(false).setVisibility(if(prefs(c).getBoolean("showLockedDetails",false))NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(hidden).build()
        manager.notify(NOTIFICATION,notification)
    }
}

class UtilityWidgetJob:JobService(){
    private var job:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        job=CoroutineScope(Dispatchers.IO).launch {try {KejianWidgets.refreshAll(this@UtilityWidgetJob)}finally{if(isActive)jobFinished(params,false)}}
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {job?.cancel();return true}
}

@Composable fun UtilityWidgetSettings(onRefresh:()->Unit){
    val c=LocalContext.current;val en=AppLanguage.english;val scope=rememberCoroutineScope();val p=remember {UtilityWidgets.prefs(c)}
    var city by remember {mutableStateOf(UtilityWidgets.city(c))};var choosing by remember {mutableStateOf(false)}
    var name by remember {mutableStateOf("")};var lat by remember {mutableStateOf("")};var lon by remember {mutableStateOf("")}
    var lock by remember {mutableStateOf(p.getBoolean("lockscreen",false))};var details by remember {mutableStateOf(p.getBoolean("showLockedDetails",false))}
    var message by remember {mutableStateOf<String?>(null)};var loading by remember {mutableStateOf(false)}
    LaunchedEffect(city){if(city!=null){withContext(Dispatchers.IO){UtilityWidgets.refreshWeather(c);KejianWidgets.refreshAll(c)};onRefresh()}}
    fun updateLock(value:Boolean){lock=value;p.edit().putBoolean("lockscreen",value).apply();UtilityWidgets.schedule(c);UtilityWidgets.notifyAgenda(c)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)updateLock(true)else message=if(en)"Allow notifications in system settings to show the card." else "需要允许通知，才能显示锁屏日程卡片。"}
    WhiteCard {
        Text(if(en)"Weather & lock screen" else "天气与锁屏",style=MaterialTheme.typography.titleMedium)
        TextButton({choosing=true}){Text((if(en)"Weather city: " else "天气城市：")+(city?.name?:if(en)"Choose" else "请选择"))}
        Text(if(en)"Opening this page refreshes the forecast, at most hourly. MET Norway receives the selected coordinates and your IP address. No GPS permission is needed. Data: MET Norway, CC BY 4.0." else "打开此页时更新预报，每小时至多一次。MET Norway 会收到所选坐标与网络 IP，不申请定位权限。离开后显示缓存。数据来源 MET Norway，CC BY 4.0。",color=Muted,style=MaterialTheme.typography.bodySmall)
        TextButton({c.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://api.met.no/doc/License")))}){Text(if(en)"Weather source & license" else "天气数据来源与许可")}
        TextButton({loading=true;scope.launch {val changed=withContext(Dispatchers.IO){val ok=UtilityWidgets.refreshWeather(c);KejianWidgets.refreshAll(c);ok};loading=false;onRefresh();message=if(changed){if(en)"Forecast updated" else "天气已更新"}else{if(en)"Showing cached data, or awaiting the next refresh. Check your network if no forecast is available." else "已显示缓存，或等待下一轮更新。若无天气数据，请检查网络后稍后重试。"}}},enabled=city!=null&&!loading){Text(if(loading)"…" else if(en)"Refresh forecast" else "刷新天气")}
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(if(en)"Lock-screen agenda notification" else "锁屏日程通知",Modifier.weight(1f));GlassSwitch(lock,{if(it&&Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(c,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)else updateLock(it)})}
        if(lock)Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(if(en)"Show course names while locked" else "锁屏时显示课程名称",Modifier.weight(1f));GlassSwitch(details,{details=it;p.edit().putBoolean("showLockedDetails",it).apply();UtilityWidgets.notifyAgenda(c)})}
        Text(if(en)"Notification layout and lock-screen visibility depend on your OS. Native lock-screen widgets also require launcher support; Zhuoyitong may not expose them." else "这是系统通知卡片，样式及锁屏是否显示由系统设置决定。原生锁屏小组件另需系统支持，卓易通可能无法提供。默认隐藏锁屏课程详情。",color=Muted,style=MaterialTheme.typography.bodySmall)
    }
    if(choosing)GlassAlertDialog(onDismissRequest={choosing=false},title={Text(if(en)"Choose weather location" else "选择天气位置")},text={Column {
        var query by remember {mutableStateOf("")}
        OutlinedTextField(query,{query=it},label={Text(if(en)"Search cities" else "搜索城市")})
        androidx.compose.foundation.lazy.LazyColumn(Modifier.height(170.dp)){items(UtilityWidgets.cities.filter {query in it.name}.size){i->val item=UtilityWidgets.cities.filter {query in it.name}[i];TextButton({city=item;UtilityWidgets.setCity(c,item);choosing=false;onRefresh()}){Text(item.name)}}}
        Text(if(en)"Other locations: enter a name and coordinates" else "其他地区：输入名称与经纬度",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{name=it},label={Text(if(en)"Location name" else "地区名称")})
        Row {OutlinedTextField(lat,{lat=it},label={Text(if(en)"Latitude" else "纬度")},modifier=Modifier.weight(1f));OutlinedTextField(lon,{lon=it},label={Text(if(en)"Longitude" else "经度")},modifier=Modifier.weight(1f))}
    }},confirmButton={TextButton({val x=lat.toDoubleOrNull();val y=lon.toDoubleOrNull();if(name.isNotBlank()&&x!=null&&y!=null&&x in -90.0..90.0&&y in -180.0..180.0){val item=WeatherCity(name.take(40),x,y);city=item;UtilityWidgets.setCity(c,item);choosing=false;onRefresh()}else message=if(en)"Enter a valid name, latitude and longitude." else "请填写地区名称与有效经纬度。"}){Text(if(en)"Save custom location" else "保存自定义地区")}},dismissButton={TextButton({choosing=false}){Text(if(en)"Cancel" else "取消")}})
    message?.let {m->GlassAlertDialog(onDismissRequest={message=null},text={Text(m)},confirmButton={TextButton({message=null}){Text(if(en)"OK" else "知道了")}})}
}
