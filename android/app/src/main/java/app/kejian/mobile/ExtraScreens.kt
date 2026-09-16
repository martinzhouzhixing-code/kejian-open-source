package app.kejian.mobile

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import java.time.*
import kotlinx.coroutines.delay

private data class TableDraft(val source:Course,val name:String=source.name,val day:String=source.day.toString(),val start:String=timeText(source.start),val end:String=timeText(source.end),val address:String=source.address,val room:String=source.room) {
    fun course():Course? {
        val d=day.toIntOrNull()?:return null;val s=parseTime(start)?:return null;val e=parseTime(end)?:return null
        val shift=(d-source.day).toLong()
        return source.copy(name=name.trim(),day=d,start=s,end=e,address=address.trim(),room=room.trim(),date=source.date?.plusDays(shift),excluded=source.excluded.map { it.plusDays(shift) }.toSet()).takeIf { it.error()==null }
    }
}

@Composable fun BatchScreen(courses:List<Course>,onBack:()->Unit,onImport:()->Unit,onPaste:(String)->Unit,onSave:(List<Course>)->Unit,onMessage:(String)->Unit){
    var rows by remember { mutableStateOf(courses.map { TableDraft(it) }) }
    var past by remember {mutableStateOf(emptyList<List<TableDraft>>())}
    var future by remember {mutableStateOf(emptyList<List<TableDraft>>())}
    var lastField by remember {mutableStateOf<String?>(null)}
    var lastEdit by remember {mutableLongStateOf(0L)}
    fun setRows(value:List<TableDraft>,field:String?=null){
        if(value==rows)return
        val now=android.os.SystemClock.uptimeMillis()
        if(field==null||field!=lastField||now-lastEdit>700){past=(past+listOf(rows)).takeLast(50)}
        rows=value;future=emptyList();lastField=field;lastEdit=now
    }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var bulk by remember { mutableStateOf(false) };var bulkStart by remember { mutableStateOf("") };var bulkDuration by remember { mutableStateOf("") };var bulkAddress by remember { mutableStateOf("") }
    var bulkTimeWheel by remember { mutableStateOf(false) };var rowTimeWheel by remember { mutableStateOf<String?>(null) }
    var bulkError by remember { mutableStateOf<String?>(null) };var error by remember { mutableStateOf<String?>(null) }
    val clipboard=LocalContext.current.getSystemService(ClipboardManager::class.java)
    fun validated():List<Course>? { val invalid=rows.indexOfFirst { it.course()==null };if(invalid>=0){error="第 ${invalid+1} 行无效：请检查名称、星期（1–7）和起止时间";return null};return rows.map { it.course()!! } }
    Column(Modifier.fillMaxSize().imePadding()){
        ScreenHeading("表格编辑",onBack){TextButton(onClick=onImport){Text("导入")}}
        Row(Modifier.padding(horizontal=8.dp)){
            TextButton(enabled=past.isNotEmpty(),onClick={future=future+listOf(rows);rows=past.last();past=past.dropLast(1);lastField=null}){Text("↶ 撤销")}
            TextButton(enabled=future.isNotEmpty(),onClick={past=past+listOf(rows);rows=future.last();future=future.dropLast(1);lastField=null}){Text("↷ 重做")}
        }
        Text("横向滑动编辑各列；保存后应用到所有周次。",Modifier.padding(horizontal=20.dp),color=Muted,style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=8.dp)){
            TextButton(onClick={selected=if(selected.size==rows.size)emptySet()else rows.map { it.source.id }.toSet()}){Text(if(selected.size==rows.size&&rows.isNotEmpty())"取消全选" else "全选")}
            TextButton(enabled=selected.isNotEmpty(),onClick={bulk=true}){Text("统一编辑 (${selected.size})")}
            TextButton(enabled=selected.isNotEmpty(),onClick={
                val removed=selected.size
                setRows(rows.filterNot {it.source.id in selected})
                selected=emptySet();onMessage("已删除 $removed 行，可用撤销恢复")
            }){Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(17.dp));Spacer(Modifier.width(4.dp));Text("删除所选")}
            TextButton(onClick={validated()?.let { all->val copied=if(selected.isEmpty())all else all.filter { it.id in selected };clipboard.setPrimaryClip(ClipData.newPlainText("课间 KJ1",CourseExchange.encode(copied)));onMessage("已复制 ${copied.size} 条严格 KJ1 课程记录") }}){Text("复制")}
            TextButton(onClick={val text=clipboard.primaryClip?.getItemAt(0)?.text?.toString();if(text.isNullOrBlank())onMessage("剪贴板没有表格文本")else onPaste(text)}){Text("粘贴导入")}
        }
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())){
            LazyColumn(Modifier.width(1016.dp).padding(horizontal=12.dp)){
                item { Row(Modifier.background(Mint).padding(vertical=8.dp)){listOf("选择" to 52,"课程" to 160,"星期" to 76,"开始" to 106,"结束" to 106,"课堂地址" to 260,"教室" to 128).forEach { (label,w)->Text(label,Modifier.width(w.dp).padding(horizontal=8.dp),color=Brand) }} }
                itemsIndexed(rows,key={_,r->r.source.id}){index,row->
                    fun update(value:TableDraft,field:String){setRows(rows.toMutableList().also {it[index]=value},row.source.id+field);error=null}
                    Row(Modifier.background(if(index%2==0)SurfaceColor else Bg).padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
                        Checkbox(row.source.id in selected,{checked->selected=if(checked)selected+row.source.id else selected-row.source.id},Modifier.width(52.dp))
                        @Composable fun cell(value:String,w:Int,clearable:Boolean=false,change:(String)->Unit){OutlinedTextField(
                            value=value,onValueChange=change,modifier=Modifier.width(w.dp).padding(end=4.dp),singleLine=true,
                            textStyle=MaterialTheme.typography.bodyMedium,
                            trailingIcon=if(clearable&&value.isNotEmpty())({IconButton(onClick={change("")}){Icon(Icons.Outlined.Clear,"清空文字",Modifier.size(18.dp))}})else null
                        )}
                        cell(row.name,160,true){update(row.copy(name=it.take(80)),"name")};cell(row.day,76){update(row.copy(day=it.take(1)),"day")}
                        SingleTimeValueField(row.start,"开始",Modifier.width(106.dp)){rowTimeWheel=row.source.id}
                        SingleTimeValueField(row.end,"结束",Modifier.width(106.dp)){rowTimeWheel=row.source.id}
                        cell(row.address,260){update(row.copy(address=it.take(300)),"address")};cell(row.room,128){update(row.copy(room=it.take(80)),"room")}
                    }
                }
                item { TextButton(onClick={setRows(rows+TableDraft(Course()))}){Text("＋ 添加一行")};Spacer(Modifier.height(16.dp)) }
            }
        }
        error?.let { Text(it,Modifier.padding(horizontal=20.dp),color=MaterialTheme.colorScheme.error) }
        Box(Modifier.padding(20.dp)){PrimaryButton("保存 ${rows.size} 行",{validated()?.let(onSave)})}
    }
    if(bulk)GlassAlertDialog(onDismissRequest={bulk=false},title={Text("统一编辑 ${selected.size} 节课")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("留空的字段保持原样；修改开始时间时保留原时长。",style=MaterialTheme.typography.bodySmall)
        SingleTimeValueField(bulkStart,"开始时间",Modifier.fillMaxWidth()){bulkTimeWheel=true}
        OutlinedTextField(bulkDuration,{bulkDuration=it},label={Text("时长 · 分钟")},singleLine=true)
        OutlinedTextField(bulkAddress,{bulkAddress=it.take(300)},label={Text("课堂地址")},maxLines=3)
        bulkError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }},confirmButton={TextButton(onClick={
        val s=if(bulkStart.isBlank())null else parseTime(bulkStart);val d=if(bulkDuration.isBlank())null else bulkDuration.toIntOrNull()
        if(bulkStart.isNotBlank()&&s==null||bulkDuration.isNotBlank()&&(d==null||d !in 1..1440)){bulkError="请填写有效的时间和正整数时长";return@TextButton}
        val changed=rows.map { r->if(r.source.id !in selected)r else {val original=r.course();if(original==null)r.copy(start="无效")else{val begin=s?:original.start;val length=d?:original.duration;r.copy(start=timeText(begin),end=timeText(begin+length),address=bulkAddress.ifBlank { r.address })}} }
        if(changed.any { it.course()==null })bulkError="存在无效行，或结束时间超过 24:00" else {setRows(changed);bulk=false;bulkError=null}
    }){Text("应用到所选")}},dismissButton={TextButton(onClick={bulk=false}){Text("取消")}})
    if(bulkTimeWheel)SingleTimeWheelSheet(parseTime(bulkStart)?:8*60,"统一开始时间",allowClear=true,onDismiss={bulkTimeWheel=false},onClear={bulkStart="";bulkTimeWheel=false}){bulkStart=timeText(it);bulkError=null;bulkTimeWheel=false}
    rowTimeWheel?.let { id->rows.firstOrNull {it.source.id==id}?.let { row->
        TimeRangeWheelSheet(parseTime(row.start)?:row.source.start,parseTime(row.end)?:row.source.end,{rowTimeWheel=null}){s,e->
            val index=rows.indexOfFirst {it.source.id==id};if(index>=0)setRows(rows.toMutableList().also {it[index]=row.copy(start=timeText(s),end=timeText(e))},id+"time")
            error=null;rowTimeWheel=null
        }
    }?:run {rowTimeWheel=null}}
}

