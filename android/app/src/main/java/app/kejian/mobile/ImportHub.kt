package app.kejian.mobile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable fun ImportHubScreen(data:AppData,onAi:()->Unit,onSchools:()->Unit,onLegacy:()->Unit,onPreview:(ImportPreview)->Unit){
    val en=AppLanguage.english;val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    var from by remember {mutableStateOf(data.settings.termStart.toString())}
    var until by remember {mutableStateOf(data.settings.termStart.plusWeeks(30).minusDays(1).toString())}
    val latest by rememberUpdatedState(data)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch {
        busy=true
        try {
            val start=LocalDate.parse(from);val end=LocalDate.parse(until)
            val preview=withContext(Dispatchers.Default){
                val bytes=context.contentResolver.openInputStream(uri)?.use {input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=4_000_000){"文件超过 4 MB"};out.write(buffer,0,n)};out.toByteArray()}?:error("无法读取文件")
                require(bytes.size<=4_000_000){"文件超过 4 MB，请分开导出"}
                IcsImport.withoutDuplicates(IcsImport.parse(bytes.toString(Charsets.UTF_8),start,end),latest)
            }
            if(preview.courses.isEmpty()&&preview.deadlines.isEmpty()&&preview.errors.isEmpty())error=if(en)"No new events in this date range. Check dates or duplicates." else "所选日期内没有新项目，请检查日期范围，或确认是否已经导入。"
            else onPreview(preview)
        }catch(e:Exception){error=e.message?:"导入失败"}finally{busy=false}
    }}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(top=LocalPageTopInset.current+20.dp,bottom=LocalDockInset.current+20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Text(if(en)"Import" else "导入",style=AppTextStyles.pageTitle)
        Text(if(en)"Bring your schedule together. Preview before applying." else "把安排带进课间，核对之后再应用。",color=Muted,style=AppTextStyles.pageSubtitle)
        WhiteCard {
            Text(if(en)"AI import & assistant" else "AI 识图与日程助手",style=MaterialTheme.typography.titleLarge)
            Text(if(en)"Images, spreadsheets, PDFs or a simple request. Your chat and task history stay here." else "课表图片、表格、PDF，或用一句话安排日程。原有对话和任务记录仍保留。",color=Muted)
            PrimaryButton(if(en)"Open AI assistant" else "打开 AI 助手",onAi)
        }
        WhiteCard {
            Text(if(en)"University timetable" else "教务系统导入",style=MaterialTheme.typography.titleLarge)
            Text(if(en)"Find your university, sign in on its own site and review the timetable before importing." else "搜索学校，在学校网站内登录，读取课表后生成预览。",color=Muted)
            PrimaryButton(if(en)"Find my university" else "搜索我的学校",onSchools)
        }
        WhiteCard {
            Text(if(en)"ICS calendar" else "ICS 日历文件",style=MaterialTheme.typography.titleLarge)
            Text(if(en)"Local import with recurring events and timezone conversion. Select the date range to expand, up to one year." else "本机解析循环课程与时区，导入前预览。选择需要展开的日期范围，最多一年。",color=Muted)
            OutlinedTextField(from,{from=it},label={Text(if(en)"From · YYYY-MM-DD" else "开始日期 · YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(until,{until=it},label={Text(if(en)"Through · YYYY-MM-DD" else "结束日期 · YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text(if(en)"Times use ${java.time.ZoneId.systemDefault().id}. All-day events become date reminders; overnight events are split by day." else "时间按 ${java.time.ZoneId.systemDefault().id} 显示。全天事项转为日期提醒，跨天课程按天拆分，重复导入自动去重。",style=MaterialTheme.typography.bodySmall,color=Muted)
            PrimaryButton(if(busy){if(en)"Reading…" else "正在解析…"}else{if(en)"Choose .ics file" else "选择 .ics 文件"},{picker.launch(arrayOf("text/calendar","application/ics","text/plain","application/octet-stream"))},enabled=!busy)
        }
        TextButton(onLegacy){Text(if(en)"Restore backup / paste a table" else "恢复备份 / 粘贴课表")}
    }
    error?.let {message->GlassAlertDialog(onDismissRequest={error=null},title={Text(if(en)"Import notice" else "导入提示")},text={Text(message)},confirmButton={TextButton({error=null}){Text(if(en)"OK" else "知道了")}})}
}
