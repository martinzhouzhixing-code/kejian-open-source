package app.kejian.mobile

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Image
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import kotlinx.coroutines.delay

/** Real rendering and real gesture handling against isolated, disposable demonstration data. */
@Composable internal fun TutorialScene(page:Int){
    if(page==7){AiTutorialScene();return}
    if(page in 8..9){RecordingTutorialScene(page-8);return}
    val palette=LocalAppPalette.current
    val density=LocalDensity.current.density
    val term=remember {monday(LocalDate.now())}
    val english=AppLanguage.english
    val sample=remember(english) {Course(id="tutorial",name=if(english)"Calculus" else "高等数学",start=480,end=570,day=1,weeks=setOf(1),address=if(english)"Learning Center" else "博学楼",room="A201",reminder=false)}
    val batchSamples=remember(english) {listOf(sample,sample.copy(id="tutorial-2",name=if(english)"Academic English" else "大学英语",day=2,start=510,end=600,color=1,room="B302"),sample.copy(id="tutorial-3",name=if(english)"Design Fundamentals" else "设计基础",day=3,start=540,end=630,color=2,room="C105"))}
    var demo by remember {mutableStateOf(AppData(if(page==1)emptyList()else if(page==4)batchSamples else listOf(sample),Settings(termStart=term,reminders=false)))}
    var editing by remember {mutableStateOf(page!=0)}
    var dragging by remember {mutableStateOf(false)}
    var selection by remember {mutableStateOf(false)}
    var batchDelete by remember {mutableStateOf(false)}
    var batchSelection by remember {mutableStateOf<Set<ModuleSelection>>(emptySet())}
    var view by remember {mutableStateOf<TimetableView?>(null)}
    var fingers by remember {mutableStateOf(emptyList<Offset>())}
    var saveRequest by remember {mutableIntStateOf(0)}
    var deadlineSheet by remember {mutableStateOf(false)}
    val bottom by animateDpAsState(if(editing)80.dp else 12.dp,spring(.86f,340f),label="tutorialSavePosition")
    Box(Modifier.fillMaxWidth().height(252.dp).clip(RoundedCornerShape(24.dp)).background(SurfaceColor)){
        AndroidView(factory={context->TimetableView(context).also {view=it}},update={v->
            v.palette=palette;v.appData=demo;v.week=term;v.editingEnabled=editing;v.batchDeleteEnabled=batchDelete
            v.onDragStateChange={dragging=it}
            v.onSelection={c,_,_->selection=c!=null}
            v.onBatchSelection={batchSelection=it}
            v.onDrop={c,_,date,start,fresh->val placed=c.copy(day=date.dayOfWeek.value,start=start,end=start+c.duration,weeks=setOf(1));demo=demo.copy(courses=if(fresh)demo.courses+placed else demo.courses.map {if(it.id==c.id)placed else it})}
            v.onDelete={c,_->demo=demo.copy(courses=demo.courses.filterNot {it.id==c.id})}
        },modifier=Modifier.fillMaxSize())
        AnimatedVisibility(selection&&!dragging,Modifier.align(Alignment.TopStart).padding(start=118.dp,top=115.dp),enter=fadeIn(tween(180))+scaleIn(initialScale=.92f),exit=fadeOut()){
            GlassSurface(shape=RoundedCornerShape(18.dp),shadowElevation=12.dp,color=SurfaceColor){
                Column(Modifier.width(144.dp).padding(6.dp)){
                    TextButton(onClick={},modifier=Modifier.fillMaxWidth()){Text("复制")}
                    TextButton(onClick={},modifier=Modifier.fillMaxWidth()){Text("自定义")}
                }
            }
        }
        AnimatedVisibility(!dragging&&!batchDelete,Modifier.align(Alignment.BottomEnd).padding(end=12.dp,bottom=bottom),enter=fadeIn()+scaleIn(),exit=fadeOut()+scaleOut()){
            EditSaveButton(editing,onEdit={editing=true},onSave={editing=false;true},saveRequest=saveRequest)
        }
        val scopeBottom by animateDpAsState(if(batchDelete)76.dp else 144.dp,spring(.82f,320f),label="tutorialScope")
        AnimatedVisibility(editing&&!dragging,Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=scopeBottom),enter=fadeIn()+slideInVertically {it/2},exit=fadeOut()){
            GlassFilledTonalButton(onClick={},modifier=Modifier.width(110.dp).height(48.dp),shape=RoundedCornerShape(24.dp),contentPadding=PaddingValues(horizontal=10.dp)){Text("仅本次 ⇄",fontSize=13.sp,fontWeight=FontWeight.Medium)}
        }
        val deleteBottom by animateDpAsState(if(batchDelete)12.dp else 208.dp,spring(.82f,300f),label="tutorialDeleteY")
        AnimatedVisibility(editing&&!dragging,Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=deleteBottom),enter=fadeIn()+scaleIn(),exit=fadeOut()+scaleOut()){
            GlassFilledTonalButton(onClick={},modifier=Modifier.width(110.dp).height(48.dp),shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(batchDelete)MaterialTheme.colorScheme.errorContainer else Mint),contentPadding=PaddingValues(horizontal=10.dp)){Text(if(!batchDelete)"批量删除" else if(batchSelection.isEmpty())"退出框选" else "删除 ${batchSelection.size} 项",fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1)}
        }
        AnimatedVisibility(page==6&&deadlineSheet,modifier=Modifier.align(Alignment.Center),enter=fadeIn(tween(220))+scaleIn(animationSpec=spring(.82f,360f),initialScale=.9f),exit=fadeOut(tween(160))+scaleOut(targetScale=.94f)){
            GlassSurface(Modifier.fillMaxWidth(.88f),shape=RoundedCornerShape(22.dp),color=SurfaceColor,shadowElevation=18.dp){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("新建截止日",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(if(english)"Calculus assignment" else "高等数学作业",color=Ink)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("截止日期",color=Muted);Text("${term.plusDays(2).monthValue} 月 ${term.plusDays(2).dayOfMonth} 日",color=Brand)}
                Text(if(english)"Standalone, or linked to an event" else "可独立设置，也可以关联一门课程",style=MaterialTheme.typography.bodySmall,color=Muted)
                GlassSurface(color=Brand,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Text("创建截止日",Modifier.padding(vertical=10.dp),color=Color.White,textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontWeight=FontWeight.Medium)}
            }}
        }
        // Ignore physical touches inside the scripted demo, not the tutorial navigation outside it.
        Canvas(Modifier.fillMaxSize().pointerInput(Unit){awaitPointerEventScope {while(true)awaitPointerEvent().changes.forEach {it.consume()}}}){
            fingers.forEach {p->drawCircle(Color(palette.brand).copy(alpha=.16f),18.dp.toPx(),p);drawCircle(Color(palette.ink).copy(alpha=.5f),6.dp.toPx(),p);drawCircle(Color(palette.surface),3.dp.toPx(),p)}
        }
    }
    LaunchedEffect(view){
        val v=view?:return@LaunchedEffect
        while(v.width==0||v.height==0)withFrameNanos {}
        val d=density;val fab=Offset(v.width-40*d,v.height-40*d)
        val course=Offset(44*d+maxOf(60*d,(v.width-44*d)/5f)/2,140*d)
        var down=SystemClock.uptimeMillis()
        fun event(action:Int,p:Offset){val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,p.x,p.y,0);v.dispatchTouchEvent(e);e.recycle()}
        fun two(action:Int,a:Offset,b:Offset){
            val props=Array(2){i->MotionEvent.PointerProperties().apply {id=i;toolType=MotionEvent.TOOL_TYPE_FINGER}}
            val coords=arrayOf(a,b).map {p->MotionEvent.PointerCoords().apply {x=p.x;y=p.y;pressure=1f;size=1f}}.toTypedArray()
            val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,2,props,coords,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);v.dispatchTouchEvent(e);e.recycle()
        }
        suspend fun move(from:Offset,to:Offset,ms:Int=900,dispatch:Boolean=true){animate(0f,1f,animationSpec=tween(ms,easing=FastOutSlowInEasing)){value,_->val p=from+(to-from)*value;fingers=listOf(p);if(dispatch)event(MotionEvent.ACTION_MOVE,p)}}
        suspend fun pinch(delta:Offset){
            val center=Offset(v.width*.5f,185*d)
            down=SystemClock.uptimeMillis();event(0,center-delta);two(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),center-delta,center+delta)
            animate(1f,1.4f,animationSpec=tween(750,easing=FastOutSlowInEasing)){scale,_->val a=center-delta*scale;val b=center+delta*scale;fingers=listOf(a,b);two(MotionEvent.ACTION_MOVE,a,b)}
            two(MotionEvent.ACTION_POINTER_UP or (1 shl 8),center-delta*1.4f,center+delta*1.4f);event(1,center-delta*1.4f);fingers=emptyList();delay(300)
        }
        try {
            delay(650)
            when(page){
                0->{val target=Offset(v.width-50*d,v.height-40*d);move(target-Offset(75*d,40*d),target,700,false);delay(180);editing=true;delay(450);fingers=emptyList()}
                1->{fingers=listOf(fab);down=SystemClock.uptimeMillis();event(0,fab);delay(780);val target=Offset(44*d+maxOf(60*d,(v.width-44*d)/5f)*1.5f,140*d);move(fab,target,1150);delay(350);event(1,target);fingers=emptyList()}
                2->{move(course+Offset(95*d,60*d),course,750,false);down=SystemClock.uptimeMillis();event(0,course);delay(90);event(1,course);delay(750);fingers=emptyList()}
                3->{fingers=listOf(course);down=SystemClock.uptimeMillis();event(0,course);delay(780);move(course,fab,1150);delay(450);event(1,fab);fingers=emptyList()}
                4->{val button=Offset(v.width-62*d,v.height-228*d);move(button-Offset(70*d,25*d),button,650,false);delay(150);batchDelete=true;delay(650);val start=Offset(48*d,78*d);val end=Offset(v.width*.63f,205*d);fingers=listOf(start);down=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN,start);move(start,end,1150);delay(350);event(MotionEvent.ACTION_UP,end);fingers=emptyList();delay(650);demo=demo.copy(courses=demo.courses.filterNot {course->batchSelection.any {it.courseId==course.id}});batchDelete=false;batchSelection=emptySet();delay(450)}
                5->{pinch(Offset(0f,35*d));pinch(Offset(40*d,0f));pinch(Offset(30*d,30*d));delay(250);val target=Offset(v.width-50*d,v.height-108*d);move(target-Offset(65*d,30*d),target,500,false);delay(120);saveRequest++;delay(120);fingers=emptyList()}
                6->{move(fab-Offset(70*d,35*d),fab,650,false);delay(180);deadlineSheet=true;delay(1050);val confirm=Offset(v.width*.5f,v.height*.68f);move(fab,confirm,520,false);delay(180);deadlineSheet=false;demo=demo.copy(deadlines=listOf(Deadline(id="tutorial-deadline",title=if(english)"Calculus assignment" else "高数作业",dueDate=term.plusDays(2),courseId=sample.id,color=3,reminder=false)));fingers=emptyList();delay(1400)}
            }
        }finally{event(MotionEvent.ACTION_CANCEL,Offset.Zero);fingers=emptyList()}
    }
}

