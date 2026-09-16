package app.kejian.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DeadlineEditor(original:Deadline,existing:Boolean,courses:List<Course>,onBack:()->Unit,onDelete:()->Unit,onSave:(Deadline)->Unit){
    var title by rememberSaveable(original.id){mutableStateOf(original.title)}
    var date by rememberSaveable(original.id){mutableStateOf(original.dueDate.toString())}
    var minute by rememberSaveable(original.id){mutableStateOf(original.dueMinute)}
    var details by rememberSaveable(original.id){mutableStateOf(original.details)}
    var courseId by rememberSaveable(original.id){mutableStateOf(original.courseId)}
    var color by rememberSaveable(original.id){mutableIntStateOf(original.color)}
    var reminder by rememberSaveable(original.id){mutableStateOf(original.reminder)}
    var datePicker by remember {mutableStateOf(false)};var timePicker by remember {mutableStateOf(false)};var deleting by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun value():Deadline?{
        val parsed=runCatching {LocalDate.parse(date)}.getOrNull()
        if(parsed==null){error="请选择有效日期";return null}
        val result=original.copy(title=title.trim(),dueDate=parsed,dueMinute=minute,details=details.trim(),courseId=courseId,color=color,reminder=reminder)
        error=result.error();return result.takeIf {error==null}
    }
    Column(Modifier.fillMaxSize().imePadding()){
        ScreenHeading(if(existing)"编辑截止日" else "新建截止日",onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("截止日按日期标记整列，不会挤进凌晨的时间轴。",color=Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(title,{title=it.take(80)},label={Text("截止日名称")},placeholder={Text("例如：线性代数作业")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            GlassOutlinedButton(onClick={datePicker=true},modifier=Modifier.fillMaxWidth().height(62.dp),shape=RoundedCornerShape(16.dp)){
                Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text("截止日期",style=MaterialTheme.typography.labelSmall,color=Muted);Text(date,style=MaterialTheme.typography.titleMedium)}
            }
            SingleTimeValueField(minute?.let(::timeText)?:"全天（不显示在时间轴）","截止时间 · 选填",Modifier.fillMaxWidth()){timePicker=true}
            OutlinedTextField(details,{details=it.take(500)},label={Text("详细说明 · 选填")},minLines=3,maxLines=6,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            Text("关联课程 · 可不关联",color=Muted,style=MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
                FilterChip(selected=courseId==null,onClick={courseId=null},label={Text("独立截止日")})
                courses.distinctBy {it.name}.take(12).forEach {course->FilterChip(selected=courseId==course.id,onClick={courseId=course.id;color=course.color},label={Text(course.name.take(12))})}
            }
            Text("标记颜色",color=Muted,style=MaterialTheme.typography.labelLarge)
            FlowRow(maxItemsInEachRow=6,horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){CourseColors.forEachIndexed {index,value->Box(Modifier.size(48.dp).clickable {color=index},contentAlignment=Alignment.Center){Box(Modifier.size(38.dp).background(value,CircleShape).border(if(index==color)3.dp else 1.dp,if(index==color)Brand else MaterialTheme.colorScheme.outline.copy(alpha=.45f),CircleShape),contentAlignment=Alignment.Center){if(index==color)Text("✓",color=Color(LocalAppPalette.current.courseInk[index]))}}}}
            Row(Modifier.fillMaxWidth().background(Mint,RoundedCornerShape(16.dp)).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("截止提醒");Text("按截止时间提醒；未填写时间时在当天 09:00 提醒",style=MaterialTheme.typography.bodySmall,color=Muted)};GlassSwitch(reminder,{reminder=it})}
            if(existing)TextButton(onClick={deleting=true}){Text("删除截止日",color=MaterialTheme.colorScheme.error)}
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }
        Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal=24.dp,vertical=12.dp)){PrimaryButton(if(existing)"保存截止日" else "创建截止日",{value()?.let(onSave)})}
    }
    if(datePicker){
        val initial=runCatching {LocalDate.parse(date)}.getOrDefault(original.dueDate)
        val state=rememberDatePickerState(initialSelectedDateMillis=initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={datePicker=false},confirmButton={TextButton(onClick={state.selectedDateMillis?.let {date=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};datePicker=false}){Text("确定")}},dismissButton={TextButton(onClick={datePicker=false}){Text("取消")}}){DatePicker(state)}
    }
    if(timePicker)SingleTimeWheelSheet(minute?:0,"截止时间",allowClear=true,onDismiss={timePicker=false},onClear={minute=null;timePicker=false}){minute=it;timePicker=false}
    if(deleting)GlassAlertDialog(onDismissRequest={deleting=false},title={Text("删除这个截止日？")},text={Text("${original.title}\n${original.dueDate}")},confirmButton={TextButton(onClick={deleting=false;onDelete()}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}
