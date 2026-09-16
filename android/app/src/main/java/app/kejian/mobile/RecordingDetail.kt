package app.kejian.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** One recording owns one full page; audio, notes and association never crowd the recorder. */
@Composable fun RecordingDetailScreen(
    model:KejianViewModel,noteId:String,onBack:()->Unit,onAccount:()->Unit,
    onOpenMindMap:(String)->Unit,demoNote:LessonNote?=null
){
    val context=LocalContext.current
    val note=demoNote?:model.savedData.notes.firstOrNull {it.id==noteId}
    if(note==null){
        Column(Modifier.fillMaxSize().padding(top=LocalPageTopInset.current).padding(20.dp)){
            TextButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,null);Text(recordingCopy("返回","Back"))}
            Text(recordingCopy("这份录音暂时无法找到","This recording is unavailable"))
        };return
    }
    val demo=demoNote!=null
    val prefs=remember(context){context.getSharedPreferences("recording_summary_preferences",0)}
    val prefKey="${model.cloudSession?.userId?:"local"}:$noteId"
    var summaryLanguage by remember(prefKey){mutableStateOf(prefs.getString("$prefKey.language",null)?:if(AppLanguage.english)"en"else"zh-CN")}
    var summaryRequirements by remember(prefKey){mutableStateOf(prefs.getString("$prefKey.requirements","")?:"")}
    var quoting by remember(noteId){mutableStateOf(false)}
    var confirmSummaryCharge by remember(noteId){mutableStateOf(false)}
    val pendingOptions=BackgroundTaskResults.pendingAudio(context)?.takeIf {it.path==note.audioPath&&it.ownerId==model.cloudSession?.userId}
    LaunchedEffect(pendingOptions?.requestId){pendingOptions?.let {summaryLanguage=it.summaryLanguage;summaryRequirements=it.summaryRequirements}}
    LaunchedEffect(prefKey,summaryLanguage,summaryRequirements){if(!demo)prefs.edit().putString("$prefKey.language",summaryLanguage).putString("$prefKey.requirements",summaryRequirements).apply()}
    val resummaryBusy=model.resummaryBusyNoteId==noteId

    val listState=rememberLazyListState()
    val chat=rememberRecordingChat(model,note,demo)
    var followChat by remember(note.id){mutableStateOf(false)}
    var chatScrollRequest by remember(note.id){mutableIntStateOf(0)}
    var tab by remember(noteId){mutableIntStateOf(0)}
    val guideKey=LocalOnboardingTargets.current?.activeKey
    LaunchedEffect(guideKey,demo){
        if(demo){
            tab=if(guideKey=="record.detail.insights")2 else 0
            val index=when(guideKey){"record.detail.actions"->2;"record.detail.link"->1;"record.detail.mindmap"->3;"record.detail.summary"->4;"record.detail.insights"->5;else->0}
            kotlinx.coroutines.delay(550)
            listState.animateScrollToItem(index)
        }
    }
    LaunchedEffect(chatScrollRequest,chat.turns,tab){
        if(followChat){
            delay(120)
            val last=listState.layoutInfo.totalItemsCount-1
            if(last>=0)listState.animateScrollToItem(last)
        }
    }
    var chooseCourse by remember(noteId){mutableStateOf(false)}
    var rename by remember {mutableStateOf(false)}
    var titleDraft by remember(noteId){mutableStateOf(note.title)}
    var removeAudio by remember {mutableStateOf(false)}
    var notice by remember(noteId){mutableStateOf<String?>(null)}
    LaunchedEffect(model.resummaryRevision){
        if(model.resummaryRevision>0&&model.resummaryMessageNoteId==noteId){tab=0;notice=model.resummaryMessage}
    }
    LaunchedEffect(AiBackgroundRuntime.revision){if(!demo)model.consumeAiBackgroundResults()}
    var exporting by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val localNotes=remember(context){RecordingInsightsStore(context)}
    DisposableEffect(localNotes){onDispose{localNotes.close()}}
    val checklistOwner=model.cloudSession?.userId?:"local"
    val localRevision=RecordingInsightsStore.revision
    var checked by remember(checklistOwner,note.id){mutableStateOf<Set<String>>(emptySet())}
    LaunchedEffect(checklistOwner,note.id,localRevision,demo){if(!demo)try {checked=withContext(Dispatchers.IO){localNotes.checked(checklistOwner,note.id)}}catch(_:Exception){notice=recordingCopy("本机清单暂时无法读取。","Local checklist is temporarily unavailable.")}}
    val film=LocalOnboardingTargets.current
    val state=if(demo&&film?.scene==9&&film.beat>=3)RecordingLibrary.FileState(stage=when(film.beat){3,4->"uploading";5->"queued";6->"transcribing";else->"summarizing"},progress=(film.beat*13).coerceAtMost(100))else recordingFileState(context,note)
    val otherTaskBusy=(model.audioBusy||BackgroundTaskRuntime.audioRunning)&&!state.busy
    val file=note.audioPath?.let(::File)?.takeIf {it.isFile}
    val mapRevision=RecordingLibrary.revision
    val map=remember(note.id,noteContentDigest(note),mapRevision){RecordingLibrary.loadMap(context,note)}
    val mapBusy=model.mindMapBusyNoteId==note.id
    val linked=if(demo)null else note.courseId?.let {id->model.savedData.courses.firstOrNull {it.id==id}}?.let {course->
        Occurrence(course,note.occurrenceDate?:note.createdAt.atZone(ZoneId.systemDefault()).toLocalDate())}
    val suggestion=remember(note.createdAt,model.savedData.courses,demo){if(demo)null else suggestedRecordingCourse(model.savedData,note.createdAt.atZone(ZoneId.systemDefault()))}
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")){uri->
        if(uri!=null&&file!=null)scope.launch {
            exporting=true
            try {withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use {out->file.inputStream().use {it.copyTo(out)}}?:error("No output stream")}
                notice=recordingCopy("录音已导出到所选位置","Audio exported to your selected location.")
            }catch(c:CancellationException){throw c}catch(_:Exception){notice=recordingCopy("无法导出音频，请换个位置重试。原文件仍在本机。","Could not export. Try another location; the original audio remains on this device.")}
            finally{exporting=false}
        }
    }
    fun transcribe(){
        if(demo)return
        if(file!=null&&BackgroundTaskResults.hasAudioForPath(context,file.absolutePath)){
            model.transcribeRecording(file,note.durationSeconds,summaryLanguage,summaryRequirements);notice=model.audioMessage;return
        }
        if(model.cloudSession?.profile?.audioSummary!=true){onAccount();return}
        if(file!=null){model.transcribeRecording(file,note.durationSeconds,summaryLanguage,summaryRequirements);if(!model.audioBusy)notice=model.audioMessage}
    }
    val notificationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){transcribe()}
    LaunchedEffect(BackgroundTaskRuntime.status){if(!demo)model.consumeBackgroundResults()}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(top=LocalPageTopInset.current).padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,recordingCopy("返回","Back"))}
            Text(recordingCopy("录音笔记","Recording notes"),Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
            if(note.summary.isNotBlank())TextButton(onClick={tab=2;followChat=true;chatScrollRequest++},modifier=Modifier.testTag("insights_shortcut")){
                Icon(Icons.Filled.AutoAwesome,null,Modifier.size(16.dp));Spacer(Modifier.width(4.dp));Text(recordingCopy("向笔记提问","Ask notes"),fontSize=13.sp)
            }
            IconButton(onClick={titleDraft=note.title;rename=true},enabled=!demo){Icon(Icons.Filled.Edit,recordingCopy("重命名录音","Rename recording"))}
        }
        val density=LocalDensity.current
        var composerHeight by remember {mutableStateOf(0.dp)}
        // Draw the document behind the composer. End padding allows the last message to scroll clear.
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()){
        LazyColumn(Modifier.fillMaxSize().glassCapture(LocalGlassLayers.current.timetable).testTag("recording_note_list"),state=listState,
            contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=composerHeight+12.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            item {
                Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
                    Text(note.title,fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.SemiBold)
                    Text("${note.createdAt.atZone(ZoneId.systemDefault()).toLocalDate()} · ${recordingTimer(note.durationSeconds)}",fontSize=13.sp,color=Muted)
                    Text(recordingStateLabel(state,note),fontSize=13.sp,color=Brand)
                }
            }
            item {
                GlassSurface(onClick={chooseCourse=true},enabled=!demo,modifier=Modifier.fillMaxWidth().onboardingTarget("record.detail.link").testTag("recording_course_link"),shape=RoundedCornerShape(18.dp),color=Mint){
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        Icon(Icons.Filled.Event,null,tint=Brand)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                            Text((if(demo)demoNote?.title else linked?.course?.name)?:recordingCopy("独立录音 · 未关联课程","Independent recording · no linked event"),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold)
                            Text(if(demo)recordingCopy("演示课程 · 可关联、改选或保持独立","Example event · link, change or keep independent")else linked?.let {"${it.date} · ${timeText(it.course.start)} · "+recordingCopy("点击更换或取消","tap to change or unlink")}
                                ?:suggestion?.let {recordingCopy("建议关联：${it.course.name}","Suggested: ${it.course.name}")}
                                ?:recordingCopy("可随时从课表选择，不影响笔记","Link an event at any time; your notes stay unchanged"),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                        }
                        Icon(Icons.Filled.ChevronRight,null,tint=Muted)
                    }
                }
            }
            if(demo&&film?.scene==11&&film.beat in 1..4)item {
                val sample=remember(AppLanguage.code){onboardingPreviewSample().first}
                Text(recordingCopy("点选课表里的模块即可关联","Choose a card to link this recording"),color=Brand)
                androidx.compose.ui.viewinterop.AndroidView(factory={TimetableView(it)},update={view->view.appData=sample;view.palette=AppPalettes.forContext(context,model.data.settings.themeMode,model.data.settings.skin);view.week=sample.settings.termStart;if(film.beat>=3)view.post {view.demoSelect("tutorial-seminar")}},modifier=Modifier.fillMaxWidth().height(270.dp).onboardingTarget("record.detail.link"))
            }
            item {
                Column(Modifier.fillMaxWidth().onboardingTarget("record.detail.actions"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(file!=null&&!demo)LocalRecordingPlayer(file)
                    Column(Modifier.fillMaxWidth().animateContentSize(),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text(recordingCopy("总结语言","Summary language"),fontWeight=FontWeight.SemiBold,fontSize=14.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            FilterChip(selected=summaryLanguage=="zh-CN",onClick={summaryLanguage="zh-CN"},enabled=!demo&&!state.busy&&!resummaryBusy&&pendingOptions==null,label={Text(recordingCopy("中文","Chinese"))},modifier=Modifier.testTag("summary_language_zh"))
                            FilterChip(selected=summaryLanguage=="en",onClick={summaryLanguage="en"},enabled=!demo&&!state.busy&&!resummaryBusy&&pendingOptions==null,label={Text("English")},modifier=Modifier.testTag("summary_language_en"))
                        }
                        OutlinedTextField(value=summaryRequirements,onValueChange={summaryRequirements=it.take(1000)},
                            label={Text(recordingCopy("你的要求（选填）","Your instructions (optional)"))},
                            placeholder={Text(recordingCopy("例如：约2000字，突出考试重点，作业单独列出","For example: about 2000 words, highlight exam topics, list assignments separately"))},
                            enabled=!demo&&!state.busy&&!resummaryBusy&&pendingOptions==null,minLines=2,maxLines=5,modifier=Modifier.fillMaxWidth().testTag("summary_requirements"),shape=RoundedCornerShape(14.dp))
                        Text(recordingCopy("默认1500–3000字；填写字数要求时优先采用你的要求。内容较少的录音不会凑字数。","Defaults to 1500–3000 words; your requested length takes priority. Short recordings are not padded."),fontSize=12.sp,color=Muted)
                        if(pendingOptions!=null)Text(recordingCopy("当前上传任务沿用提交时的语言与要求；完成后可重新总结。","This upload keeps its original language and instructions. You can regenerate afterward."),fontSize=12.sp,color=Muted)
                    }
                    if(note.summary.isBlank()){
                        GlassButton(onClick={
                            if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED&&!demo&&state.stage!="save_pending"&&model.cloudSession?.profile?.audioSummary==true)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else transcribe()
                        },enabled=!demo&&!state.busy&&!otherTaskBusy&&file!=null,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("recording_transcribe"),shape=RoundedCornerShape(16.dp)){
                            if(state.busy){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                            Text(if(state.stage=="save_pending")recordingCopy("重试保存已完成的笔记","Retry saving completed notes")else if(otherTaskBusy)recordingCopy("另一份录音正在处理中","Another recording is processing")else if(state.busy)recordingCopy("正在后台处理","Processing in background")else if(state.stage in setOf("failed","paused"))recordingCopy("继续转写与总结","Continue transcription & notes")else recordingCopy("转写与重点总结","Transcribe & create study notes"))
                        }
                        Text(if(demo)recordingCopy("示例文件 · 教程不会上传音频","Example only · this guide never uploads audio")else if(file==null)recordingCopy("此设备没有原始音频。已保存的文字笔记仍可阅读。","The original audio is not on this device. Saved text notes remain readable.")else
                            recordingCopy("历史估算约 ${friendlyDuration(estimatedAudioProcessingSeconds(note.durationSeconds))}。排队与整篇总结可能更久，仅供参考；可离开本页，完成后会通知你。","Historical estimate: ${friendlyDuration(estimatedAudioProcessingSeconds(note.durationSeconds))}. Queueing and whole-transcript processing may take longer. You can leave this page; we will notify you when ready."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    }
                    if(note.summary.isNotBlank()&&note.transcript.isNotBlank()){
                        GlassOutlinedButton(onClick={
                            if(model.cloudSession==null){onAccount()}else scope.launch {
                                quoting=true
                                try {
                                    val quote=model.quoteRecordingSummary(note,summaryLanguage,summaryRequirements)
                                    if(quote.optBoolean("usesTokens"))confirmSummaryCharge=true
                                    else model.regenerateRecordingSummary(noteId,summaryLanguage,summaryRequirements,false)
                                }catch(c:CancellationException){throw c}catch(_:Exception){notice=recordingCopy("暂时无法连接 AI 服务，请检查网络与 API Key。","Could not reach the AI service. Check your connection and API Key.")}
                                finally{quoting=false}
                            }
                        },enabled=!demo&&!state.busy&&!quoting&&model.resummaryBusyNoteId==null,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("summary_regenerate"),shape=RoundedCornerShape(16.dp)){
                            if(resummaryBusy||quoting){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))}
                            Text(if(resummaryBusy)recordingCopy("正在重新总结","Regenerating notes")else recordingCopy("重新总结","Regenerate notes"))
                        }
                        Text(recordingCopy("重新总结使用个人 API Key，按服务商计费；无需再次上传音频。失败保留原笔记。","Regeneration uses your personal API Key and provider billing. No audio re-upload is needed. Failed attempts keep the original notes."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                        if(model.resummaryMessageNoteId==noteId)model.resummaryMessage?.let {Text(it,fontSize=13.sp,color=Brand,modifier=Modifier.testTag("summary_result_status"))}
                    }
                    if(state.stage=="uploading")LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth(),color=Brand)
                    if(state.stage in setOf("failed","paused","save_pending")&&state.message.isNotBlank())RecordingMessage(if(AppLanguage.english&&state.message.any {it.code in 0x4e00..0x9fff})"This task is paused. Your original audio and result are kept. Retry after checking device storage and connection."else state.message,true)
                    if(file!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        GlassOutlinedButton(onClick={exporter.launch(file.name)},enabled=!exporting&&!demo,modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp)){
                            Icon(Icons.Filled.IosShare,null,Modifier.size(17.dp));Spacer(Modifier.width(6.dp));Text(recordingCopy("导出音频","Export audio"),fontSize=13.sp)
                        }
                        if(note.summary.isNotBlank())TextButton(onClick={removeAudio=true},enabled=!demo&&!state.busy){Text(recordingCopy("移除本机音频","Remove audio"),fontSize=12.sp)}
                    }
                }
            }
            if(note.summary.isNotBlank())item {
                Column(Modifier.fillMaxWidth().onboardingTarget("record.detail.mindmap"),verticalArrangement=Arrangement.spacedBy(7.dp)){
                    GlassOutlinedButton(onClick={if(map!=null||demo)onOpenMindMap(note.id)else model.generateRecordingMindMap(note.id){onOpenMindMap(note.id)}},
                        enabled=!mapBusy&&(demo||model.mindMapBusyNoteId==null),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("recording_mind_map"),shape=RoundedCornerShape(16.dp)){
                        if(mapBusy)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)else Icon(Icons.Filled.AccountTree,null,Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if(mapBusy)recordingCopy("正在生成思维导图","Creating your mind map")else if(map!=null)recordingCopy("打开思维导图","Open mind map")else recordingCopy("生成思维导图","Generate a mind map"))
                    }
                    Text(if(mapBusy)recordingCopy("正在后台生成，可切换应用；完成后会保留在本机。","Working in the background. You may switch apps; the result will be saved locally.")else if(map!=null)recordingCopy("已保存在本机，再次查看不消耗额度","Saved on this device. Viewing it again uses no tokens.")else
                        recordingCopy("根据完整笔记生成 · 使用个人 API Key，按服务商计费","Built from your notes · uses your own provider API Key"),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    model.mindMapMessage?.takeIf {!demo}?.let {RecordingMessage(it,true)}
                }
            }
            notice?.let {text->item {RecordingMessage(text,false)}}
            if(note.summary.isNotBlank()||note.transcript.isNotBlank()){
                item {
                    Column(Modifier.fillMaxWidth().onboardingTarget("record.detail.summary"),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            FilterChip(selected=tab==0,onClick={tab=0},label={Text(recordingCopy("课堂笔记","Detailed notes"))})
                            FilterChip(selected=tab==1,onClick={tab=1},label={Text(recordingCopy("逐字稿","Transcript"))})
                            FilterChip(selected=tab==2,onClick={tab=2},label={Text(recordingCopy("向笔记提问","Ask notes"))})
                        }
                        Text(if(tab==0)recordingCopy("知识、细节与行动，一起留下。","Keep the knowledge, details, and next steps.")else if(tab==2)recordingCopy("把不清楚的问题，交给这份笔记。","Ask this note about anything you want to clarify.")else
                            recordingCopy("转写内容可能有误，重要信息请对照原始音频。","Transcripts may contain errors. Check important details against the original audio."),fontSize=12.sp,lineHeight=18.sp,color=Muted)
                    }
                }
                if(tab==0){
                    if(noteDocumentBlocks(note.summary).firstOrNull()?.kind!="heading")item {NoteDocumentHeading(recordingCopy("课程概览","Overview"),"note_overview_heading")}
                    items(noteTextParagraphs(note.summary)){paragraph->NoteDocumentText(paragraph)}
                    if(note.keyPoints.isNotEmpty()){
                        item {NoteDocumentHeading(recordingCopy("知识点","Knowledge points"),"note_knowledge_heading")}
                        items(note.keyPoints.withIndex().toList()){point->NoteKnowledgePoint(point.index,point.value)}
                    }
                    if(note.actionItems.isNotEmpty()){
                        item {Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
                            NoteDocumentHeading(recordingCopy("作业与任务","Assignments & tasks"),"note_assignments_heading")
                            Text(recordingCopy("勾选仅保存在本机，不改动课堂原文。","Checkmarks stay on this device and do not change the original notes."),fontSize=11.sp,color=Muted)
                        }}
                        items(note.actionItems.withIndex().toList()){action->
                            val key=sha256Text("${action.index}:${action.value}")
                            NoteAssignment(action.index,action.value,key in checked,!demo){value->scope.launch {
                                try {withContext(Dispatchers.IO){localNotes.check(checklistOwner,note.id,key,value)}}
                                catch(_:Exception){notice=recordingCopy("清单未能保存，请检查本机存储空间。","Could not save the checklist. Check device storage.")}
                            }}
                        }
                    }
                }else if(tab==1)items(noteTextParagraphs(note.transcript)){paragraph->NoteDocumentText(paragraph)}
                if(note.summary.isNotBlank())item {RecordingInsightsSection(model,note,demo,onAccount,chat)}
            }else item {
                GlassSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=SurfaceColor){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Icon(Icons.Filled.Article,null,tint=Brand)
                    Text(recordingCopy("先记录，随时整理","Capture now, organize later"),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                    Text(recordingCopy("原始录音已保存在本机。转写后，这里会展示课程概要、详细知识点、作业和老师的提醒。","Your original audio is saved locally. After transcription, this page contains the overview, detailed concepts, assignments, and reminders."),fontSize=14.sp,lineHeight=23.sp,color=Muted)
                }}
            }
            item {Spacer(Modifier.height(24.dp))}
        }
        if(note.summary.isNotBlank())RecordingChatComposer(model,note,demo,chat,onAccount,
            modifier=Modifier.align(Alignment.BottomCenter).onSizeChanged {composerHeight=with(density){it.height.toDp()}}){
            tab=2;followChat=true;chatScrollRequest++
        }
    }
    }
    if(confirmSummaryCharge)GlassAlertDialog(onDismissRequest={confirmSummaryCharge=false},title={Text(recordingCopy("使用 AI token 重新总结？","Use AI tokens to regenerate?"))},
        text={Text(recordingCopy("此录音的两次免费重新总结已用完。本次将按模型实际输入与输出 token 扣除会员额度；失败不扣费。","Both free regenerations have been used. This attempt uses your membership allowance based on actual input and output tokens. Failed attempts are not charged."))},
        confirmButton={TextButton(onClick={confirmSummaryCharge=false;model.regenerateRecordingSummary(noteId,summaryLanguage,summaryRequirements,true)}){Text(recordingCopy("确认生成","Confirm"))}},
        dismissButton={TextButton(onClick={confirmSummaryCharge=false}){Text(recordingCopy("取消","Cancel"))}})
    if(chooseCourse&&!demo)CoursePickerDialog(model.savedData,linked?:suggestion,onDismiss={chooseCourse=false},onSelect={
        if(model.linkRecording(note.id,it)){chooseCourse=false;notice=recordingCopy("课程关联已更新，笔记内容保持不变。","Event link updated. Your notes are unchanged.")}
    })
    if(rename&&!demo)GlassAlertDialog(onDismissRequest={rename=false},title={Text(recordingCopy("重命名录音","Rename recording"))},text={OutlinedTextField(value=titleDraft,onValueChange={titleDraft=it.take(120)},singleLine=true,label={Text(recordingCopy("名称","Name"))})},
        confirmButton={TextButton(onClick={if(model.saveLessonNote(note.copy(title=titleDraft.trim()),recordingCopy("录音已重命名","Recording renamed")))rename=false},enabled=titleDraft.isNotBlank()){Text(recordingCopy("保存","Save"))}},
        dismissButton={TextButton(onClick={rename=false}){Text(recordingCopy("取消","Cancel"))}})
    if(removeAudio)GlassAlertDialog(onDismissRequest={removeAudio=false},title={Text(recordingCopy("移除本机音频？","Remove local audio?"))},
        text={Text(recordingCopy("文字笔记和课程关联会保留，音频无法从服务器恢复。需要备份时请先导出。","Your text notes and event link will stay. Audio cannot be recovered from the server; export a copy first if needed."))},
        confirmButton={TextButton(onClick={if(model.removeRecordingAudio(note.id))removeAudio=false}){Text(recordingCopy("移除音频","Remove audio"))}},
        dismissButton={TextButton(onClick={removeAudio=false}){Text(recordingCopy("取消","Cancel"))}})
}

