package app.kejian.mobile

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.*
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import java.time.*
import kotlin.random.Random
import kotlin.math.roundToInt

object KejianWidgets {
    const val ACTION_PINNED="app.kejian.mobile.action.WIDGET_PINNED"
    val providers=listOf(SmallWidget::class.java,SquareWidget::class.java,TallWidget::class.java,WideWidget::class.java,StripWidget::class.java,QuoteWidget::class.java,WeatherWidget::class.java)
    fun hasWidgets(context:Context)=providers.any { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,it)).isNotEmpty() }
    fun refreshAll(context:Context){UtilityWidgets.schedule(context);UtilityWidgets.notifyAgenda(context);val manager=AppWidgetManager.getInstance(context);providers.forEach { type -> manager.getAppWidgetIds(ComponentName(context,type)).forEach { id -> update(context,manager,id,type) } }}
    fun update(context:Context,manager:AppWidgetManager,id:Int,type:Class<out BaseCourseWidget>){
        val data=runCatching { CourseStore(context).load() }.getOrNull()?:return
        val options=manager.getAppWidgetOptions(id)
        val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,if(type==SmallWidget::class.java)70 else 180).coerceAtLeast(1)
        val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,if(type==TallWidget::class.java)320 else if(type==StripWidget::class.java)70 else 170).coerceAtLeast(1)
        val portraitHeight=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,height).coerceAtLeast(height)
        val landscapeWidth=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,width).coerceAtLeast(width)
        // MIN_WIDTH pairs with MAX_HEIGHT in portrait; using both minima distorted tall corners.
        val kind=when(type){QuoteWidget::class.java->"quote";WeatherWidget::class.java->"weather";else->"course"}
        val portrait=render(context,data,width,portraitHeight,id,kind=kind)
        val landscape=render(context,data,landscapeWidth,height,id,kind=kind)
        manager.updateAppWidget(id,RemoteViews(landscape,portrait))
    }
    fun render(context:Context,data:AppData,width:Int,height:Int,id:Int=0,tutorial:Boolean=false,kind:String="course"):RemoteViews {
        if(kind!="course")return UtilityWidgets.render(context,data,width,height,id,kind,tutorial)
        val basePalette=AppPalettes.forContext(context,data.settings.themeMode,data.settings.skin)
        val imageMode=(ProductAccess.supporterAppearance||tutorial)&&data.settings.widgetBackgroundMode=="image"&&data.settings.widgetBackgroundUri!=null
        val textSettings=if(imageMode&&data.settings.widgetImageLuminance==null)data.settings.copy(widgetImageLuminance=WidgetMaterials.imageLuminance(context,data.settings.widgetBackgroundUri!!))else data.settings
        val imageInk=WidgetMaterials.textColor(textSettings)
        val palette=if(imageMode&&textSettings.widgetImageLuminance!=null)basePalette.copy(ink=imageInk,brand=imageInk,muted=imageInk)else basePalette
        val strip=height<105&&width>=95
        val compact=width<130&&!strip;val tall=height>=250;val wide=width>=250&&!strip
        val insets=WidgetGeometry.insets(data.settings.widgetStyle,width,height)
        val density=context.resources.displayMetrics.density
        fun applyAppearance(views:RemoteViews,contentId:Int) {
            views.setImageViewBitmap(android.R.id.background,WidgetMaterials.background(context,data.settings,palette,width,height,tutorial))
            views.setViewPadding(contentId,(insets.left*density).roundToInt(),(insets.top*density).roundToInt(),(insets.right*density).roundToInt(),(insets.bottom*density).roundToInt())
        }
        val entries=widgetOccurrences(data);val next=entries.firstOrNull();val c=next?.course
        if(c==null){
            val empty=RemoteViews(context.packageName,R.layout.widget_empty)
            applyAppearance(empty,R.id.widget_empty_content)
            val english=data.settings.language=="en"
            val quotes=if(english)listOf("Leave a little room in the day for yourself.","Rest is part of moving forward.","A quiet hour can still be time well spent.") else listOf("给时间留一点空白，也是在认真生活。","休息不是停下，是为下一次出发留力。","日程之外，也要记得照顾自己。")
            empty.setTextColor(R.id.widget_title,palette.ink)
            empty.setTextColor(R.id.widget_empty_label,palette.brand);empty.setTextColor(R.id.widget_empty_divider,palette.brand);empty.setTextColor(R.id.widget_empty_quote,palette.muted)
            empty.setTextViewText(R.id.widget_empty_label,if(english)"KEJIAN · NEXT 24 HOURS" else "课间 · 接下来 24 小时")
            empty.setTextViewText(R.id.widget_title,if(english)"Nothing scheduled.\nTake a little break." else "24小时内没有课程，\n休息一下吧~")
            empty.setTextViewText(R.id.widget_empty_quote,quotes[LocalDate.now().dayOfYear%quotes.size])
            empty.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,if(compact||strip)11f else 15f)
            for(viewId in listOf(R.id.widget_empty_label,R.id.widget_empty_divider,R.id.widget_empty_quote))empty.setViewVisibility(viewId,if(compact||strip)View.GONE else View.VISIBLE)
            val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            empty.setOnClickPendingIntent(R.id.widget_root,open)
            return empty
        }
        val views=RemoteViews(context.packageName,if(strip)R.layout.widget_strip else if(wide)R.layout.widget_wide else R.layout.widget_course)
        applyAppearance(views,R.id.widget_content)
        views.setImageViewBitmap(R.id.widget_color_tag,WidgetMaterials.tag(palette.accents[c?.color?:0]))
        views.setContentDescription(R.id.widget_color_tag,if(data.settings.language=="en")"Event color ${c?.color?:0}" else "课程颜色 ${c?.color?:0}")
        for(textId in listOf(R.id.widget_time,R.id.widget_title,R.id.widget_agenda))views.setTextColor(textId,palette.ink)
        views.setTextColor(R.id.widget_place,palette.muted)
        views.setTextColor(R.id.widget_label,palette.brand);views.setTextColor(R.id.widget_hint,palette.brand)
        val today=LocalDate.now()
        val english=data.settings.language=="en"
        val dateLabel=when(next?.date){today->if(english)"Today"else"今天";today.plusDays(1)->if(english)"Tomorrow"else"明天";null->if(english)"Kejian"else"课间";else->"${next.date.monthValue}/${next.date.dayOfMonth}"}
        val ongoing=next?.startAt()?.isAfter(ZonedDateTime.now())==false
        views.setTextViewText(R.id.widget_label,if(ongoing)if(english)"IN PROGRESS"else"正在上课" else if(compact||strip)dateLabel else (if(english)"KEJIAN / "else"课间 / ")+dateLabel)
        views.setTextViewText(R.id.widget_time,if(c!=null)timeText(c.start) else if(english)"Free"else"空闲")
        val clock=if(compact)if(width<85)22f else 26f else if(strip)26f else if(!wide&&!tall)28f else 30f
        val available=(width-insets.left-insets.right).coerceAtLeast(1)*(if(strip).58f else if(wide).5f else 1f)-(if(wide)7f else 0f)
        val clockPaint=Paint().apply {typeface=Typeface.create("sans-serif-condensed-medium",Typeface.NORMAL)}
        fun clockFits(sp:Float):Boolean {
            clockPaint.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,sp,context.resources.displayMetrics)
            return clockPaint.measureText(timeText(c.start))<=available*density-1
        }
        var fittedClock=clock
        if(!clockFits(clock)){
            var low=1f;var high=clock
            repeat(12){val mid=(low+high)/2;if(clockFits(mid))low=mid else high=mid}
            fittedClock=low
        }
        views.setTextViewTextSize(R.id.widget_time,android.util.TypedValue.COMPLEX_UNIT_SP,fittedClock)
        views.setTextViewText(R.id.widget_title,c?.name?:"暂时没有课程")
        // Narrow 1×2 cells keep the title on one line more often after adding safer gutters.
        views.setTextViewTextSize(R.id.widget_title,android.util.TypedValue.COMPLEX_UNIT_SP,if(compact)10f else if(strip)12f else 15f)
        views.setInt(R.id.widget_title,"setMaxLines",if(compact)2 else 1)
        views.setTextViewText(R.id.widget_place,if(compact||strip)c?.room?.ifBlank {c.address}?.ifBlank {if(english)"No location"else"未填地址"}?:if(english)"Tap to add"else"打开添加"else c?.let {if(english&&it.address.isBlank()&&it.room.isBlank())"No location" else it.place}?:if(english)"Open Kejian to add an event"else"打开课间安排课程")
        views.setInt(R.id.widget_place,"setMaxLines",if(tall||wide)2 else 1)
        views.setTextViewText(R.id.widget_hint,if(c!=null)"${timeText(c.end)} ${if(english)"ends"else"结束"}"else if(english)"Tap to add"else"点击添加")
        views.setTextViewTextSize(R.id.widget_hint,android.util.TypedValue.COMPLEX_UNIT_SP,if(compact)8f else if(strip)9f else 10f)
        views.setViewVisibility(R.id.widget_hint,View.VISIBLE)
        val contentHeight=height-insets.top-insets.bottom
        val count=when {strip||compact->0;wide->2;tall->if(contentHeight>=300)3 else 2;contentHeight>=135->1;else->0}
        val agenda=android.text.SpannableStringBuilder()
        entries.drop(1).take(count).forEachIndexed {index,o->
            if(index>0)agenda.append(if(tall||wide)"\n\n"else "\n")
            val dot=agenda.length;agenda.append("● ")
            agenda.setSpan(android.text.style.ForegroundColorSpan(palette.accents[o.course.color]),dot,dot+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            agenda.append("${o.date.monthValue}/${o.date.dayOfMonth} ${timeText(o.course.start)}"+(if(tall||wide)"\n"else " · ")+o.course.name)
            if(tall||wide)agenda.append("\n${o.course.place}")
        }
        views.setTextViewText(R.id.widget_agenda,agenda.ifEmpty {if(english)"No other events in 24 hours"else"24小时内暂无其他课程"})
        views.setInt(R.id.widget_agenda,"setMaxLines",if(tall||wide)15 else 1)
        views.setTextViewTextSize(R.id.widget_agenda,android.util.TypedValue.COMPLEX_UNIT_SP,if(tall)12f else 11f)
        views.setViewVisibility(R.id.widget_agenda,if(count>0)View.VISIBLE else View.GONE)
        val open=PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java).apply { putExtra("courseId",c?.id);putExtra("date",next?.date?.toString()) },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root,open)
        return views
    }
}
class WidgetPinResultReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=KejianWidgets.ACTION_PINNED)return
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID)return
        val manager=AppWidgetManager.getInstance(context)
        val provider=KejianWidgets.providers.firstOrNull {manager.getAppWidgetIds(ComponentName(context,it)).contains(id)}?:return
        KejianWidgets.update(context,manager,id,provider)
        val english=runCatching {CourseStore(context).load().settings.language=="en"}.getOrDefault(false)
        Toast.makeText(context,if(english)"Kejian widget added to your Home screen"else"课间小组件已添加到桌面",Toast.LENGTH_LONG).show()
    }
}
open class BaseCourseWidget:AppWidgetProvider(){
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach { KejianWidgets.update(context,manager,it,javaClass) };runCatching { ReminderScheduler.reschedule(context) }}
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:Bundle){KejianWidgets.update(context,manager,id,javaClass)}
    override fun onDisabled(context:Context){runCatching { ReminderScheduler.reschedule(context) }}
}
class SmallWidget:BaseCourseWidget()
class SquareWidget:BaseCourseWidget()
class TallWidget:BaseCourseWidget()
class WideWidget:BaseCourseWidget()
class StripWidget:BaseCourseWidget()
