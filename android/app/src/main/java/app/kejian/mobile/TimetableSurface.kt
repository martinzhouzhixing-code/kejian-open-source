package app.kejian.mobile

import android.animation.*
import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import java.time.LocalDate
import kotlin.math.*

data class ModuleSelection(val courseId:String,val date:LocalDate)

class TimetableView(context:Context):View(context) {
    var customBackdrop:Bitmap?=null;set(v){if(field!==v){field=v;glassGridKey=null;invalidate()}}
    private val liquidSupported=liquidGlassSupported(context)
    private var courseLens:CourseLiquidLens?=null
    var appData=AppData();set(v){if(field!=v){field=v;clampScroll();glassGridKey=null;updateAccessibilityDescription();invalidate()}}
    var palette=AppPalettes.light;set(v){if(field!=v){field=v;invalidate()}}
    var week=monday(LocalDate.now());set(v){if(field!=v){cancelDrag();field=v;clampScroll();glassGridKey=null;invalidate()}}
    var nextOccurrence:Occurrence?=null;set(v){if(field!=v){field=v;invalidate()}}
    var onEdit:(Course,LocalDate)->Unit={_,_->};
    var onDeadline:(List<Deadline>)->Unit={}
    var onSelection:(Course?,LocalDate?,RectF?)->Unit={_,_,_->}
    var hideSelectedCourse=false;set(v){if(field!=v){field=v;invalidate()}}
    var editingEnabled=false;set(v){if(field!=v){field=v;clampScroll();glassGridKey=null;cancelDrag();clearSelection();modeAnimator?.cancel();modeAnimator=ValueAnimator.ofFloat(modeAmount,if(v)1f else 0f).apply {duration=motionDuration(260);interpolator=ease;addUpdateListener {modeAmount=it.animatedValue as Float;invalidate()};start()}}}
    var batchDeleteEnabled=false;set(v){if(field!=v){field=v;cancelDrag();clearSelection();batchSelected.clear();selectionBox=null;selectingBox=false;onBatchSelection(emptySet());invalidate()}}
    var onBatchSelection:(Set<ModuleSelection>)->Unit={}
    private val batchSelected=linkedSetOf<ModuleSelection>()
    private var selectionBox:RectF?=null;private var selectingBox=false
    private var selectStartX=0f;private var selectStartY=0f;private var selectCurrentX=0f;private var selectCurrentY=0f
    private var modeAmount=0f;private var modeAnimator:ValueAnimator?=null
    private var settlingCourse:Course?=null
    private var selected:Course?=null;private var selectionAmount=0f;private var selectionAnimator:ValueAnimator?=null
    fun clearSelection(){
        val old=selected?:return;selected=null;selectionAnimator?.cancel();onSelection(null,null,null)
        if(editingEnabled&&selectionAmount>0f){
            settlingCourse=old
            selectionAnimator=ValueAnimator.ofFloat(selectionAmount,0f).apply {
                duration=motionDuration(220);interpolator=ease
                addUpdateListener {selectionAmount=it.animatedValue as Float;if(selectionAmount<=.001f)settlingCourse=null;invalidate()};start()
            }
        }else {settlingCourse=null;selectionAmount=0f};invalidate()
    }
    private fun select(c:Course,r:RectF){if(selected?.id==c.id){clearSelection();return};selected=c;frameKey=null;settlingCourse=null;selectionAnimator?.cancel();selectionAnimator=ValueAnimator.ofFloat(0f,1f).apply {duration=motionDuration(220);interpolator=ease;addUpdateListener {selectionAmount=it.animatedValue as Float;invalidate()};start()};onSelection(c,week.plusDays((c.day-1).toLong()),RectF(r));invalidate()}
    fun demoSelect(id:String){hits.firstOrNull {it.first.id==id}?.let{select(it.first,it.second)}}
    fun demoMove(id:String,start:Int){
        val hit=hits.firstOrNull {it.first.id==id}?:return
        val c=hit.first;val dest=slot(week.plusDays((c.day-1).toLong()),start,c.duration)
        landing=Landing(c,RectF(hit.second),dest,false,false)
        landingAnimator?.cancel();landingAnimator=ValueAnimator.ofFloat(0f,1f).apply{duration=700;interpolator=ease;addUpdateListener{landing?.progress=it.animatedValue as Float;invalidate()};start()}
        postDelayed({landing=null;invalidate()},1800)
    }
    var onCreate:()->Unit={}
    var onDrop:(Course,LocalDate,LocalDate,Int,Boolean)->Unit={_,_,_,_,_->}
    var onDelete:(Course,LocalDate)->Unit={_,_->}
    var onDragStateChange:(Boolean)->Unit={}
    var onZoomChanged:(Float)->Unit={}
    var onAxisZoomChanged:(Float,Float)->Unit={_,_->}
    var zoomX=1f;private set
    var zoomY=1f;private set
    var zoom:Float
        get()=zoomX
        set(value){setAxisZoom(value,value)}
    fun setAxisZoom(x:Float,y:Float){
        if(!x.isFinite()||!y.isFinite())return
        val xx=x.coerceIn(.65f,1.8f);val yy=y.coerceIn(.65f,1.8f)
        if(abs(zoomX-xx)<.001f&&abs(zoomY-yy)<.001f)return
        val focusX=(width+gutter)/2;val focusY=(height+header)/2;val anchor=gridPositionAt(focusX,focusY)
        zoomX=xx;zoomY=yy;sx=anchor.x*col-(focusX-gutter);sy=anchor.y*minute-(focusY-header);clampScroll();invalidate()
    }
    val motionInProgress get()=drag!=null||landing!=null||liftAnimator?.isRunning==true
    val isPinching get()=multiTouch
    /** Column and minute under a screen point; useful for keeping gesture anchors stable. */
    fun gridPositionAt(x:Float,y:Float)=PointF((x-gutter+sx)/col,(y-header+sy)/minute)
    private val density=resources.displayMetrics.density
    private fun dp(x:Float)=x*density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular=Typeface.create("sans-serif",Typeface.NORMAL);private val medium=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    private val handler=Handler(Looper.getMainLooper());private val scroller=OverScroller(context)
    private val gutter get()=dp(44f);private val header get()=dp(48f)
    private val dayCount get()=visibleScheduleDays(appData,week,editingEnabled)
    private val timeRange get()=scheduleTimeRange(appData,week)
    private val col get()=max(dp(42f),(width-gutter)/dayCount)*zoomX
    private val minute get()=dp(72f)/60f*zoomY
    private var sx=0f;private var sy=dp(72f)*7
    private var downX=0f;private var downY=0f;private var lastX=0f;private var lastY=0f
    private var panning=false;private var downFab=false;private var multiTouch=false
    private var velocity:VelocityTracker?=null
    private var downCourse:Course?=null;private var downBounds:RectF?=null
    private val hits=mutableListOf<Pair<Course,RectF>>()
    private var downDeadlines:List<Deadline>?=null
    private val deadlineHits=mutableListOf<Pair<List<Deadline>,RectF>>()
    private data class Drag(val course:Course,val originalDate:LocalDate,val fresh:Boolean,val grabMinutes:Float,val grabX:Float,val source:RectF,var x:Float,var y:Float,var trashArmed:Boolean=!fresh)
    private data class Landing(val course:Course,val from:RectF,val to:RectF,val fresh:Boolean,val cancel:Boolean,val deleting:Boolean=false,var progress:Float=0f)
    private var drag:Drag?=null;private var landing:Landing?=null
    private var lift=0f;private var liftAnimator:ValueAnimator?=null;private var landingAnimator:ValueAnimator?=null
    private val ease=PathInterpolator(.2f,0f,0f,1f)
    var bottomObstruction=0f
    private val fab get()=PointF(width-dp(40f),height-bottomObstruction-dp(40f))
    private fun motionDuration(ms:Long)=if(ValueAnimator.areAnimatorsEnabled())ms else 0L
    private fun mix(a:Float,b:Float,t:Float)=a+(b-a)*t
    private fun rectMix(a:RectF,b:RectF,t:Float)=RectF(mix(a.left,b.left,t),mix(a.top,b.top,t),mix(a.right,b.right,t),mix(a.bottom,b.bottom,t))
    private fun colorMix(a:Int,b:Int,t:Float)=Color.argb(mix(Color.alpha(a).toFloat(),Color.alpha(b).toFloat(),t).toInt(),mix(Color.red(a).toFloat(),Color.red(b).toFloat(),t).toInt(),mix(Color.green(a).toFloat(),Color.green(b).toFloat(),t).toInt(),mix(Color.blue(a).toFloat(),Color.blue(b).toFloat(),t).toInt())
    private val startLongPress=Runnable {
        val c=downCourse
        if(editingEnabled&&!batchDeleteEnabled&&!panning&&!multiTouch&&(downFab||c!=null)&&landing==null){
            clearSelection()
            val course=c?:Course(day=LocalDate.now().dayOfWeek.value,color=nextCourseColor(appData.courses))
            val source=if(downFab)RectF(fab.x-dp(28f),fab.y-dp(28f),fab.x+dp(28f),fab.y+dp(28f))else RectF(downBounds!!)
            drag=Drag(course,week.plusDays((course.day-1).toLong()),downFab,if(downFab)0f else (downY-header+sy)/minute-course.start,if(downFab).5f else ((downX-source.left)/source.width()).coerceIn(0f,1f),source,downX,downY)
            onDragStateChange(true)
            parent?.requestDisallowInterceptTouchEvent(true);performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            lift=0f;liftAnimator?.cancel();liftAnimator=ValueAnimator.ofFloat(0f,1f).apply {duration=motionDuration(220);interpolator=ease;addUpdateListener {lift=it.animatedValue as Float;invalidate()};start()}
            invalidate();postOnAnimation(autoScroll)
        }
    }
    private val autoScroll=object:Runnable {override fun run(){
        val d=drag?:return
        if(!overTrash(d)&&(!d.fresh||hypot(d.x-downX,d.y-downY)>dp(20f))&&lift>.7f){
            val oldX=sx;val oldY=sy
            if(d.y<header+dp(32f))sy-=dp(5f)else if(d.y>height-dp(52f))sy+=dp(5f)
            if(d.x<gutter+dp(20f))sx-=dp(3f)else if(d.x>width-dp(20f))sx+=dp(3f)
            clampScroll();if(oldX!=sx||oldY!=sy)invalidate()
        };postOnAnimation(this)
    }}
    private val selectionAutoScroll=object:Runnable {override fun run(){
        if(!batchDeleteEnabled||!selectingBox)return
        val oldX=sx;val oldY=sy
        if(selectCurrentY<header+dp(42f))sy-=dp(7f)else if(selectCurrentY>height-dp(38f))sy+=dp(7f)
        if(selectCurrentX<gutter+dp(28f))sx-=dp(5f)else if(selectCurrentX>width-dp(24f))sx+=dp(5f)
        clampScroll();if(oldX!=sx||oldY!=sy){updateBatchFromBox();invalidate()};postOnAnimation(this)
    }}
    // Track actual finger span: platform ScaleGestureDetector can require a very wide initial span.
    private var pinchSpan=0f;private var pinchX=0f;private var pinchY=0f;private var pinchActive=false;private var pinchChanged=false
    private var pinchAxis=0
    private var pinchFirst=0;private var pinchSecond=1
    private fun beginPinch(event:MotionEvent,excluded:Int=-1){
        val indices=(0 until event.pointerCount).filter {it!=excluded};if(indices.size<2){finishPinch();return}
        pinchFirst=event.getPointerId(indices[0]);pinchSecond=event.getPointerId(indices[1])
        val a=indices[0];val b=indices[1];val dx=abs(event.getX(a)-event.getX(b));val dy=abs(event.getY(a)-event.getY(b));pinchAxis=if(dx>dy*1.75f)1 else if(dy>dx*1.75f)2 else 0;pinchSpan=hypot(event.getX(a)-event.getX(b),event.getY(a)-event.getY(b));pinchX=(event.getX(a)+event.getX(b))/2;pinchY=(event.getY(a)+event.getY(b))/2;pinchActive=false
    }
    private fun movePinch(event:MotionEvent){
        val a=event.findPointerIndex(pinchFirst);val b=event.findPointerIndex(pinchSecond);if(a<0||b<0||pinchSpan<dp(20f))return
        val span=hypot(event.getX(a)-event.getX(b),event.getY(a)-event.getY(b));val focusX=(event.getX(a)+event.getX(b))/2;val focusY=(event.getY(a)+event.getY(b))/2
        if(!pinchActive&&abs(span-pinchSpan)<dp(8f)&&hypot(focusX-pinchX,focusY-pinchY)<dp(7f))return
        pinchActive=true
        val anchor=gridPositionAt(pinchX,pinchY);val oldX=zoomX;val oldY=zoomY;val ratio=span/pinchSpan;setAxisZoom(if(pinchAxis==2)zoomX else zoomX*ratio,if(pinchAxis==1)zoomY else zoomY*ratio)
        sx=anchor.x*col-(focusX-gutter);sy=anchor.y*minute-(focusY-header);clampScroll()
        pinchChanged=pinchChanged||abs(zoomX-oldX)>.001f||abs(zoomY-oldY)>.001f;pinchSpan=span;pinchX=focusX;pinchY=focusY;invalidate()
    }
    private fun finishPinch(){if(pinchChanged){onZoomChanged(zoomX);onAxisZoomChanged(zoomX,zoomY)};pinchSpan=0f;pinchActive=false;pinchChanged=false}
    private fun key(course:Course)=ModuleSelection(course.id,week.plusDays((course.day-1).toLong()))
    private fun toggleBatch(course:Course){val key=key(course);if(!batchSelected.add(key))batchSelected.remove(key);onBatchSelection(batchSelected.toSet());performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);invalidate()}
    private fun updateBatchFromBox(){val box=selectionBox?:return;var changed=false;hits.forEach {(course,bounds)->if(RectF.intersects(box,bounds))changed=batchSelected.add(key(course))||changed};if(changed)onBatchSelection(batchSelected.toSet())}
    private val english get()=appData.settings.language=="en"
    private fun dayLabel(day:Int)=if(english) arrayOf("M","T","W","T","F","S","S")[day-1] else "一二三四五六日"[day-1].toString()
    private fun updateAccessibilityDescription(){contentDescription=if(english) "Weekly schedule with your preferred time range. Tap an event for details. In edit mode, hold to drag. Pinch vertically for row height, horizontally for column width, or diagonally for both. A list view is also available." else "一周课表，按设置显示时间范围。点击课程显示详情；进入编辑后可长按拖动。竖向双指调整行距，横向调整列距，斜向同时缩放。也可使用课程列表。"}
    init {isClickable=true;isFocusable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES;updateAccessibilityDescription()}
    private fun clampScroll(){sx=sx.coerceIn(0f,max(0f,col*dayCount-(width-gutter)));sy=sy.coerceIn(minute*timeRange.first,max(minute*timeRange.first,minute*timeRange.last-(height-header-bottomObstruction)))}
    fun resetViewport(){scroller.forceFinished(true);clearSelection();sx=0f;sy=timeRange.first*minute;clampScroll();invalidate()}
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){if(oldw>0&&oldh>0)cancelDrag()else sy=timeRange.first*minute;clampScroll()}
    override fun computeScroll(){if(scroller.computeScrollOffset()){sx=scroller.currX.toFloat();sy=scroller.currY.toFloat();clampScroll();postInvalidateOnAnimation()}}
    private fun text(canvas:Canvas,value:String,x:Float,y:Float,size:Float,color:Int=palette.ink,bold:Boolean=false){paint.color=color;paint.style=Paint.Style.FILL;paint.textSize=dp(size);paint.typeface=if(bold)medium else regular;paint.pathEffect=null;paint.alpha=255;canvas.drawText(value,x,y,paint)}
    private fun round(canvas:Canvas,r:RectF,color:Int,radius:Float=12f,alpha:Int=255){paint.color=color;paint.alpha=alpha;paint.style=Paint.Style.FILL;paint.pathEffect=null;canvas.drawRoundRect(r,dp(radius),dp(radius),paint);paint.alpha=255}
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas);canvas.save();canvas.clipRect(0,0,width,height);canvas.drawColor(if(appData.settings.glassBackground)androidx.core.graphics.ColorUtils.setAlphaComponent(palette.background,38)else palette.background);hits.clear();deadlineHits.clear()
        drawGridLayer(canvas)
        updateGlassGrid()
        canvas.save();canvas.clipRect(0f,header,width.toFloat(),height.toFloat())
        canvas.save();canvas.clipRect(gutter,header,width.toFloat(),height.toFloat())
        for(day in 1..dayCount){val date=week.plusDays((day-1).toLong());val courses=appData.courses.filter {it.occurs(date,appData.settings.termStart)}.sortedWith(compareBy<Course>{it.start}.thenBy {it.id});var i=0
            while(i<courses.size){val group=mutableListOf(courses[i]);var end=courses[i].end;i++;while(i<courses.size&&courses[i].start<end){group.add(courses[i]);end=max(end,courses[i].end);i++}
                val lanes=mutableListOf<Int>();val assignments=mutableMapOf<String,Int>()
                for(c in group){var lane=lanes.indexOfFirst {it<=c.start};if(lane<0){lane=lanes.size;lanes.add(c.end)}else lanes[lane]=c.end;assignments[c.id]=lane}
                for(c in group){val lane=assignments.getValue(c.id);val w=col/lanes.size
                    val r=RectF(gutter+(day-1)*col-sx+lane*w+dp(3f),header+c.start*minute-sy,gutter+(day-1)*col-sx+(lane+1)*w-dp(3f),header+c.end*minute-sy-dp(2f));r.bottom=max(r.bottom,r.top+dp(24f))
                    if(r.bottom<header||r.top>height||r.right<gutter||r.left>width)continue
                    val held=drag?.let {it.course.id==c.id&&!it.fresh}==true||landing?.let {it.course.id==c.id&&!it.fresh}==true
                    val batch=key(c) in batchSelected;val moduleAlpha=if(held)65 else if(drag!=null)190 else 255
                    val hiddenForExpansion=hideSelectedCourse&&selected?.id==c.id&&!held
                    if(batch)shadow(canvas,r,10f,.6f);if(!hiddenForExpansion&&selected?.id!=c.id&&settlingCourse?.id!=c.id)drawCourse(canvas,c,r,moduleAlpha,occurrenceDate=date);if(isNext(c,date)&&!batch&&!hiddenForExpansion)drawNextHighlight(canvas,c,r);if(batch)drawBatchSelected(canvas,r);hits.add(c to r)
                }
            }
        }
        if(!hideSelectedCourse)(selected?:settlingCourse)?.let {chosen->hits.find {it.first.id==chosen.id}?.let {(c,r)->val lifted=RectF(r).apply {offset(0f,if(editingEnabled)-dp(2f)*selectionAmount else 0f)};shadow(canvas,lifted,10f,selectionAmount);drawCourse(canvas,c,lifted,occurrenceDate=key(c).date);if(isNext(c,key(c).date))drawNextHighlight(canvas,c,lifted)}}
        selectionBox?.let {box->
            paint.style=Paint.Style.FILL;paint.color=Color.argb(45,Color.red(palette.brand),Color.green(palette.brand),Color.blue(palette.brand));canvas.drawRoundRect(box,dp(8f),dp(8f),paint)
            paint.style=Paint.Style.STROKE;paint.color=palette.brand;paint.strokeWidth=dp(1.8f);paint.pathEffect=DashPathEffect(floatArrayOf(dp(6f),dp(4f)),0f);canvas.drawRoundRect(box,dp(8f),dp(8f),paint);paint.pathEffect=null;paint.style=Paint.Style.FILL
        }
        val now=java.time.LocalDateTime.now();if(monday(now.toLocalDate())==week){val y=header+(now.hour*60+now.minute)*minute-sy;paint.color=palette.brand;paint.strokeWidth=dp(1.5f);canvas.drawLine(gutter,y,width.toFloat(),y,paint);canvas.drawCircle(gutter+dp(2f),y,dp(3f),paint)}
        canvas.restore();canvas.restore();round(canvas,RectF(0f,0f,width.toFloat(),header),palette.background,0f)
        canvas.save();canvas.clipRect(gutter,0f,width.toFloat(),header)
        for(day in 1..dayCount){val x=gutter+(day-1)*col-sx;val date=week.plusDays((day-1).toLong());val due=appData.deadlines.filter {it.dueDate==date};if(date==LocalDate.now())round(canvas,RectF(x+dp(3f),dp(2f),x+col-dp(3f),header-dp(4f)),palette.container)
            text(canvas,dayLabel(day),x+col/2-dp(if(english)4f else 6f),dp(18f),11f,palette.muted)
            if(due.isEmpty())text(canvas,date.dayOfMonth.toString(),x+col/2-dp(6f),dp(37f),11f,palette.muted)else drawDeadlineDateMarker(canvas,x,date,due)
        };canvas.restore()
        if(modeAmount>0f&&!batchDeleteEnabled&&drag==null&&landing==null){canvas.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(255*modeAmount).toInt());canvas.save();canvas.scale(.7f+.3f*modeAmount,.7f+.3f*modeAmount,fab.x,fab.y);drawFab(canvas);canvas.restore();canvas.restore()}
        updateMovingGlass()
        drawingMovingGlass=true
        drag?.let {drawDrag(canvas,it)}
        landing?.let {l->val r=rectMix(l.from,l.to,l.progress);val amount=1-l.progress
            if(l.deleting){drawCourse(canvas,l.course,r,alpha=(255*(1-l.progress)).toInt());drawTrash(canvas,true)}
            else if(l.cancel&&l.fresh){shadow(canvas,r,mix(12f,28f,l.progress),amount);round(canvas,r,colorMix(palette.fills[l.course.color],palette.brand,l.progress),mix(12f,28f,l.progress));drawPlus(canvas,r.centerX(),r.centerY(),l.progress)}
            else {shadow(canvas,r,12f,amount);drawCourse(canvas,l.course,r)}
        };canvas.restore()
        drawingMovingGlass=false
        publishGlassFrame()
    }
    var onGlassFrame:(Bitmap)->Unit={}
    private var frameKey:String?=null
    private var capturingGlass=false
    // Intentionally render only this view content into an offscreen backdrop; draw() would include unwanted view layers.
    @android.annotation.SuppressLint("WrongCall")
    private fun publishGlassFrame(){
        if(glassGridRaw==null||selected==null||editingEnabled||!appData.settings.glassBackground||capturingGlass||width==0||height==0)return
        val key="$glassGridKey/${selected?.id}/${appData.courses.hashCode()}"
        if(frameKey==key||frameInFlight)return
        frameKey=key;capturingGlass=true
        try {
            val scale=minOf(1f,CourseGlassMaterial.captureLimit.toFloat()/maxOf(width,height))
            val source=Bitmap.createBitmap((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val c=Canvas(source);c.scale(scale,scale)
            glassGridRaw?.let {c.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),height.toFloat()),glassPaint)}
            hits.filter {it.first.id!=selected?.id}.forEach {(course,bounds)->drawCourse(c,course,bounds)}
            frameInFlight=true
            enqueueBlur(source){blurred->
                source.recycle();frameInFlight=false
                if(blurred!=null&&selected!=null&&frameKey==key&&appData.settings.glassBackground)onGlassFrame(blurred)
                else {blurred?.recycle();if(frameKey==key)frameKey=null}
                invalidate()
            }
        } finally {capturingGlass=false}
    }
    private var glassGrid:Bitmap?=null
    private var glassGridRaw:Bitmap?=null
    private var gridInFlight=false
    private var movingInFlight=false
    private var frameInFlight=false
    private var renderGeneration=0
    // At most one job per kind. Viewport changes coalesce while busy; no unbounded queue.
    // Only a small Canvas capture runs on the UI thread. All separable blur passes run here.
    private fun enqueueBlur(source:Bitmap,completed:(Bitmap?)->Unit){
        val generation=renderGeneration
        val radius=(CourseGlassMaterial.blurDp*density*source.width/width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        BackdropExecutor.execute {
            val result=runCatching {FrostedBitmap.create(source,radius,CourseGlassMaterial.captureLimit)}.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if(generation==renderGeneration&&isAttachedToWindow)completed(result)
                else {result?.recycle();if(!source.isRecycled)source.recycle()}
            }
        }
    }
    private var glassGridKey:String?=null
    private var movingGlass:Bitmap?=null
    private var movingGlassKey:String?=null
    private var drawingMovingGlass=false
    private fun updateMovingGlass(){
        val held=drag?.course?.id?:landing?.course?.id?:return
        val grid=glassGridRaw?:return
        if(!appData.settings.glassBackground)return
        val key="$glassGridKey/$held/${appData.courses.hashCode()}/${appData.notes.hashCode()}"
        if(key==movingGlassKey||movingInFlight)return
        val source=Bitmap.createBitmap(grid.width,grid.height,Bitmap.Config.ARGB_8888)
        val c=Canvas(source);c.scale(grid.width.toFloat()/width,grid.height.toFloat()/height)
        c.drawBitmap(grid,null,RectF(0f,0f,width.toFloat(),height.toFloat()),glassPaint)
        c.clipRect(gutter,header,width.toFloat(),height.toFloat())
        hits.filter {it.first.id!=held}.forEach {(course,bounds)->drawCourse(c,course,bounds)}
        // Immutable frames can still be referenced by the GPU; let GC retire them safely.
        movingInFlight=true
        enqueueBlur(source){next->
            source.recycle();movingInFlight=false
            if(next!=null){movingGlass=next;movingGlassKey=key}
            invalidate()
        }
    }
    private val glassPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun drawGridLayer(canvas:Canvas){
        canvas.save();canvas.clipRect(0f,header,width.toFloat(),height.toFloat());canvas.drawColor(if(appData.settings.glassBackground)androidx.core.graphics.ColorUtils.setAlphaComponent(palette.surface,90)else palette.surface)
        for(t in (timeRange.first/30*30)..timeRange.last step 30){val y=header+t*minute-sy;if(y<header-dp(20f)||y>height+dp(20f))continue
            paint.color=palette.line;paint.strokeWidth=dp(if(t%60==0).8f else .4f);paint.alpha=if(t%60==0)220 else 95;canvas.drawLine(gutter,y,width.toFloat(),y,paint);paint.alpha=255
            if(t%60==0)text(canvas,timeText(t),dp(6f),y+dp(12f),9f,palette.muted)
        }
        canvas.save();canvas.clipRect(gutter,header,width.toFloat(),height.toFloat())
        for(d in 0..dayCount){paint.color=palette.line;paint.strokeWidth=dp(.5f);canvas.drawLine(gutter+d*col-sx,header,gutter+d*col-sx,height.toFloat(),paint)}
        for(day in 1..dayCount){
            val date=week.plusDays((day-1).toLong());val due=appData.deadlines.filter {it.dueDate==date}
            if(due.isNotEmpty())drawDeadlineTint(canvas,day,due)
        }
        canvas.restore();canvas.restore()
    }
    /** One bounded capture of the actual grid per viewport, shared by every course, never its text. */
    private fun updateGlassGrid(){
        if(!appData.settings.glassBackground||width==0||height==0)return
        val location=IntArray(2);getLocationInWindow(location)
        val key="$width/$height/$sx/$sy/$zoomX/$zoomY/$week/${appData.settings.hashCode()}/${palette.hashCode()}/${appData.deadlines.hashCode()}/${location[0]}/${location[1]}"
        if(glassGridKey==key||gridInFlight)return
        val scale=minOf(1f,CourseGlassMaterial.captureLimit.toFloat()/maxOf(width,height));val w=(width*scale).toInt().coerceAtLeast(1);val h=(height*scale).toInt().coerceAtLeast(1)
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val c=Canvas(source);c.scale(scale,scale)
        val custom=appData.settings.customTheme&&customBackdrop!=null
        val photo=if(custom)customBackdrop!! else FrostedBitmap.skin(context,appSkin(appData.settings.skin).photo)
        val rw=rootView.width.coerceAtLeast(width);val rh=rootView.height.coerceAtLeast(height)
        val ps=maxOf(rw.toFloat()/photo.width,rh.toFloat()/photo.height)
        val left=(rw-photo.width*ps)/2-location[0];val top=(rh-photo.height*ps)/2-location[1]
        c.drawBitmap(photo,null,RectF(left,top,left+photo.width*ps,top+photo.height*ps),glassPaint)
        if(custom){val tone=appData.settings.appBackgroundTone;c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(if(tone<0)Color.BLACK else Color.WHITE,(abs(tone)*.85f*255).toInt()))}
        else {c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.background,(landscapeVeil(palette.dark)*255).toInt()));c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(appSkin(appData.settings.skin).seed,10))}
        c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(palette.background,70))
        drawGridLayer(c)
        gridInFlight=true
        enqueueBlur(source){next->
            gridInFlight=false
            if(next!=null&&appData.settings.glassBackground){glassGridRaw=source;glassGrid=next;glassGridKey=key}
            else {next?.recycle();source.recycle()}
            invalidate()
        }
    }
    private fun drawGlassCourse(canvas:Canvas,r:RectF,fill:Int,radius:Float){
        val bitmap=if(drawingMovingGlass)movingGlass?:glassGrid else glassGrid
        if(!appData.settings.glassBackground||bitmap==null){round(canvas,r,fill,radius);return}
        canvas.save();val path=Path().apply{addRoundRect(r,dp(radius),dp(radius),Path.Direction.CW)};canvas.clipPath(path)
        val liquid=appData.settings.liquidGlass&&liquidSupported&&Build.VERSION.SDK_INT>=33&&canvas.isHardwareAccelerated
        if(liquid){if(courseLens==null)courseLens=CourseLiquidLens();courseLens!!.draw(canvas,bitmap,r,width,height,dp(radius),resources.displayMetrics.density)}
        else canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),glassPaint)
        round(canvas,r,fill,radius,((if(liquid)CourseGlassMaterial.tintAlpha else courseGlassAlpha)*255).toInt())
        // Same short, low-contrast glint as Compose panels, including moving cards.
        glassPaint.style=Paint.Style.STROKE;glassPaint.strokeWidth=dp(.65f)
        glassPaint.shader=android.graphics.RadialGradient(r.left+r.width()*.18f,r.top,minOf(dp(26f),r.width()*.22f).coerceAtLeast(1f),
            intArrayOf(Color.argb(46,255,255,255),Color.argb(18,255,255,255),Color.TRANSPARENT),floatArrayOf(0f,.35f,1f),android.graphics.Shader.TileMode.CLAMP)
        canvas.drawPath(path,glassPaint)
        glassPaint.shader=null;glassPaint.style=Paint.Style.FILL;glassPaint.color=Color.WHITE;canvas.restore()
    }
    private fun drawCourse(canvas:Canvas,c:Course,r:RectF,alpha:Int=255,radius:Float=CourseGlassMaterial.cornerDp,contentAlpha:Float=1f,fill:Int=palette.fills[c.color],occurrenceDate:LocalDate?=null){
        if(r.width()<=0||r.height()<=0)return
        if(alpha<255)canvas.saveLayerAlpha(r,alpha)else canvas.save();drawGlassCourse(canvas,r,fill,radius);if(contentAlpha<1f)canvas.saveLayerAlpha(r,(contentAlpha*255).toInt().coerceIn(0,255))else canvas.save();canvas.clipRect(r.left+dp(5f),r.top+dp(4f),r.right-dp(4f),r.bottom-dp(3f))
        drawCourseLabels(canvas,c,r,occurrenceDate)
        canvas.restore();canvas.restore()
    }
    fun courseLabelSnapshot(c:Course,r:RectF,date:LocalDate):android.graphics.Bitmap {
        val bitmap=android.graphics.Bitmap.createBitmap(kotlin.math.ceil(r.width()).toInt().coerceAtLeast(1),kotlin.math.ceil(r.height()).toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.clipRect(dp(5f),dp(4f),r.width()-dp(4f),r.height()-dp(3f))
        drawCourseLabels(canvas,c,RectF(0f,0f,r.width(),r.height()),date)
        return bitmap
    }
    private fun drawCourseLabels(canvas:Canvas,c:Course,r:RectF,occurrenceDate:LocalDate?){
        val available=max(dp(8f),r.width()-dp(10f))
        val font=(12f*sqrt(zoomX*zoomY)).coerceIn(10f,15f).coerceAtMost(max(10f,(r.height()/density-8f)/2.4f))
        val lineHeight=dp(font+3f)
        val rows=((r.height()-dp(8f))/lineHeight).toInt().coerceAtLeast(1)
        val place=c.room.ifBlank {c.address}
        val titleRows=if(rows>=3)2 else 1
        var title=c.name;var baseline=r.top+dp(font+4f)
        fun fitted(value:String):String {
            paint.textSize=dp(font);paint.typeface=medium
            if(paint.measureText(value)<=available)return value
            val count=paint.breakText(value,true,max(0f,available-paint.measureText("…")),null)
            return value.take(count)+"…"
        }
        repeat(titleRows) {index->
            if(title.isNotEmpty()){
                paint.textSize=dp(font);paint.typeface=medium
                val count=paint.breakText(title,true,available,null).coerceAtLeast(1)
                val line=if(index==titleRows-1)fitted(title)else title.take(count)
                text(canvas,line,r.left+dp(5f),baseline,font,palette.courseInk[c.color],true)
                title=title.drop(count);baseline+=lineHeight
            }
        }
        // Location has priority over repeated time labels when the card is compact.
        if(place.isNotBlank()&&baseline<=r.bottom-dp(3f)){
            text(canvas,fitted(place),r.left+dp(5f),baseline,font,palette.courseInk[c.color]);baseline+=lineHeight
        }
        if(baseline<=r.bottom-dp(3f))text(canvas,fitted(timeText(c.start)),r.left+dp(5f),baseline,font,palette.courseInk[c.color])
        if(r.height()>dp(90f)&&appData.notes.any {it.courseId==c.id&&(occurrenceDate==null||it.occurrenceDate==null||it.occurrenceDate==occurrenceDate)}){val x=r.right-dp(9f);val y=r.bottom-dp(9f);paint.color=palette.courseInk[c.color];paint.alpha=185;paint.strokeWidth=dp(1.2f);paint.style=Paint.Style.STROKE;canvas.drawRoundRect(RectF(x-dp(4f),y-dp(5f),x+dp(4f),y+dp(5f)),dp(2f),dp(2f),paint);canvas.drawLine(x-dp(2f),y-dp(1f),x+dp(2f),y-dp(1f),paint);canvas.drawLine(x-dp(2f),y+dp(2f),x+dp(2f),y+dp(2f),paint);paint.alpha=255;paint.style=Paint.Style.FILL}
    }
    private fun drawDeadlineTint(canvas:Canvas,day:Int,due:List<Deadline>){
        val left=gutter+(day-1)*col-sx+dp(2f);val right=gutter+day*col-sx-dp(2f)
        if(right<gutter||left>width)return
        val accent=palette.accents[due.first().color]
        paint.style=Paint.Style.FILL;paint.color=Color.argb(if(palette.dark)18 else 12,Color.red(accent),Color.green(accent),Color.blue(accent));canvas.drawRect(left,header,right,height.toFloat(),paint)
        paint.color=Color.argb(if(palette.dark)95 else 72,Color.red(accent),Color.green(accent),Color.blue(accent));canvas.drawRoundRect(RectF(left,header+dp(3f),left+dp(2.2f),height-dp(4f)),dp(2f),dp(2f),paint)
    }
    private fun drawDeadlineDateMarker(canvas:Canvas,x:Float,date:LocalDate,due:List<Deadline>){
        val accent=palette.accents[due.first().color];val marker=RectF(x+dp(3f),dp(23f),x+col-dp(3f),dp(45f))
        paint.style=Paint.Style.FILL;paint.color=Color.argb(if(palette.dark)66 else 42,Color.red(accent),Color.green(accent),Color.blue(accent));canvas.drawRoundRect(marker,dp(8f),dp(8f),paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(.8f);paint.color=Color.argb(if(palette.dark)170 else 135,Color.red(accent),Color.green(accent),Color.blue(accent));canvas.drawRoundRect(marker,dp(8f),dp(8f),paint);paint.style=Paint.Style.FILL
        val label=if(due.size>1){if(english)"${date.dayOfMonth} · ${due.size} due" else "${date.dayOfMonth} · ${due.size}项截止"} else "${date.dayOfMonth} · ${due.first().title.take(5)}"
        paint.textSize=dp(8f);paint.typeface=medium;val count=paint.breakText(label,true,marker.width()-dp(8f),null);val shown=label.take(count);val tx=marker.centerX()-paint.measureText(shown)/2
        text(canvas,shown,tx,marker.bottom-dp(6f),8f,accent,true)
        deadlineHits+=due to RectF(marker).apply {inset(-dp(4f),-dp(3f))}
    }
    private fun drawBatchSelected(canvas:Canvas,r:RectF){
        paint.color=Color.argb(34,Color.red(palette.danger),Color.green(palette.danger),Color.blue(palette.danger));canvas.drawRoundRect(r,dp(10f),dp(10f),paint);paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(2.5f);paint.color=palette.danger;canvas.drawRoundRect(r,dp(10f),dp(10f),paint);paint.style=Paint.Style.FILL
        val cx=r.right-dp(10f);val cy=r.top+dp(10f);paint.color=palette.danger;canvas.drawCircle(cx,cy,dp(8f),paint);paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.8f);paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(cx-dp(3.5f),cy,cx-dp(1f),cy+dp(3f),paint);canvas.drawLine(cx-dp(1f),cy+dp(3f),cx+dp(4f),cy-dp(3f),paint);paint.strokeCap=Paint.Cap.BUTT;paint.style=Paint.Style.FILL
    }
    private fun isNext(course:Course,date:LocalDate):Boolean=nextOccurrence?.let {it.course.id==course.id&&it.date==date}==true
    private fun drawNextHighlight(canvas:Canvas,course:Course,r:RectF){
        val accent=colorMix(palette.fills[course.color],palette.courseInk[course.color],if(palette.dark).34f else .38f)
        paint.style=Paint.Style.FILL;paint.color=accent
        canvas.drawRoundRect(RectF(r.left+dp(7f),r.top+dp(3f),r.left+minOf(dp(24f),r.width()*.35f),r.top+dp(5f)),dp(1f),dp(1f),paint)
    }
    private fun shadow(canvas:Canvas,r:RectF,radius:Float,amount:Float){canvas.save();canvas.clipOutPath(Path().apply {addRoundRect(r,dp(radius),dp(radius),Path.Direction.CW)});paint.style=Paint.Style.FILL;paint.pathEffect=null;paint.color=Color.BLACK;paint.setShadowLayer(dp(4f+14f*amount),0f,dp(2f+7f*amount),Color.argb((65*amount).toInt(),0,0,0));canvas.drawRoundRect(r,dp(radius),dp(radius),paint);paint.clearShadowLayer();canvas.restore()}
    private fun drawPlus(canvas:Canvas,x:Float,y:Float,alpha:Float=1f,foreground:Int=palette.onBrand){paint.style=Paint.Style.STROKE;paint.color=foreground;paint.alpha=(alpha*255).toInt().coerceIn(0,255);paint.strokeWidth=dp(2f);paint.strokeCap=Paint.Cap.ROUND;canvas.drawLine(x-dp(9f),y,x+dp(9f),y,paint);canvas.drawLine(x,y-dp(9f),x,y+dp(9f),paint);paint.alpha=255;paint.style=Paint.Style.FILL;paint.strokeCap=Paint.Cap.BUTT}
    private fun drawFab(canvas:Canvas){
        val f=fab;val r=RectF(f.x-dp(28f),f.y-dp(28f),f.x+dp(28f),f.y+dp(28f))
        round(canvas,r,palette.brand,28f)
        drawPlus(canvas,f.x,f.y,foreground=palette.onBrand)
    }
    private fun target(d:Drag):Pair<LocalDate,Int>? {if(d.course.duration>1440||overTrash(d)||d.x<gutter||d.x>width||d.y<header||d.y>height)return null;val day=floor((d.x-gutter+sx)/col).toInt().coerceIn(0,dayCount-1);return week.plusDays(day.toLong()) to snapStart(max(timeRange.first.toFloat(),(d.y-header+sy)/minute-d.grabMinutes),d.course.duration)}
    private fun slot(date:LocalDate,start:Int,duration:Int)=RectF(gutter+(date.dayOfWeek.value-1)*col-sx+dp(3f),header+start*minute-sy,gutter+date.dayOfWeek.value*col-sx-dp(3f),header+(start+duration)*minute-sy-dp(2f)).apply {bottom=max(bottom,top+dp(24f))}
    private fun floating(d:Drag):RectF {
        // Restore the original offset preview: the lifted card is beside the finger,
        // while the dashed slot still represents the exact snapped destination.
        val w=dp(84f);val h=dp(106f)
        val left=if(d.x+w+dp(24f)<width)d.x+dp(24f)else d.x-w-dp(24f)
        val top=(d.y-dp(118f)).coerceIn(header+dp(42f),max(header+dp(42f),height-h-dp(76f)))
        return rectMix(d.source,RectF(left.coerceIn(gutter,max(gutter,width-w)),top,left.coerceIn(gutter,max(gutter,width-w))+w,top+h),lift)
    }
    private fun overTrash(d:Drag)=hypot(d.x-fab.x,d.y-fab.y)<=dp(38f)
    private fun drawTrash(canvas:Canvas,hover:Boolean){
        val f=fab;val red=Color.rgb(201,57,65)
        if(hover){paint.shader=RadialGradient(f.x,f.y,dp(48f),intArrayOf(Color.argb(180,237,76,85),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);canvas.drawCircle(f.x,f.y,dp(48f),paint);paint.shader=null}
        paint.color=red;canvas.drawCircle(f.x,f.y,dp(if(hover)31f else 28f),paint)
        paint.color=Color.WHITE;paint.strokeWidth=dp(1.8f);paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND
        canvas.drawRoundRect(RectF(f.x-dp(7f),f.y-dp(5f),f.x+dp(7f),f.y+dp(10f)),dp(2f),dp(2f),paint)
        canvas.drawLine(f.x-dp(10f),f.y-dp(8f),f.x+dp(10f),f.y-dp(8f),paint)
        canvas.drawLine(f.x-dp(3f),f.y-dp(11f),f.x+dp(3f),f.y-dp(11f),paint)
        for(x in listOf(-2.5f,2.5f))canvas.drawLine(f.x+dp(x),f.y-dp(1f),f.x+dp(x),f.y+dp(6f),paint)
        paint.style=Paint.Style.FILL;paint.strokeCap=Paint.Cap.BUTT
    }

    private fun drawDrag(canvas:Canvas,d:Drag){
        val destination=target(d);var badge:String?=null;var badgeColor=palette.brand
        if(destination!=null){val(date,start)=destination;val collision=appData.courses.any {it.id!=d.course.id&&it.occurs(date,appData.settings.termStart)&&it.start<start+d.course.duration&&start<it.end};badgeColor=if(collision)palette.danger else palette.brand
            val r=slot(date,start,d.course.duration);canvas.save();canvas.clipRect(gutter,header,width.toFloat(),height.toFloat());round(canvas,r,palette.fills[if(collision)2 else d.course.color],10f,155)
            paint.style=Paint.Style.STROKE;paint.color=badgeColor;paint.strokeWidth=dp(2f);paint.pathEffect=DashPathEffect(floatArrayOf(dp(5f),dp(4f)),0f);canvas.drawRoundRect(r,dp(10f),dp(10f),paint);paint.pathEffect=null;paint.style=Paint.Style.FILL
            paint.color=badgeColor;paint.strokeWidth=dp(1f);paint.alpha=150;paint.pathEffect=DashPathEffect(floatArrayOf(dp(4f),dp(4f)),0f)
            canvas.drawLine(gutter,r.centerY(),width.toFloat(),r.centerY(),paint)
            canvas.drawLine(r.centerX(),header,r.centerX(),height.toFloat(),paint)
            paint.pathEffect=null;paint.alpha=255;canvas.restore()
            badge=(if(english) "${arrayOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[date.dayOfWeek.value-1]} " else "周${dayLabel(date.dayOfWeek.value)} ")+"${timeText(start)}–${timeText(start+d.course.duration)}"+if(collision){if(english)" · Conflict"else" · 时间冲突"}else ""
        }
        val r=floating(d);val radius=if(d.fresh)mix(28f,12f,lift)else 12f
        shadow(canvas,r,radius,lift);drawCourse(canvas,d.course.copy(start=destination?.second?:d.course.start),r,radius=radius,contentAlpha=if(d.fresh)((lift-.3f)/.7f).coerceIn(0f,1f)else 1f,fill=if(d.fresh)colorMix(palette.brand,palette.fills[d.course.color],lift)else palette.fills[d.course.color])
        if(d.fresh&&lift<.8f)drawPlus(canvas,r.centerX(),r.centerY(),(1-lift/.8f).coerceIn(0f,1f))
        badge?.let {val by=header+dp(8f);round(canvas,RectF(gutter+dp(8f),by,min(width-dp(8f),gutter+dp(258f)),by+dp(29f)),badgeColor,8f);text(canvas,it,gutter+dp(17f),by+dp(19f),11f,if(palette.dark)palette.background else Color.WHITE)}
        val hover=overTrash(d)&&d.trashArmed
        val hint=if(english){if(hover)"Release to delete · undoable" else if(destination==null)"Move onto the grid or drag to the bin" else "Release to place · 30 min snap"}else{if(hover)"松手删除 · 可撤销"else if(destination==null)"移回表格放置，或拖到垃圾桶"else "松手放置 · 30 分钟吸附"}
        round(canvas,RectF(dp(12f),height-dp(54f),width-dp(84f),height-dp(8f)),palette.container,16f)
        text(canvas,hint,dp(22f),height-dp(26f),10f,palette.ink)
        drawTrash(canvas,hover)
    }
    private fun release(d:Drag,destination:Pair<LocalDate,Int>?,deleting:Boolean=false){
        val from=floating(d);val to=if(deleting)RectF(fab.x-dp(3f),fab.y-dp(3f),fab.x+dp(3f),fab.y+dp(3f))else destination?.let {slot(it.first,it.second,d.course.duration)}?:d.source
        handler.removeCallbacks(startLongPress);removeCallbacks(autoScroll);liftAnimator?.cancel();drag=null
        val l=Landing(d.course.copy(start=destination?.second?:d.course.start),from,to,d.fresh,destination==null,deleting);landing=l
        landingAnimator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=motionDuration(if(deleting)220 else 260);interpolator=ease
            addUpdateListener {l.progress=it.animatedValue as Float;invalidate()}
            var cancelled=false
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){
                    landing=null;landingAnimator=null;onDragStateChange(false);invalidate()
                    if(!cancelled){
                        if(deleting){performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);if(!d.fresh)onDelete(d.course,d.originalDate)}
                        else if(destination!=null){performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);onDrop(d.course,d.originalDate,destination.first,destination.second,d.fresh)}
                    }
                }
            });start()
        }
    }

    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(landing!=null)return true
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN){handler.removeCallbacks(startLongPress);clearSelection();cancelDrag();multiTouch=true;panning=true;scroller.forceFinished(true);beginPinch(event);parent?.requestDisallowInterceptTouchEvent(true);return true}
        if(multiTouch){when(event.actionMasked){MotionEvent.ACTION_MOVE->movePinch(event);MotionEvent.ACTION_POINTER_UP->beginPinch(event,event.actionIndex);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{finishPinch();multiTouch=false;velocity?.recycle();velocity=null;parent?.requestDisallowInterceptTouchEvent(false)}};return true}
        if(batchDeleteEnabled){when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{scroller.forceFinished(true);selectStartX=event.x.coerceIn(gutter,width.toFloat());selectStartY=event.y.coerceIn(header,height.toFloat());selectCurrentX=selectStartX;selectCurrentY=selectStartY;selectingBox=false;selectionBox=null;downCourse=hits.lastOrNull {it.second.contains(event.x,event.y)}?.first;parent?.requestDisallowInterceptTouchEvent(true);return true}
            MotionEvent.ACTION_MOVE->{selectCurrentX=event.x.coerceIn(gutter,width.toFloat());selectCurrentY=event.y.coerceIn(header,height.toFloat());if(!selectingBox&&hypot(selectCurrentX-selectStartX,selectCurrentY-selectStartY)>dp(7f)){selectingBox=true;postOnAnimation(selectionAutoScroll)};if(selectingBox){selectionBox=RectF(min(selectStartX,selectCurrentX),min(selectStartY,selectCurrentY),max(selectStartX,selectCurrentX),max(selectStartY,selectCurrentY));updateBatchFromBox();invalidate()};return true}
            MotionEvent.ACTION_UP->{removeCallbacks(selectionAutoScroll);if(selectingBox){selectionBox=null;selectingBox=false;performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)}else downCourse?.let {toggleBatch(it)};downCourse=null;parent?.requestDisallowInterceptTouchEvent(false);invalidate();return true}
            MotionEvent.ACTION_CANCEL->{removeCallbacks(selectionAutoScroll);selectionBox=null;selectingBox=false;downCourse=null;parent?.requestDisallowInterceptTouchEvent(false);invalidate();return true}
        }}
        when(event.actionMasked){
            MotionEvent.ACTION_DOWN->{scroller.forceFinished(true);velocity?.recycle();velocity=VelocityTracker.obtain().also {it.addMovement(event)};downX=event.x;downY=event.y;lastX=event.x;lastY=event.y;panning=false;downFab=editingEnabled&&hypot(event.x-fab.x,event.y-fab.y)<=dp(32f);val hit=if(downFab)null else hits.lastOrNull {it.second.contains(event.x,event.y)&&event.x>=gutter&&event.y>=header};downCourse=hit?.first;downBounds=hit?.second?.let {RectF(it)};downDeadlines=if(hit==null&&!downFab)deadlineHits.lastOrNull {it.second.contains(event.x,event.y)}?.first else null;handler.postDelayed(startLongPress,400);return true}
            MotionEvent.ACTION_MOVE->{velocity?.addMovement(event);val d=drag;if(d!=null){val was=overTrash(d)&&d.trashArmed;d.x=event.x;d.y=event.y;if(!overTrash(d))d.trashArmed=true;if(!was&&overTrash(d)&&d.trashArmed)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);invalidate()}else{if(hypot(event.x-downX,event.y-downY)>dp(7f)){handler.removeCallbacks(startLongPress);clearSelection();panning=true};if(panning){parent?.requestDisallowInterceptTouchEvent(true);sx-=event.x-lastX;sy-=event.y-lastY;clampScroll();invalidate()}};lastX=event.x;lastY=event.y;return true}
            MotionEvent.ACTION_UP->{handler.removeCallbacks(startLongPress);val d=drag
                if(d!=null){d.x=event.x;d.y=event.y;release(d,target(d),overTrash(d)&&d.trashArmed)}
                else if(!panning){performClick();if(downFab){clearSelection();onCreate()}else if(downCourse!=null&&downBounds!=null)select(downCourse!!,downBounds!!)else if(downDeadlines!=null){clearSelection();onDeadline(downDeadlines!!)}else clearSelection()}
                else {velocity?.apply {addMovement(event);computeCurrentVelocity(1000);scroller.fling(sx.toInt(),sy.toInt(),-xVelocity.toInt(),-yVelocity.toInt(),0,max(0f,col*dayCount-(width-gutter)).toInt(),(minute*timeRange.first).toInt(),max(minute*timeRange.first,minute*timeRange.last-(height-header-bottomObstruction)).toInt());postInvalidateOnAnimation()}}
                downDeadlines=null;velocity?.recycle();velocity=null;parent?.requestDisallowInterceptTouchEvent(false);invalidate();return true}
            MotionEvent.ACTION_CANCEL->{downDeadlines=null;drag?.let {release(it,null)}?:cancelDrag();velocity?.recycle();velocity=null;parent?.requestDisallowInterceptTouchEvent(false);return true}
        };return true
    }
    override fun performClick():Boolean {super.performClick();return true}
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);if(editingEnabled&&!batchDeleteEnabled)info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,if(english)"New event"else"新建课程"))}
    override fun performAccessibilityAction(action:Int,args:Bundle?):Boolean {if(action==AccessibilityNodeInfo.ACTION_CLICK&&editingEnabled&&!batchDeleteEnabled){onCreate();return true};return super.performAccessibilityAction(action,args)}
    fun cancelDrag(){handler.removeCallbacks(startLongPress);removeCallbacks(autoScroll);removeCallbacks(selectionAutoScroll);selectionBox=null;selectingBox=false;liftAnimator?.cancel();liftAnimator=null;landingAnimator?.cancel();landingAnimator=null;drag=null;landing=null;onDragStateChange(false);invalidate()}
    override fun onDetachedFromWindow(){renderGeneration++;gridInFlight=false;movingInFlight=false;frameInFlight=false;frameKey=null;glassGrid?.recycle();glassGrid=null;glassGridRaw=null;glassGridKey=null;clearSelection();modeAnimator?.cancel();cancelDrag();velocity?.recycle();velocity=null;scroller.forceFinished(true);super.onDetachedFromWindow()}
}


/** Shared serial worker: expensive bitmap blur must never execute from onDraw. */
private val BackdropExecutor=java.util.concurrent.Executors.newSingleThreadExecutor {r->
    Thread(r,"kejian-timetable-blur").apply {isDaemon=true;priority=Thread.NORM_PRIORITY-1}
}
