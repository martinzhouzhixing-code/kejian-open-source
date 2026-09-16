package app.kejian.mobile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Stable internal class RecordingChatState(val owner:String){
    var turns by mutableStateOf<List<NoteInsightTurn>>(emptyList())
    var question by mutableStateOf("")
    var readError by mutableStateOf(false)
    var loaded by mutableStateOf(false)
}
@Composable internal fun rememberRecordingChat(model:KejianViewModel,note:LessonNote,demo:Boolean):RecordingChatState{
    val context=LocalContext.current
    val owner=model.cloudSession?.userId?:"local"
    val chat=remember(owner,note.id){RecordingChatState(owner)}
    val store=remember(context){RecordingInsightsStore(context)}
    DisposableEffect(store){onDispose{store.close()}}
    val revision=RecordingInsightsStore.revision
    LaunchedEffect(owner,note.id,noteContentDigest(note),revision,demo){
        if(!demo)try {
            chat.turns=withContext(Dispatchers.IO){store.turns(owner,note,allVersions=true)}
            chat.readError=false
            if(chat.turns.lastOrNull()?.question==chat.question)chat.question=""
        }catch(c:kotlinx.coroutines.CancellationException){throw c}
        catch(_:Exception){chat.readError=true}
        finally{chat.loaded=true}
    }
    return chat
}