internal fun suggestedRecordingCourse(data:AppData,recordedAt:ZonedDateTime):Occurrence? = data.courses.flatMap {course->course.dates(data.settings.termStart).map {Occurrence(course,it)}}
    .filter {it.endAt(recordedAt.zone)<=recordedAt.plusHours(3)&&it.startAt(recordedAt.zone)>=recordedAt.minusDays(14)}
    .sortedByDescending {it.endAt(recordedAt.zone)}.let {candidates->candidates.firstOrNull {it.endAt(recordedAt.zone)<=recordedAt}?:candidates.firstOrNull()}

/** Bound paragraph layout work without dropping or shortening any text. */
internal fun noteTextParagraphs(text:String):List<String> = text.split(Regex("\\n\\s*\\n")).flatMap {paragraph->
    if(paragraph.length<=6000)listOf(paragraph)else paragraph.chunked(6000)
}.filter {it.isNotBlank()}

@Composable private fun RecordingTextBlock(text:String){
    GlassSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=SurfaceColor){Text(text,Modifier.padding(18.dp),fontSize=15.sp,lineHeight=26.sp)}
}
@Composable private fun NoteSectionTitle(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector){
    Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        Icon(icon,null,Modifier.size(20.dp),tint=Brand);Text(title,fontSize=19.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable private fun NoteBullet(index:Int,text:String){
    GlassSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=SurfaceColor){
        Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Text(index.toString().padStart(2,'0'),fontSize=12.sp,color=Brand,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp))
            Text(text,Modifier.weight(1f),fontSize=15.sp,lineHeight=26.sp)
        }
    }
}

