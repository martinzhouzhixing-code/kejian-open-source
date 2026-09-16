package app.kejian.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable fun CourseEditor(original:Course,existing:Boolean,onBack:()->Unit,onCopy:(Course)->Unit,onDelete:()->Unit,onSave:(Course)->Unit){
    var name by rememberSaveable(original.id){mutableStateOf(original.name)}
    var address by rememberSaveable(original.id){mutableStateOf(original.address)}
    var room by rememberSaveable(original.id){mutableStateOf(original.room)}
    var teacher by rememberSaveable(original.id){mutableStateOf(original.teacher)}
    var day by rememberSaveable(original.id){mutableIntStateOf(original.day)}
    var start by rememberSaveable(original.id){mutableStateOf(timeText(original.start))}
    var end by rememberSaveable(original.id){mutableStateOf(timeText(original.end))}
    var duration by rememberSaveable(original.id){mutableStateOf(original.duration.toString())}
    var color by rememberSaveable(original.id){mutableIntStateOf(original.color)}
    var weeks by rememberSaveable(original.id){mutableStateOf(original.weeks.sorted().let { if(it.isNotEmpty()&&it== (it.first()..it.last()).toList())"${it.first()}-${it.last()}" else it.joinToString(",") })}
    var reminder by rememberSaveable(original.id){mutableStateOf(original.reminder)}
    var timeWheel by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun course():Course? {
        val s=parseTime(start);val e=parseTime(end);val w=CourseTable.parseWeeks(weeks)
        if(s==null||e==null||w==null){error="请检查时间（如 08:10）和周次（如 1-16）";return null}
        if(duration.toIntOrNull()!=e-s||e<=s){error="请填写有效时长，并确保与起止时间一致";return null}
        val c=original.copy(name=name.trim(),address=address.trim(),room=room.trim(),teacher=teacher.trim(),day=day,start=s,end=e,color=color,weeks=w,reminder=reminder)
        error=c.error();return if(error==null)c else null
    }
    Column(Modifier.fillMaxSize().imePadding()){
        ScreenHeading(if(existing)"编辑课程" else "新建课程",onBack){if(existing)TextButton(onClick={course()?.let(onCopy)}){Text("复制")}}
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            Text("把时间留给重要的事。",color=Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(name,{name=it.take(80)},label={Text("课程名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            OutlinedTextField(address,{address=it.take(300)},label={Text("课堂地址")},placeholder={Text("例如：东校区 · 博学楼 / 具体街道地址")},minLines=2,maxLines=3,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),supportingText={Text("可填写校区、楼栋或具体地址；会显示在课前提醒中")})
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                OutlinedTextField(room,{room=it.take(80)},label={Text("教室 · 选填")},singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp))
                OutlinedTextField(teacher,{teacher=it.take(80)},label={Text("老师 · 选填")},singleLine=true,modifier=Modifier.weight(1f),shape=RoundedCornerShape(16.dp))
            }
            Text("课程颜色",color=Muted,style=MaterialTheme.typography.labelLarge)
            FlowRow(Modifier.fillMaxWidth(),maxItemsInEachRow=6,horizontalArrangement=Arrangement.spacedBy(4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){CourseColors.forEachIndexed { i,c->Box(Modifier.size(48.dp).clickable { color=i },contentAlignment=Alignment.Center){Box(Modifier.size(38.dp).background(c,CircleShape).border(if(i==color)3.dp else 1.dp,if(i==color)Brand else MaterialTheme.colorScheme.outline.copy(alpha=.45f),CircleShape),contentAlignment=Alignment.Center){if(i==color)Text("✓",color=Color(LocalAppPalette.current.courseInk[i]))}}}}
            WhiteCard {
                TimeRangeValueField(parseTime(start)?:original.start,parseTime(end)?:original.end,Modifier.fillMaxWidth()){timeWheel=true}
                OutlinedTextField(duration,{value->duration=value.take(4);val d=value.toIntOrNull();val s=parseTime(start);if(d!=null&&d>0&&s!=null&&s+d<=1440)end=timeText(s+d)},label={Text("时长（分钟）")},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Text("上下滚动可精确到任意分钟；只有拖动模块时按半小时吸附。",color=Muted,style=MaterialTheme.typography.bodySmall)
            }
            Text("上课星期",color=Muted,style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp)){(1..7).forEach { d->Box(Modifier.size(48.dp).clickable { day=d },contentAlignment=Alignment.Center){Box(Modifier.size(40.dp).background(if(d==day)Brand else SurfaceColor,CircleShape),contentAlignment=Alignment.Center){Text("一二三四五六日"[d-1].toString(),color=if(d==day)OnBrand else Muted)}}}}
            if(original.date==null){OutlinedTextField(weeks,{weeks=it.take(150)},label={Text("重复周次")},supportingText={Text("1-16 或 1,3,5；支持第 1–30 周")},singleLine=true,modifier=Modifier.fillMaxWidth())
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TextButton(onClick={weeks="1-16"}){Text("1–16 周")};TextButton(onClick={weeks=(1..16 step 2).joinToString(",")}){Text("单周")};TextButton(onClick={weeks=(2..16 step 2).joinToString(",")}){Text("双周")}}
            } else Text("仅本次：${original.date}（修改星期会调整到同一周）",color=Brand,style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().background(Mint,RoundedCornerShape(16.dp)).padding(horizontal=16.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("课前提醒");Text("提前时间可在设置中自由调整",style=MaterialTheme.typography.bodySmall,color=Muted)};GlassSwitch(reminder,{reminder=it})}
            if(existing)TextButton(onClick={deleting=true}){Text("删除课程",color=MaterialTheme.colorScheme.error)}
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
        }
        Column(Modifier.fillMaxWidth().background(Bg).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text("先加入草稿，回到课表点保存后生效。",color=Muted,style=MaterialTheme.typography.bodySmall);PrimaryButton(if(existing)"完成自定义" else "确定创建",{course()?.let(onSave)})}
    }
    if(timeWheel)TimeRangeWheelSheet(parseTime(start)?:original.start,parseTime(end)?:original.end,{timeWheel=false}){s,e->start=timeText(s);end=timeText(e);duration=(e-s).toString();error=null;timeWheel=false}
    if(deleting)GlassAlertDialog(onDismissRequest={deleting=false},title={Text("删除这节课？")},text={Text("${original.name}\n${original.place}")},confirmButton={TextButton(onClick={deleting=false;onDelete()}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}