@Composable internal fun RecordingInsightsSection(model:KejianViewModel,note:LessonNote,demo:Boolean,onAccount:()->Unit,chat:RecordingChatState){
    val owner=chat.owner
    val turns=chat.turns
    val digest=noteContentDigest(note)
    val busy=model.insightBusyScope=="$owner:${note.id}:$digest"
    val readError=chat.readError
    Column(Modifier.fillMaxWidth().testTag("recording_insights"),verticalArrangement=Arrangement.spacedBy(14.dp)){
        HorizontalDivider(Modifier.padding(top=8.dp,bottom=6.dp),color=Muted.copy(alpha=.2f))
        Row(Modifier.onboardingTarget("record.detail.insights").testTag("insights_heading"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Icon(Icons.Filled.AutoAwesome,null,Modifier.size(21.dp),tint=Brand)
            Text(recordingCopy("向笔记提问","Ask notes"),fontSize=21.sp,fontWeight=FontWeight.SemiBold)
        }
        Text(recordingCopy("查阅笔记、逐字稿，必要时联网检索通用知识并标明来源。使用会员 AI token，不扣录音时长。","Uses notes, the transcript, and public references when helpful, with citations. Uses membership AI tokens, not recording minutes."),fontSize=13.sp,lineHeight=21.sp,color=Muted)
        if(!demo&&model.cloudSession?.profile?.aiAssistant!=true)TextButton(onClick=onAccount){Text(recordingCopy("查看账号与 AI 额度","View account & AI allowance"))}
        if(demo){
            val beat=LocalOnboardingTargets.current?.beat?:0
            if(beat>=1)NoteChatBubble(true){Text(recordingCopy("老师提到了哪些作业要求？","What assignment requirements did the teacher mention?"),color=OnBrand,fontSize=14.sp)}
            val quote=note.actionItems.firstOrNull()?.take(600)
            if(beat in 2..3)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(beat>=4)NoteChatBubble(false){Text(quote?.plus(" [a0]")?:recordingCopy("这份示例没有提供作业要求，无法从原文确认。","No assignment requirements were provided in this example, so they cannot be confirmed from the source."),fontSize=15.sp,lineHeight=25.sp);if(quote!=null)InsightSource(NoteCitation("a0","actionItem",0,quote))}
        }else turns.forEach {turn->
            Column(Modifier.fillMaxWidth().testTag("insight_turn_${turn.id}"),verticalArrangement=Arrangement.spacedBy(11.dp)){
                NoteChatBubble(true,Modifier.testTag("note-question-${turn.id}")){Text(turn.question,color=OnBrand,fontSize=14.sp,lineHeight=23.sp)}
                NoteChatBubble(false,Modifier.testTag("note-answer-${turn.id}")){
                if(turn.digest!=digest)Text(recordingCopy("较早版本笔记的对话","Conversation about an earlier note version"),fontSize=11.sp,color=Muted)
                turn.answer?.let {answer->
                    NoteDocumentText(answer.text)
                    if(answer.insufficientEvidence)Text(recordingCopy("资料不足的部分不会补写为事实。","Unsupported details are not presented as facts."),fontSize=12.sp,color=Muted)
                    answer.citations.forEach {citation->InsightSource(citation)}
                    Text(recordingCopy("本次使用 ${answer.inputTokens+answer.outputTokens} token · 再次阅读免费","${answer.inputTokens+answer.outputTokens} tokens used · reading again is free"),fontSize=11.sp,color=Muted)
                }?:if(busy&&model.insightBusyTurnId==turn.id){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
                    CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text(recordingCopy("正在后台对照原文整理回答，可离开此页面…","Preparing an answer in the background. You may leave this page…"),fontSize=13.sp,color=Muted)
                }}else {
                    val freshRequest=insightNeedsFreshRequest(turn)
                    Text(if(insightRetryWindowExpired(turn))recordingCopy("安全重试窗口已结束，再次提问是新请求，会使用 token。","The safe retry window has ended. Asking again is a new request and uses tokens.")else if(turn.errorCode=="INSIGHTS_EXPIRED")recordingCopy("服务器回答缓存已过期，再次提问会使用 token。","The server answer cache expired. Asking again uses tokens.")else recordingCopy("暂未取得回答，问题已保存在本机。","The answer is not available yet. Your question is saved locally."),fontSize=12.sp,lineHeight=20.sp,color=Muted)
                    TextButton(onClick={if(model.cloudSession!=null)model.askRecordingInsight(note.id,turn.question,turn.id,confirmFreshRequest=freshRequest)else onAccount()},enabled=model.insightBusyScope==null&&turn.digest==digest){
                        Icon(Icons.Filled.Refresh,null,Modifier.size(16.dp));Spacer(Modifier.width(6.dp))
                        Text(if(freshRequest)recordingCopy("重新提问 · 使用 token","Ask again · uses tokens")else recordingCopy("重试同一请求","Retry the same request"))
                    }
                }
            }
        }
        }
        if(!demo)model.recordingInsightMessage(owner,note)?.let {Text(it,fontSize=12.sp,lineHeight=20.sp,color=MaterialTheme.colorScheme.error)}
        if(readError)Text(recordingCopy("暂时无法读取本机问答历史，请检查存储空间。","Could not read local question history. Check device storage."),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
        if(turns.isEmpty()){
            listOf(recordingCopy("梳理这节课的核心知识点","Explain the main ideas in this lesson"),recordingCopy("有哪些作业、截止时间和提醒？","What assignments, deadlines, and reminders were mentioned?")).forEach {suggestion->
                GlassOutlinedButton(onClick={chat.question=suggestion},enabled=!demo&&!busy,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp)){
                    Text(suggestion,Modifier.fillMaxWidth(),fontSize=13.sp,lineHeight=20.sp)
                }
            }
        }
        Text(recordingCopy("问答历史保存在本机并按账号隔离。服务端为重试保留回答与引用 7 天、任务标记 30 天；不发送其他录音。AI 可能出错，请核对引用。","History stays on this device, separated by account. The server caches answers and citations for 7 days and request markers for 30 days for safe retries. Other recordings are not sent. AI can make mistakes; check the sources."),fontSize=11.sp,lineHeight=18.sp,color=Muted)
    }
}

/** Each saved turn keeps its question and grounded answer in separate, aligned bubbles. */
@Composable internal fun NoteChatBubble(fromUser:Boolean,modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    val shape=RoundedCornerShape(topStart=20.dp,topEnd=20.dp,bottomStart=if(fromUser)20.dp else 6.dp,bottomEnd=if(fromUser)6.dp else 20.dp)
    Box(Modifier.fillMaxWidth()){
        GlassSurface(modifier.align(if(fromUser)Alignment.CenterEnd else Alignment.CenterStart).fillMaxWidth(if(fromUser).88f else .96f),
            shape=shape,color=if(fromUser)Brand else SurfaceColor,contentColor=if(fromUser)OnBrand else Ink){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(!fromUser)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    Icon(Icons.Filled.AutoAwesome,null,Modifier.size(16.dp),tint=Brand)
                    Text(recordingCopy("课间 AI","Kejian AI"),fontSize=12.sp,color=Brand,fontWeight=FontWeight.Medium)
                }
                content()
            }
        }
    }
}

