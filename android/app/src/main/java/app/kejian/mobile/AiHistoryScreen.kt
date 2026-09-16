package app.kejian.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun historyCopy(zh:String,en:String)=if(AppLanguage.english)en else zh
fun aiTaskStatusLabel(status:String)=when(status){
    "running"->historyCopy("处理中","Processing")
    "preview"->historyCopy("已生成预览","Preview ready")
    "completed"->historyCopy("已完成","Completed")
    "stopped"->historyCopy("已停止","Stopped")
    "interrupted"->historyCopy("已中断","Interrupted")
    "retry"->historyCopy("等待继续","Waiting to resume")
    else->historyCopy("未完成","Failed")
}
@Composable fun AiHistoryScreen(model:KejianViewModel,onBack:()->Unit){
    var clear by remember {mutableStateOf(false)}
    var selected by remember(model.cloudSession?.userId) {mutableStateOf<AiTaskRecord?>(null)}
    var snapshot by remember(model.cloudSession?.userId) {mutableStateOf<AppData?>(null)}
    BackHandler(selected!=null||snapshot!=null){if(snapshot!=null)snapshot=null else selected=null}
    val preview=snapshot
    if(preview!=null){
        TimetablePreviewScreen(ImportPreview(preview.courses,emptyList(),backup=preview,deadlines=preview.deadlines),AppData(settings=preview.settings),{snapshot=null},{},readOnly=true)
        return
    }
    Column(Modifier.fillMaxSize().testTag("ai_history_screen")){
        ScreenHeading(historyCopy("本机任务历史","Local task history"),{if(selected!=null)selected=null else onBack()}){
            if(selected==null)TextButton(onClick={clear=true},enabled=model.aiTaskHistory.isNotEmpty()&&!model.recognitionBusy){Text(historyCopy("清空","Clear"))}
        }
        Text(historyCopy("对话与任务仅保存在本机，不会随云存档上传。切换账号会切换对应历史。","Conversations and tasks stay on this device, outside cloud backups. History is separate for each account."),Modifier.padding(horizontal=20.dp,vertical=10.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
        val task=selected
        if(task!=null){
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                item{Text(task.title.let {if(task.kind=="image"&&it=="图片识别")historyCopy("图片识别","Image recognition")else if(it=="文件识别")historyCopy("文件识别","Document recognition")else it},style=MaterialTheme.typography.headlineSmall)}
                item{Text(aiTaskStatusLabel(task.status),color=Brand)}
                if(task.details.isNotBlank())item{Text(localized(task.details),color=Muted)}
                if(task.status=="retry"&&task.kind in setOf("image","command"))item{
                    GlassFilledTonalButton(onClick={model.resumeAiTask(task.id);selected=null},modifier=Modifier.fillMaxWidth().testTag("ai_task_resume")){
                        Text(historyCopy("继续原任务","Continue original task"))
                    }
                    Text(historyCopy("沿用原任务编号，不会自动创建新的付费请求。","Keeps the original request ID. No new paid request is created automatically."),style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                if(task.status=="preview")item{
                    GlassFilledTonalButton(onClick={model.restoreAiTaskPreview(task.id);onBack()},enabled=!model.recognitionBusy,modifier=Modifier.fillMaxWidth().testTag("ai_task_review")){
                        Text(historyCopy("继续核对并确认","Review and confirm"))
                    }
                }
                if(task.snapshot.isNotBlank())item{
                    GlassFilledTonalButton(onClick={snapshot=runCatching {DataJson.decode(task.snapshot)}.getOrNull()},modifier=Modifier.fillMaxWidth()){
                        Icon(Icons.Outlined.CalendarViewWeek,null);Spacer(Modifier.width(8.dp));Text(historyCopy("查看当时的课表结果","View saved schedule result"))
                    }
                    Text(historyCopy("仅查看，不会重新执行、扣费或覆盖当前课表。","Read only. This does not rerun the task, spend quota or overwrite your schedule."),Modifier.padding(top=8.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }else LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            if(model.aiTaskHistory.isEmpty())item{WhiteCard{Icon(Icons.Outlined.History,null,tint=Brand);Text(historyCopy("还没有任务记录","No tasks yet"),style=MaterialTheme.typography.titleMedium);Text(historyCopy("每次识图与日程指令的状态会出现在这里。","Image recognition and schedule commands will appear here."),color=Muted)}}
            items(model.aiTaskHistory,key={it.id}){record->
                GlassSurface(Modifier.fillMaxWidth().clickable {selected=record},color=SurfaceColor,shape=RoundedCornerShape(20.dp)){
                    Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){
                        Icon(when(record.kind){"image"->Icons.Outlined.Image;"document"->Icons.Outlined.InsertDriveFile;else->Icons.Outlined.AutoAwesome},null,tint=Brand)
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            Text(when(record.title){"图片识别"->historyCopy("图片识别","Image recognition");"文件识别"->historyCopy("文件识别","Document recognition");else->record.title},fontWeight=FontWeight.Medium,maxLines=2)
                            Text(DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(record.createdAt).atZone(ZoneId.systemDefault()))+" · "+aiTaskStatusLabel(record.status),style=MaterialTheme.typography.labelMedium,color=if(record.status=="failed")MaterialTheme.colorScheme.error else Muted)
                        }
                        Icon(Icons.Outlined.ChevronRight,null,tint=Muted)
                    }
                }
            }
        }
    }
    if(clear)GlassAlertDialog(onDismissRequest={clear=false},title={Text(historyCopy("清空本机 AI 历史？","Clear local AI history?"))},text={Text(historyCopy("删除当前账号在本机的对话和任务记录。不会删除课表、录音或其他账号的记录。","This removes this account's conversations and tasks on this device. Schedules, recordings and other accounts are kept."))},confirmButton={TextButton(onClick={model.clearAiHistory();clear=false}){Text(historyCopy("清空记录","Clear history"))}},dismissButton={TextButton(onClick={clear=false}){Text(historyCopy("取消","Cancel"))}})
}