@Composable private fun LocalRecordingPlayer(file:File){
    var player by remember(file.path){mutableStateOf<MediaPlayer?>(null)}
    var playing by remember(file.path){mutableStateOf(false)}
    var preparing by remember(file.path){mutableStateOf(true)}
    var failed by remember(file.path){mutableStateOf(false)}
    var position by remember(file.path){mutableIntStateOf(0)}
    var duration by remember(file.path){mutableIntStateOf(1)}
    DisposableEffect(file.path){
        val value=MediaPlayer()
        try {value.setDataSource(file.path);value.setOnPreparedListener {duration=it.duration.coerceAtLeast(1);preparing=false;player=it}
            value.setOnCompletionListener {playing=false;position=0;it.seekTo(0)}
            value.setOnErrorListener {_,_,_->failed=true;preparing=false;playing=false;true};value.prepareAsync()
        }catch(_:Exception){failed=true;preparing=false}
        onDispose {runCatching {value.release()};player=null}
    }
    LaunchedEffect(playing){while(playing){position=runCatching {player?.currentPosition?:0}.getOrDefault(0);delay(250)}}
    GlassSurface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=SurfaceColor){
        Column(Modifier.padding(horizontal=16.dp,vertical=10.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                IconButton(onClick={runCatching {if(playing)player?.pause()else player?.start();playing=!playing}.onFailure {failed=true}},enabled=!preparing&&!failed){
                    Icon(if(playing)Icons.Filled.PauseCircle else Icons.Filled.PlayCircle,if(playing)recordingCopy("暂停","Pause")else recordingCopy("播放录音","Play audio"),Modifier.size(36.dp),tint=Brand)
                }
                Column(Modifier.weight(1f)){
                    Text(if(failed)recordingCopy("无法播放，可尝试导出音频","Cannot play here; try exporting the audio")else recordingCopy("原始录音","Original audio"),fontSize=13.sp,fontWeight=FontWeight.Medium)
                    Text("${recordingTimer(position/1000)} / ${recordingTimer(duration/1000)}",fontSize=12.sp,color=Muted)
                }
            }
            GlassSlider(value=position.toFloat().coerceIn(0f,duration.toFloat()),onValueChange={position=it.toInt();runCatching {player?.seekTo(position)}},valueRange=0f..duration.toFloat(),enabled=!preparing&&!failed)
        }
    }
}