@Composable private fun InsightSource(citation:NoteCitation){
    var expanded by remember(citation){mutableStateOf(false)}
    val source=when(citation.source){"web"->recordingCopy("联网补充","Online reference");"summary"->recordingCopy("笔记","Notes");"transcript"->recordingCopy("逐字稿","Transcript");"keyPoint"->recordingCopy("知识点 ${citation.index+1}","Knowledge point ${citation.index+1}");else->recordingCopy("作业 ${citation.index+1}","Action ${citation.index+1}")}
    Column(Modifier.fillMaxWidth().animateContentSize()){
        TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)){
            Icon(Icons.Filled.FormatQuote,null,Modifier.size(17.dp));Spacer(Modifier.width(5.dp));Text("[${citation.id}] $source",fontSize=12.sp)
            Icon(if(expanded)Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,null,Modifier.size(18.dp))
        }
        if(citation.source=="web"&&citation.url!=null)NoteSourceLink(citation.url)
        if(expanded)Text(citation.quote,Modifier.padding(start=12.dp,end=8.dp,bottom=10.dp).testTag("insight_source_${citation.id}"),fontSize=13.sp,lineHeight=22.sp,color=Muted)
    }
}

@Composable internal fun RecordingChatComposer(model:KejianViewModel,note:LessonNote,demo:Boolean,chat:RecordingChatState,onAccount:()->Unit,modifier:Modifier=Modifier,onSent:()->Unit){
    val busy=model.insightBusyScope!=null
    val unresolved=chat.turns.lastOrNull {it.digest==noteContentDigest(note)&&it.status in setOf("pending","retry")}
    val readError=chat.readError||!chat.loaded
    val shape=RoundedCornerShape(22.dp)
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp,vertical=6.dp).testTag("note-chat-composer")){
        Box(Modifier.matchParentSize().liveGlass(shape,SurfaceColor,LocalGlassLayers.current.timetable,radius=12.dp,tintAlpha=if(LocalAppPalette.current.dark).44f else .36f).glassHighlight(shape)
            .then(if(LocalGlassEnabled.current)Modifier else Modifier.background(SurfaceColor,shape)))
        Column(Modifier.padding(horizontal=12.dp,vertical=8.dp)){
        OutlinedTextField(value=chat.question,onValueChange={chat.question=it.take(2000)},enabled=!demo&&!readError,
            placeholder={Text(recordingCopy("对此笔记提问…","Ask about this note…"))},
            colors=OutlinedTextFieldDefaults.colors(
                focusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedContainerColor=androidx.compose.ui.graphics.Color.Transparent,
                disabledContainerColor=androidx.compose.ui.graphics.Color.Transparent,
                focusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,unfocusedBorderColor=androidx.compose.ui.graphics.Color.Transparent,
                disabledBorderColor=androidx.compose.ui.graphics.Color.Transparent),
            modifier=Modifier.fillMaxWidth().testTag("insights_question"),shape=RoundedCornerShape(16.dp),minLines=1,maxLines=4,
            trailingIcon={IconButton(onClick={
                if(model.cloudSession?.profile?.aiAssistant==true){model.askRecordingInsight(note.id,chat.question);keyboard?.hide();onSent()}else onAccount()
            },enabled=!demo&&!busy&&unresolved==null&&!readError&&chat.question.isNotBlank(),modifier=Modifier.testTag("insights_send")){
                if(busy)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                else Icon(Icons.Filled.ArrowUpward,recordingCopy("发送问题","Send question"),tint=if(chat.question.isNotBlank())Brand else Muted)
            }})
        Text(recordingCopy("只查阅本笔记 · 问答自动保存在本机","This note only · conversation saved on this device"),Modifier.padding(top=4.dp),fontSize=11.sp,color=Muted)

        }
    }
}