@Composable private fun RecordingTutorialScene(stage:Int){
    val english=AppLanguage.english
    var phase by remember(stage){mutableIntStateOf(0)}
    LaunchedEffect(stage){while(true){phase=0;delay(550);phase=1;delay(950);phase=2;delay(1100);phase=3;delay(1300);phase=4;delay(1500)}}
    Box(Modifier.fillMaxWidth().height(252.dp).clip(RoundedCornerShape(24.dp)).background(SurfaceColor).padding(14.dp)){
        if(stage==0)Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(english)"Record & summarize" else "录音与课堂总结",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(if(english)"Local first" else "本机优先",color=Brand,style=MaterialTheme.typography.labelMedium)}
            Row(Modifier.height(38.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){repeat(13){i->val active=phase>=1;val h=if(active)(8+((i*7+phase*11)%24)).dp else 6.dp;Box(Modifier.width(3.dp).height(h).background(if(active)Brand else Muted.copy(alpha=.25f),RoundedCornerShape(3.dp)))}}
            Text(if(phase==0)"00:00" else "00:${if(phase==1)"10" else "42"}",style=MaterialTheme.typography.headlineSmall)
            GlassSurface(shape=RoundedCornerShape(30.dp),color=if(phase==1)Color(0xFFB13A3A) else Mint,modifier=Modifier.size(52.dp)){Box(contentAlignment=Alignment.Center){Icon(if(phase==1)Icons.Filled.Stop else Icons.Filled.Mic,null,tint=Ink)}}
            AnimatedVisibility(phase>=2){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(english)"Estimated: about 2 min · safe to leave" else "预计约 2 分钟 · 可以离开页面",color=Muted,style=MaterialTheme.typography.bodySmall);LinearProgressIndicator(progress={if(phase>=3).72f else .18f},modifier=Modifier.fillMaxWidth(.72f));Text(if(phase>=4){if(english)"System notification · Summary ready" else "系统通知 · 课堂总结已完成"}else{if(english)"Processing in background…" else "正在后台转写总结…"},color=Brand,style=MaterialTheme.typography.labelMedium)}}
        } else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(english)"Review, then attach" else "核对后关联课程",style=MaterialTheme.typography.titleMedium)
            AnimatedVisibility(phase>=1,enter=fadeIn()+slideInVertically{it/3}){GlassSurface(shape=RoundedCornerShape(16.dp),color=Bg,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp)){Text(if(english)"Class summary" else "课堂总结",color=Brand,fontWeight=FontWeight.SemiBold);Text(if(english)"Key ideas, examples and next actions are ready." else "重点、课堂示例与待办事项已经整理完成。",style=MaterialTheme.typography.bodySmall,color=Muted)}}}
            AnimatedVisibility(phase>=2){Text(if(english)"Suggested: Academic English · today 14:30" else "建议关联：大学英语 · 今天 14:30",style=MaterialTheme.typography.bodySmall)}
            AnimatedVisibility(phase>=2){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf("Calculus" to Color(0xFF285B47),"English" to Color(0xFF344D7A),"Design" to Color(0xFF755241)).forEachIndexed {i,(name,color)->GlassSurface(color=color,shape=RoundedCornerShape(12.dp),border=if(phase>=3&&i==1)BorderStroke(2.dp,Mint)else null,modifier=Modifier.weight(1f).height(66.dp)){Column(Modifier.padding(7.dp)){Text(if(english)name else listOf("高等数学","大学英语","设计基础")[i],color=Color.White,fontSize=11.sp,maxLines=2);Text(listOf("08:00","14:30","16:00")[i],color=Color.White.copy(alpha=.78f),fontSize=9.sp)}}}}}
            AnimatedVisibility(phase>=4){GlassSurface(color=Mint,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()){Text(if(english)"Saved · View summary from the event" else "已保存 · 可从课程详情查看总结",Modifier.padding(9.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,fontWeight=FontWeight.Medium)}}
        }
    }
}