@Composable fun ImportScreen(onBack:()->Unit,onChoose:()->Unit,onPaste:(String)->Unit){
    var text by rememberSaveable { mutableStateOf("") };val clipboard=LocalContext.current.getSystemService(ClipboardManager::class.java)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()){
        ScreenHeading("导入课表",onBack)
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            WhiteCard { Text("从文件或 Excel 开始",style=MaterialTheme.typography.titleLarge);Text("无需联网。支持 UTF-8 / UTF-16 的 CSV、TSV，以及课间 JSON 完整备份。",color=Muted);PrimaryButton("选择文件",onChoose) }
            Text("也可以粘贴表格",style=MaterialTheme.typography.titleMedium)
            Text("列顺序：课程、星期、开始、结束、课堂地址、教室、颜色（0–11）、周次、教师、提醒。前四列必填；也支持严格 KJ1 行。",color=Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(text,{text=it.take(1_000_001)},label={Text("CSV / TSV 文本")},placeholder={Text("高等数学\t1\t08:10\t09:40\t东校区博学楼\tA201")},minLines=6,maxLines=10,modifier=Modifier.fillMaxWidth())
            Row { TextButton(onClick={text=clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().take(1_000_001)}){Text("读取剪贴板")};TextButton(onClick={text="课程,星期,开始,结束,课堂地址,教室,颜色,周次,教师,提醒\n高等数学,周一,08:10,09:40,东校区博学楼,A201,0,1-16,陈老师,是"}){Text("填入示例")}}
            PrimaryButton("识别并预览",{onPaste(text)},enabled=text.isNotBlank())
            Text("导入前会展示预览，不会直接写入。CSV / TSV 追加课程；JSON 恢复会替换全部数据。单次调整请通过 JSON 备份。",color=Muted,style=MaterialTheme.typography.bodySmall)
            WhiteCard { Text("图片与 Office 文件",style=MaterialTheme.typography.titleMedium);Text("请使用底部“识别”专区。云端 AI 接入后会统一处理图片、PDF 与常见 Office 文件；当前文本和课间备份仍可在这里导入。",color=Muted) }
        }
    }
}

@Composable fun PreviewScreen(preview:ImportPreview,data:AppData,onBack:()->Unit,onConfirm:()->Unit){
    TimetablePreviewScreen(preview,data,onBack,onConfirm)
}
