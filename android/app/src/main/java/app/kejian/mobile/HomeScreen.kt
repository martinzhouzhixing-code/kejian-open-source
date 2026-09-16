package app.kejian.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import java.time.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable fun HomeScreen(model:KejianViewModel,demo:OnboardingTargets?=null,onEdit:(Course,LocalDate)->Unit,onDeadline:(List<Deadline>)->Unit,onCreate:()->Unit,onBatch:()->Unit,onList:()->Unit,onSettings:()->Unit,onDrop:(Course,LocalDate,LocalDate,Int,Boolean)->Unit,onDeleteDrop:(Course,LocalDate)->Unit,onCopy:(Course,LocalDate)->Unit,onLogoBounds:(Rect)->Unit={},logoVisible:Boolean=true,onOpenNote:(String)->Unit={}){
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit){while(true){delay(30_000);now=ZonedDateTime.now()}}
    val originalDemo=remember(AppLanguage.code){onboardingPreviewSample()}
    val beat=demo?.beat?:0;val scene=demo?.scene?:0
    val demoData=originalDemo.first.copy(courses=originalDemo.first.courses.map{if(it.id=="tutorial-seminar"&&scene==1&&beat>=6)it.copy(start=615,end=690)else it},deadlines=if(scene==2&&beat>=2)originalDemo.second.deadlines else emptyList(),settings=model.data.settings.copy(termStart=originalDemo.first.settings.termStart,visibleStart=480,visibleEnd=1080))
    val data=if(demo!=null)demoData else model.data;val editing=if(demo!=null)scene==1&&beat in 1..7 else model.isEditing
    val shownWeek=if(demo!=null)demoData.settings.termStart else model.shownWeek
    val next=remember(data,now){upcoming(data,now).firstOrNull()};val week=weekNumber(shownWeek,data.settings.termStart)
    Column(Modifier.fillMaxSize()){
        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp).padding(top=LocalPageTopInset.current+12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.ic_launcher),"课间标志",Modifier.size(36.dp).onGloballyPositioned {onLogoBounds(it.boundsInRoot())}.then(if(logoVisible)Modifier else Modifier.graphicsLayer(alpha=0f)));Spacer(Modifier.width(10.dp));Text("课间",style=AppTextStyles.pageTitle,modifier=Modifier.weight(1f));TextButton(onClick=onSettings){Text(data.settings.termName.take(12)+" ⌄",style=MaterialTheme.typography.bodySmall,color=Muted)}}
            val nextHint=next?.let {occurrence->
                val day=when(occurrence.date){now.toLocalDate()->"今天";now.toLocalDate().plusDays(1)->"明天";else->"周${"一二三四五六日"[occurrence.date.dayOfWeek.value-1]}"}
                "下一节 · $day ${timeText(occurrence.course.start)}  ${occurrence.course.name}"
            }?:"未来 24 小时没有课程，休息一下吧～"
            Text("${now.monthValue} 月 ${now.dayOfMonth} 日，周${"一二三四五六日"[now.dayOfWeek.value-1]} · $nextHint",style=AppTextStyles.pageSubtitle,color=Muted,maxLines=1)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={model.shownWeek=model.shownWeek.minusWeeks(1)}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft,"上一周")}
            TextButton(onClick={model.shownWeek=monday(LocalDate.now())}){Text(if(week in 1..30)"第 $week 周" else "${model.shownWeek.monthValue}/${model.shownWeek.dayOfMonth}",color=Ink)}
            IconButton(onClick={model.shownWeek=model.shownWeek.plusWeeks(1)}){Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,"下一周")}
            Spacer(Modifier.weight(1f));if(editing)TextButton(onClick=onBatch){Text("表格编辑",fontSize=12.sp)};TextButton(onClick=onList){Text("列表",fontSize=12.sp)}
        }
        val palette=LocalAppPalette.current
        val context=androidx.compose.ui.platform.LocalContext.current
        val customBackdrop by produceState<android.graphics.Bitmap?>(null,data.settings.glassBackground,data.settings.customTheme,data.settings.appBackgroundUri,data.settings.appBackgroundBlur){
            value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
                if(!data.settings.glassBackground||!data.settings.customTheme)null else data.settings.appBackgroundUri?.let {LocalMediaStore.readAppBackground(context,it)}?.let {source->
                    if(data.settings.appBackgroundBlur<=.01f)source else FrostedBitmap.create(source,(data.settings.appBackgroundBlur*24).toInt()).also {source.recycle()}
                }
            }
        }
        AnimatedVisibility(editing){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={model.undo()},enabled=model.canUndo,contentPadding=PaddingValues(horizontal=12.dp)){Text("↶ 撤销")}
            TextButton(onClick={model.redo()},enabled=model.canRedo,contentPadding=PaddingValues(horizontal=12.dp)){Text("↷ 重做")}
            Spacer(Modifier.weight(1f));Text(if(model.hasDraftChanges)"未保存的草稿"else "编辑课表",fontSize=11.sp,color=Muted)
        }}
        var dragging by remember {mutableStateOf(false)}
        var native by remember {mutableStateOf<TimetableView?>(null)}
        var selected by remember {mutableStateOf<Course?>(null)}
        var selectedDate by remember {mutableStateOf<LocalDate?>(null)}
        var selectedRect by remember {mutableStateOf<android.graphics.RectF?>(null)}
        var glassFrame by remember {mutableStateOf<android.graphics.Bitmap?>(null)}
        var batchDelete by remember {mutableStateOf(false)}
        var batchSelection by remember {mutableStateOf<Set<ModuleSelection>>(emptySet())}
        LaunchedEffect(editing){if(!editing){batchDelete=false;batchSelection=emptySet()}}
        LaunchedEffect(demo?.scene,beat,native){
            if(demo!=null){
                if(scene==0&&beat==3){delay(100);native?.demoSelect("tutorial-seminar")}
                // CourseExpansion reverses the same animation at beat 6.
                if(scene==1&&beat==2)native?.demoSelect("tutorial-seminar")
                if(scene==1&&beat==4)native?.demoMove("tutorial-seminar",615)
                if(scene==1&&beat==6)native?.clearSelection()
            }
        }
        val menuAppearance=remember {Animatable(0f)}
        var retainedMenu by remember {mutableStateOf<Pair<Course,android.graphics.RectF>?>(null)}
        LaunchedEffect(selected?.id,editing){
            if(selected!=null&&selectedRect!=null)retainedMenu=selected!! to android.graphics.RectF(selectedRect!!)
            menuAppearance.animateTo(if(selected!=null&&editing)1f else 0f,spring(.86f,420f))
            if(selected==null)retainedMenu=null
        }
        val density=LocalDensity.current
        val dockPx=with(density){LocalDockInset.current.toPx()}
        val saveBottom by animateDpAsState(if(editing)80.dp+LocalDockInset.current else 12.dp+LocalDockInset.current,spring(dampingRatio=.86f,stiffness=340f),label="editSavePosition")
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).onboardingTarget("home.grid")){
            AndroidView(factory={context->TimetableView(context).also {native=it}},update={view->
                if(view.appData.courses!=data.courses||view.appData.settings!=data.settings)view.clearSelection()
                view.customBackdrop=customBackdrop;view.onGlassFrame={glassFrame=it};view.bottomObstruction=dockPx;view.appData=data;view.palette=palette;view.week=shownWeek;view.nextOccurrence=next;view.editingEnabled=editing;view.batchDeleteEnabled=batchDelete
                view.hideSelectedCourse=selected!=null&&!editing&&(!data.settings.glassBackground||glassFrame!=null)
                if(!view.isPinching)view.setAxisZoom(data.settings.gridZoomX,data.settings.gridZoomY)
                view.onAxisZoomChanged={x,y->if(demo==null)model.setZoom(x,y)};view.onCreate=onCreate;view.onDrop=onDrop;view.onDelete=onDeleteDrop;view.onDeadline=onDeadline
                view.onSelection={c,date,rect->glassFrame=null;selected=c;selectedDate=date;selectedRect=rect}
                view.onBatchSelection={batchSelection=it}
                view.onDragStateChange={dragging=it}
            },modifier=Modifier.fillMaxSize().then(if(editing&&(selected!=null||retainedMenu!=null))Modifier.glassCapture(LocalGlassLayers.current.timetable)else Modifier))
            Box(Modifier.fillMaxWidth().height(48.dp).align(Alignment.TopCenter).onboardingTarget("home.deadlines"))
            // Menu is a Compose surface so its two actions remain accessible to TalkBack.
            val chosen=selected;val bounds=selectedRect
            if(retainedMenu!=null&&!dragging&&!batchDelete&&editing){
                val (chosen,bounds)=retainedMenu!!
                val menuWidth=164.dp;val menuHeight=if(editing)104.dp else 136.dp
                val left=with(density){bounds.left.toDp()};val right=with(density){bounds.right.toDp()}
                val x=(if(right+menuWidth+8.dp<maxWidth)right+8.dp else left-menuWidth-8.dp).coerceIn(8.dp,(maxWidth-menuWidth-8.dp).coerceAtLeast(8.dp))
                val y=with(density){bounds.top.toDp()}.coerceIn(50.dp,(maxHeight-menuHeight-8.dp).coerceAtLeast(50.dp))
                val menuShape=RoundedCornerShape(18.dp)
                Box(Modifier.offset(x,y).width(menuWidth).graphicsLayer {alpha=menuAppearance.value;scaleX=.92f+.08f*menuAppearance.value;scaleY=scaleX;translationY=8.dp.toPx()*(1f-menuAppearance.value)}
                    .shadow(8.dp,menuShape).clip(menuShape).testTag("course-action-menu")) {
                    // Capture only the timetable; content and shadows must never enter this blur.
                    Box(Modifier.matchParentSize().liveGlass(menuShape,SurfaceColor,LocalGlassLayers.current.timetable,10.dp,tintAlpha=if(LocalAppPalette.current.dark).58f else .44f)
                        .background(if(LocalGlassEnabled.current)Color.Transparent else SurfaceColor).glassHighlight(menuShape))
                    Column(Modifier.padding(6.dp)){
                        TextButton(onClick={val date=selectedDate?:return@TextButton;native?.clearSelection();onCopy(chosen,date)},modifier=Modifier.fillMaxWidth()){Text("复制",color=Ink)}
                        TextButton(onClick={val date=selectedDate?:return@TextButton;native?.clearSelection();onEdit(chosen,date)},modifier=Modifier.fillMaxWidth()){Text("自定义",color=Ink)}
                    }
                }
            }
            if(chosen!=null&&bounds!=null&&!dragging&&!batchDelete&&!editing&&(!data.settings.glassBackground||glassFrame!=null)){
                val compactLabels=remember(chosen,selectedDate,bounds,palette){native?.courseLabelSnapshot(chosen,bounds,selectedDate?:shownWeek)}
                CourseExpansion(chosen,selectedDate?:model.shownWeek.plusDays((chosen.day-1).toLong()),bounds,maxWidth,maxHeight,data.notes,compactLabels=compactLabels,glassFrame=glassFrame,dockInset=LocalDockInset.current,autoClose=demo!=null&&scene==0&&beat>=6,remark=data.weeklyRemarks[weeklyRemarkKey(chosen.id,selectedDate?:model.shownWeek)].orEmpty(),onSaveRemark={value->val key=weeklyRemarkKey(chosen.id,selectedDate?:model.shownWeek);model.commit(model.data.copy(weeklyRemarks=if(value.isBlank())model.data.weeklyRemarks-key else model.data.weeklyRemarks+(key to value)),"本周备注已保存",false)},onOpenNote=onOpenNote){native?.clearSelection()}
            }
            // Fixed-color tools avoid sampling the moving timetable for every small button.
            CompositionLocalProvider(LocalControlOnTimetable provides true,LocalLiquidEnabled provides false,LocalGlassEnabled provides false){
            androidx.compose.animation.AnimatedVisibility(!dragging&&!batchDelete&&(selected==null||editing),modifier=Modifier.align(Alignment.BottomEnd).padding(end=12.dp,bottom=saveBottom),enter=fadeIn()+scaleIn(),exit=fadeOut()+scaleOut()){
                EditSaveButton(editing,modifier=Modifier.onboardingTarget("home.edit"),onEdit={native?.clearSelection();model.beginEditing()},onSave={batchDelete=false;native?.clearSelection();model.saveEditing()})
            }
            val scopeBottom by animateDpAsState(if(batchDelete)76.dp+LocalDockInset.current else 144.dp+LocalDockInset.current,spring(dampingRatio=.82f,stiffness=320f),label="scopeMove")
            androidx.compose.animation.AnimatedVisibility(editing&&!dragging,modifier=Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=scopeBottom),enter=fadeIn()+slideInVertically {it/2},exit=fadeOut()+slideOutVertically {it/2}){
                GlassFilledTonalButton(onClick={native?.clearSelection();model.commit(data.copy(settings=data.settings.copy(editAllWeeks=!data.settings.editAllWeeks)),"",false)},modifier=Modifier.width(110.dp).height(48.dp),shape=RoundedCornerShape(24.dp),contentPadding=PaddingValues(horizontal=10.dp)){
                    Text(if(data.settings.editAllWeeks)"所有周次 ⇄"else "仅本次 ⇄",fontSize=13.sp,fontWeight=FontWeight.Medium)
                }
            }
            val deleteBottom by animateDpAsState(if(batchDelete)12.dp+LocalDockInset.current else 208.dp+LocalDockInset.current,spring(dampingRatio=.82f,stiffness=300f),label="batchDeleteY")
            androidx.compose.animation.AnimatedVisibility(editing&&!dragging,modifier=Modifier.align(Alignment.BottomEnd).padding(end=10.dp,bottom=deleteBottom),enter=fadeIn()+scaleIn(),exit=fadeOut()+scaleOut()){
                GlassFilledTonalButton(onClick={
                    if(!batchDelete){native?.clearSelection();batchDelete=true;batchSelection=emptySet()}
                    else if(batchSelection.isEmpty()){batchDelete=false}
                    else {
                        var changed=data
                        if(data.settings.editAllWeeks){val ids=batchSelection.map {it.courseId}.toSet();changed=changed.copy(courses=changed.courses.filterNot {it.id in ids})}
                        else batchSelection.sortedBy {it.date}.forEach {selection->changed.courses.find {it.id==selection.courseId}?.let {course->changed=changeCourse(changed,course,null,selection.date,selection.date)}}
                        model.commit(changed,"已批量删除 ${batchSelection.size} 个课程模块 · 可撤销");batchDelete=false;batchSelection=emptySet()
                    }
                },modifier=Modifier.width(110.dp).height(48.dp),shape=RoundedCornerShape(24.dp),colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(batchDelete)MaterialTheme.colorScheme.errorContainer else Mint,contentColor=if(batchDelete)MaterialTheme.colorScheme.onErrorContainer else Brand),contentPadding=PaddingValues(horizontal=10.dp)){
                    AnimatedContent(if(!batchDelete)"批量删除" else if(batchSelection.isEmpty())"退出框选" else "删除 ${batchSelection.size} 项",label="batchDeleteLabel"){Text(it,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=1)}
                }
            }
            if(LocalDockInset.current>0.dp&&selected==null&&!dragging&&!batchDelete&&demo==null){
                GlassFilledTonalButton(onClick={native?.setAxisZoom(1f,1f);native?.resetViewport();model.setZoom(1f)},
                    modifier=Modifier.align(Alignment.BottomStart).padding(start=12.dp,bottom=LocalDockInset.current+12.dp),
                    shape=RoundedCornerShape(18.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)){
                    Text(if(AppLanguage.english)"Reset view" else "重置视图",fontSize=11.sp)
                }
            }
            }
            if(batchDelete)GlassSurface(Modifier.align(Alignment.TopCenter).padding(top=8.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.errorContainer.copy(alpha=.94f),shadowElevation=6.dp){Text("拖动框选，或点按课程切换选择 · 靠近边缘自动滚动",Modifier.padding(horizontal=12.dp,vertical=8.dp),fontSize=10.sp,color=MaterialTheme.colorScheme.onErrorContainer)}
        }
        if(LocalDockInset.current==0.dp)Row(Modifier.fillMaxWidth().heightIn(min=36.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Text(if(editing)"长按拖动 · 点保存后生效"else "${timeText(scheduleTimeRange(data,shownWeek).first)} 起 · 双指调整行距/列距",fontSize=10.sp,color=Muted,modifier=Modifier.weight(1f))
            TextButton(onClick={native?.setAxisZoom(1f,1f);native?.resetViewport();model.setZoom(1f)},contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp)){Text("列 ${(data.settings.gridZoomX*100).toInt()}% / 行 ${(data.settings.gridZoomY*100).toInt()}% · 重置",fontSize=9.sp)}
        }

    }
}