@Composable private fun AiTutorialScene(){
    val english=AppLanguage.english
    var phase by remember {mutableIntStateOf(0)}
    LaunchedEffect(Unit){while(true){phase=0;delay(650);phase=1;delay(850);phase=2;delay(900);phase=3;delay(850);phase=4;delay(1400)}}
    Box(Modifier.fillMaxWidth().height(252.dp).clip(RoundedCornerShape(24.dp)).background(SurfaceColor)){
        Column(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Text(if(english)"Kejian AI" else "课间 AI",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));Text(if(english)"Plus · 30" else "支持者 · 30",color=Brand,style=MaterialTheme.typography.labelMedium)}
            AnimatedVisibility(phase>=1,enter=fadeIn(tween(220))+slideInHorizontally{it/3}){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){GlassSurface(color=Brand,shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.Image,null,tint=Color.White,modifier=Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Text(if(english)"Schedule image" else "课表截图",color=Color.White)}}}}
            AnimatedContent(phase>=4,label="aiTutorialResult"){done->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Start){GlassSurface(color=if(done)Mint else Bg,shape=RoundedCornerShape(16.dp)){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){if(!done&&phase>=1)CircularProgressIndicator(Modifier.size(15.dp),strokeWidth=2.dp);if(!done&&phase>=1)Spacer(Modifier.width(7.dp));Text(if(done){if(english)"Task completed!" else "任务已完成！"}else if(phase>=1){if(english)"Reading layout and events…" else "正在识别版面与课程信息…"}else{if(english)"Upload an image or enter a task" else "上传图片或输入课表任务"},color=if(done)Brand else Muted)}}}}
            AnimatedVisibility(phase in 2..3,enter=fadeIn()+scaleIn(initialScale=.94f),exit=fadeOut()+scaleOut(targetScale=.96f)){GlassSurface(color=Bg,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(if(english)"Preview · 3 events" else "变更预览 · 3 节课",style=MaterialTheme.typography.labelLarge);Text(if(english)"Calculus  Mon 08:00" else "高等数学  周一 08:00",color=Brand,style=MaterialTheme.typography.bodySmall);Text(if(english)"Added only after confirmation" else "确认后才会放入课表",color=Muted,style=MaterialTheme.typography.bodySmall);LinearProgressIndicator(progress={if(phase==3)1f else .35f},modifier=Modifier.fillMaxWidth())}}}
        }
    }
}