@Composable internal fun CourseExpansion(course:Course,date:LocalDate,origin:android.graphics.RectF,maxWidth:Dp,maxHeight:Dp,notes:List<LessonNote>,compactLabels:android.graphics.Bitmap?=null,glassFrame:android.graphics.Bitmap?=null,dockInset:Dp=0.dp,autoClose:Boolean=false,remark:String="",onSaveRemark:(String)->Unit={},onOpenNote:(String)->Unit,onDismiss:()->Unit){
    var remarkDraft by remember(course.id,monday(date),remark){mutableStateOf(remark)}
    var remarkEditor by remember(course.id,date){mutableStateOf(false)}
    if(remarkEditor)GlassAlertDialog(onDismissRequest={remarkEditor=false},title={Text(if(AppLanguage.english)"This week's course notes"else"本周课程备注")},text={
        OutlinedTextField(remarkDraft,{remarkDraft=it.take(10000)},placeholder={Text(if(AppLanguage.english)"Topics, key points or homework"else"记录本周内容、重点或作业")},modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=8)
    },confirmButton={GlassButton(onClick={onSaveRemark(remarkDraft);remarkEditor=false}){Text(if(AppLanguage.english)"Save"else"保存")}},dismissButton={TextButton(onClick={remarkEditor=false}){Text(if(AppLanguage.english)"Cancel"else"取消")}})
    val progress=remember(course.id,date){Animatable(0f)}
    LaunchedEffect(course.id,date){progress.animateTo(1f,spring(dampingRatio=.82f,stiffness=310f))}
    val scope=rememberCoroutineScope();var closing by remember(course.id,date){mutableStateOf(false)}
    fun close(){if(closing)return;closing=true;scope.launch {progress.animateTo(0f,spring(dampingRatio=.82f,stiffness=310f));onDismiss()}}
    LaunchedEffect(autoClose){if(autoClose)close()}
    BackHandler {close()}
    val density=LocalDensity.current
    val originX=with(density){origin.left.toDp()};val originY=with(density){origin.top.toDp()}
    val originW=with(density){origin.width().toDp()};val originH=with(density){origin.height().toDp()}
    val targetW=(maxWidth-32.dp).coerceAtMost(360.dp);val availableHeight=(maxHeight-dockInset-24.dp).coerceAtLeast(100.dp);val targetH=(availableHeight-40.dp).coerceAtMost(500.dp)
    val targetX=(maxWidth-targetW)/2
    val targetY=if(originY+originH/2<availableHeight/2)(availableHeight-targetH-20.dp).coerceAtLeast(20.dp) else 20.dp
    fun between(start:Dp,end:Dp)=start+(end-start)*progress.value
    val x=between(originX,targetX);val y=between(originY,targetY)
    val width=between(originW,targetW);val height=between(originH,targetH)
    val scrim=.34f*progress.value
    val detailAlpha=((progress.value-.28f)/.42f).coerceIn(0f,1f)
    val compactAlpha=(1f-progress.value/.42f).coerceIn(0f,1f)
    val note=notes.filter {it.courseId==course.id&&(it.occurrenceDate==null||it.occurrenceDate==date)}.maxByOrNull {it.createdAt}
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=scrim)).semantics {contentDescription=if(AppLanguage.english)"Close event details"else"收起课程详情"}.clickable(interactionSource=remember {androidx.compose.foundation.interaction.MutableInteractionSource()},indication=null,onClick={close()}))
    val palette=LocalAppPalette.current
    val cardShape=RoundedCornerShape(CourseGlassMaterial.cornerDp.dp)
    androidx.compose.material3.Surface(Modifier.offset(x,y).size(width,height).testTag("course-expansion").clickable(interactionSource=remember {androidx.compose.foundation.interaction.MutableInteractionSource()},indication=null,onClick={}),shape=cardShape,color=Color.Transparent,shadowElevation=between(2.dp,18.dp)){
        Box(Modifier.fillMaxSize()){
        val glass=LocalGlassEnabled.current
        val liquid=LocalLiquidEnabled.current
        val lens=remember {if(android.os.Build.VERSION.SDK_INT>=33)CourseLiquidLens()else null}
        Canvas(Modifier.fillMaxSize().then(if(glass)Modifier.glassHighlight(cardShape)else Modifier)){
            val frame=glassFrame
            if(glass&&frame!=null){
                if(liquid&&lens!=null&&android.os.Build.VERSION.SDK_INT>=33){
                    val c=drawContext.canvas.nativeCanvas
                    c.save();c.translate(-x.toPx(),-y.toPx())
                    lens.draw(c,frame,android.graphics.RectF(x.toPx(),y.toPx(),x.toPx()+size.width,y.toPx()+size.height),maxWidth.roundToPx(),maxHeight.roundToPx(),CourseGlassMaterial.cornerDp.dp.toPx(),density.density)
                    c.restore()
                }else drawImage(frame.asImageBitmap(),dstOffset=IntOffset(-x.roundToPx(),-y.roundToPx()),dstSize=IntSize(maxWidth.roundToPx(),maxHeight.roundToPx()),filterQuality=androidx.compose.ui.graphics.FilterQuality.Medium)
            }
            drawRect(Color(palette.fills[course.color]).copy(alpha=if(glass&&frame!=null){if(liquid)CourseGlassMaterial.tintAlpha else courseGlassAlpha}else 1f))
        }
        Column(Modifier.wrapContentSize(Alignment.TopStart,unbounded=true).requiredSize(targetW,targetH).graphicsLayer {translationX=with(density){((width-targetW)*.08f).toPx()};translationY=with(density){((height-targetH)*.05f).toPx()}}.padding(22.dp).alpha(detailAlpha),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(course.name,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold,color=Color(palette.courseInk[course.color]))
            Text("${date.monthValue} 月 ${date.dayOfMonth} 日 · 周${"一二三四五六日"[date.dayOfWeek.value-1]}",color=Color(palette.courseInk[course.color]).copy(alpha=.78f))
            Text("${timeText(course.start)} — ${timeText(course.end)} · ${course.duration} 分钟",style=MaterialTheme.typography.titleMedium,color=Color(palette.courseInk[course.color]))
            if(course.place.isNotBlank())Text("地点  ${course.place}",color=Color(palette.courseInk[course.color]).copy(alpha=.82f))
            if(course.teacher.isNotBlank())Text("教师  ${course.teacher}",color=Color(palette.courseInk[course.color]).copy(alpha=.82f))
            note?.let {saved->GlassSurface(Modifier.clickable {onOpenNote(saved.id)},shape=RoundedCornerShape(14.dp),color=Color(palette.courseInk[course.color]).copy(alpha=.12f)){Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Filled.Description,null,Modifier.size(17.dp),tint=Color(palette.courseInk[course.color]));Spacer(Modifier.width(7.dp));Text(if(saved.summary.isNotBlank())"查看课程总结" else "查看已保存录音",color=Color(palette.courseInk[course.color]),fontWeight=FontWeight.Medium)}}}
            }
            Text(if(AppLanguage.english)"This week's notes"else"本周课程备注",style=MaterialTheme.typography.labelLarge,color=Color(palette.courseInk[course.color]))
            Text(remark.ifBlank {if(AppLanguage.english)"Add topics, key points or homework"else"记录本周内容、重点或作业"},maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=Color(palette.courseInk[course.color]))
            GlassButton(onClick={remarkDraft=remark;remarkEditor=true}){Text(if(AppLanguage.english)"Edit notes"else"编辑备注")}
            Text("轻触空白处收起",style=MaterialTheme.typography.labelMedium,color=Color(palette.courseInk[course.color]).copy(alpha=.62f))
        }
        if(compactLabels!=null)Image(compactLabels.asImageBitmap(),null,Modifier.wrapContentSize(Alignment.TopStart,unbounded=true).requiredSize(originW,originH).graphicsLayer {alpha=compactAlpha;translationX=with(density){(16.dp*progress.value).toPx()};translationY=translationX},contentScale=ContentScale.FillBounds)
        else Column(Modifier.wrapContentSize(Alignment.TopStart,unbounded=true).requiredSize(originW,originH).padding(horizontal=6.dp,vertical=5.dp).alpha(compactAlpha)){
            Text(course.name,fontSize=12.sp,fontWeight=FontWeight.Medium,color=Color(palette.courseInk[course.color]),maxLines=2,lineHeight=17.sp)
            if(originH>36.dp)Text(timeText(course.start),fontSize=10.sp,color=Color(palette.courseInk[course.color]),maxLines=1)
            if(originH>72.dp)Text(course.room.ifBlank {course.address},fontSize=10.sp,color=Color(palette.courseInk[course.color]),maxLines=1)
        }
        }
    }
}

@Composable fun CourseListScreen(data:AppData,week:LocalDate,onBack:()->Unit,onEdit:(Course,LocalDate)->Unit,onCreate:()->Unit,editable:Boolean=false){
    val rows=(0..6).flatMap { offset->val date=week.plusDays(offset.toLong());data.courses.filter { it.occurs(date,data.settings.termStart) }.map { Occurrence(it,date) } }.sortedBy { it.startAt() }
    Column(Modifier.fillMaxSize()){
        ScreenHeading("本周课程",onBack){if(editable)TextButton(onClick=onCreate){Text("新建")}}
        if(rows.isEmpty())Text("本周暂无课程，点击新建开始安排。",modifier=Modifier.padding(24.dp),color=Muted)
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(rows,key={it.course.id+it.date}){o->WhiteCard(Modifier.clickable { onEdit(o.course,o.date) }){Text(o.course.name,style=MaterialTheme.typography.titleMedium);Text("周${"一二三四五六日"[o.date.dayOfWeek.value-1]} · ${timeText(o.course.start)}–${timeText(o.course.end)}",color=Brand);Text("课堂地址：${o.course.place}",color=Muted);if(o.course.teacher.isNotBlank())Text("老师：${o.course.teacher}",color=Muted)}}}
    }
}
