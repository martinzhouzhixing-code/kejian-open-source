package app.kejian.mobile

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

@Composable fun AccountAvatar(session:CloudSession?,revision:Int,modifier:Modifier=Modifier){
    val context=LocalContext.current
    val bitmap=remember(session?.userId,revision){session?.userId?.let {id->LocalMediaStore.avatarFile(context,id).takeIf {it.isFile}?.let {BitmapFactory.decodeFile(it.absolutePath)?.asImageBitmap()}}}
    GlassSurface(shape=CircleShape,color=Mint,modifier=modifier){
        Box(contentAlignment=Alignment.Center){
            if(bitmap!=null)Image(bitmap,"用户头像",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
            else if(session==null)Icon(Icons.Outlined.AccountCircle,"默认头像",tint=Brand,modifier=Modifier.fillMaxSize(.62f))
            else Text(session.email.firstOrNull()?.uppercaseChar()?.toString()?:"课",color=Brand,style=MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable fun CloudAccountScreen(model:KejianViewModel,onBack:()->Unit,onChooseAvatar:()->Unit={}){
    val context=LocalContext.current
    val session=model.cloudSession
    var register by remember {mutableStateOf(false)}
    var email by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var answer by remember {mutableStateOf("")}
    var deleteDialog by remember {mutableStateOf(false)}
    val purpose=if(register)"register" else "login"
    LaunchedEffect(session,register){if(session==null){answer="";if(register)model.requestCaptcha(purpose)}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()){
        ScreenHeading(if(session==null)"课间账号" else "云端课表",onBack)
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            if(session==null){
                Text("账号不是使用课间的前提",style=MaterialTheme.typography.headlineSmall)
                Text("不登录也能继续使用完整课表。登录后，课表才会加密传输到 kejian.im，并可在其他设备恢复。",color=Muted)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(selected=!register,onClick={register=false},label={Text("登录")},modifier=Modifier.weight(1f))
                    FilterChip(selected=register,onClick={register=true},label={Text("创建账号")},modifier=Modifier.weight(1f))
                }
                WhiteCard{
                    OutlinedTextField(email,{email=it.trimStart().take(254)},label={Text("邮箱")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(password,{password=it.take(72)},label={Text("密码")},supportingText=if(register){{Text("8–72 位，同时包含字母和数字")}}else null,singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    if(register)CaptchaBlock(model,answer,{answer=it.take(5).uppercase()},purpose)
                    PrimaryButton(if(register)"创建账号并同步" else "登录并同步",{model.authenticate(register,email,password,answer)},enabled=!model.cloudBusy&&email.isNotBlank()&&password.isNotBlank()&&(!register||(answer.length==5&&model.captcha?.purpose==purpose)))
                }
                Text("当前不发送邮箱验证码，也不要求绑定手机号。请确认邮箱拼写正确；忘记密码功能将在邮箱验证上线后提供。",style=MaterialTheme.typography.bodySmall,color=Muted)
                Text("继续即表示你已阅读《隐私政策》",style=MaterialTheme.typography.bodySmall,color=Brand,modifier=Modifier.clickable {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://kejian.im/privacy")))})
            }else{
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    AccountAvatar(session,model.avatarRevision,Modifier.size(64.dp))
                    Column(Modifier.weight(1f)){Text(session.email,style=MaterialTheme.typography.titleLarge);Text(session.profile.roleLabel,color=if(model.supporter)Brand else Muted)}
                }
                WhiteCard {Text("本月额度",style=MaterialTheme.typography.titleMedium);Text(if(session.profile.isDeveloper)"AI 与录音总结：无限" else "课表 AI：剩余 ${session.profile.aiPercentRemaining}% · 录音总结：剩余 ${(session.profile.audioSecondsRemaining?:0)/60} 分钟",color=Brand);LinearProgressIndicator(progress={session.profile.aiPercentRemaining/100f},modifier=Modifier.fillMaxWidth())}
                GlassOutlinedButton(onClick=onChooseAvatar,modifier=Modifier.fillMaxWidth()){Text("从相册更换头像")}
                Text("长按并拖动存档完成上传或下载。只有目标已有内容、会发生覆盖时才会询问。",color=Muted)
                CloudSlotsPanel(model)
                WhiteCard{
                    GlassOutlinedButton(onClick={model.refreshCloudSlots()},modifier=Modifier.fillMaxWidth(),enabled=!model.cloudBusy){Text(if(model.cloudBusy)"正在读取…"else "刷新云存档")}
                    GlassOutlinedButton(onClick={model.logoutCloud()},modifier=Modifier.fillMaxWidth(),enabled=!model.cloudBusy){Text("退出账号")}
                    TextButton(onClick={deleteDialog=true;model.requestCaptcha("delete")},modifier=Modifier.fillMaxWidth(),enabled=!model.cloudBusy){Text("注销账号并删除云端课表",color=MaterialTheme.colorScheme.error)}
                }
            }
            model.cloudMessage?.let {Text(it,color=if(it.contains("失败")||it.contains("错误")||it.contains("失效"))MaterialTheme.colorScheme.error else Brand,style=MaterialTheme.typography.bodyMedium)}
            Spacer(Modifier.height(20.dp))
        }
    }
    if(deleteDialog)DeleteCloudDialog(model,onClose={deleteDialog=false})
}

private data class SaveDrag(val from:String,val position:Offset)
private data class SaveTransfer(val from:String,val to:String)

@Composable private fun CloudSlotsPanel(model:KejianViewModel){
    val density=LocalDensity.current;val ghostX=with(density){90.dp.toPx()};val ghostY=with(density){34.dp.toPx()}
    val bounds=remember {mutableStateMapOf<String,Rect>()};var rootOrigin by remember {mutableStateOf(Offset.Zero)}
    var drag by remember {mutableStateOf<SaveDrag?>(null)};var pending by remember {mutableStateOf<SaveTransfer?>(null)}
    fun transfer(value:SaveTransfer,overwrite:Boolean){
        if(value.from=="local"&&value.to.startsWith("cloud"))model.uploadLocalToSlot(value.to.removePrefix("cloud").toInt(),overwrite)
        else if(value.to=="local"&&value.from.startsWith("cloud"))model.downloadSlotToLocal(value.from.removePrefix("cloud").toInt(),overwrite)
    }
    fun drop(value:SaveDrag){
        val target=bounds.entries.firstOrNull {(key,rect)->key!=value.from&&rect.contains(value.position)}?.key?:return
        val operation=SaveTransfer(value.from,target)
        val overwrites=if(value.from=="local"&&target.startsWith("cloud"))model.cloudSlots.getOrNull(target.removePrefix("cloud").toInt()-1)!=null else target=="local"&&value.from.startsWith("cloud")&&model.savedData.courses.isNotEmpty()
        if(overwrites)pending=operation else transfer(operation,false)
    }
    fun dragModifier(id:String,enabled:Boolean)=Modifier.onGloballyPositioned {bounds[id]=it.boundsInRoot()}.then(if(!enabled)Modifier else Modifier.pointerInput(id){detectDragGesturesAfterLongPress(onDragStart={local->bounds[id]?.let {drag=SaveDrag(id,Offset(it.left+local.x,it.top+local.y))}},onDrag={change,amount->change.consume();drag=drag?.copy(position=drag!!.position+amount)},onDragEnd={drag?.let(::drop);drag=null},onDragCancel={drag=null})})
    Box(Modifier.fillMaxWidth().onGloballyPositioned {rootOrigin=it.boundsInRoot().topLeft}){
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            SaveSlotCard("本地存档","${model.savedData.courses.size} 节课程 · ${model.savedData.settings.termName}",false,drag?.from=="local",dragModifier("local",!model.cloudBusy))
            model.cloudSlots.forEachIndexed {index,slot->val number=index+1;val unlocked=number<=model.cloudSlotCount
                SaveSlotCard("云存档 $number",when{!unlocked->"支持者会员权益";slot==null->"空槽位 · 可拖入本地存档";else->"${slot.data.courses.size} 节课程 · ${cloudTimeLabel(slot.updatedAt)?:"已保存"}"},!unlocked,drag?.from=="cloud$number",dragModifier("cloud$number",unlocked&&slot!=null&&!model.cloudBusy))
            }
        }
        drag?.let {value->GlassSurface(Modifier.offset {IntOffset((value.position.x-rootOrigin.x-ghostX).roundToInt(),(value.position.y-rootOrigin.y-ghostY).roundToInt())}.width(180.dp).zIndex(20f).graphicsLayer {alpha=.94f;scaleX=1.04f;scaleY=1.04f},shape=RoundedCornerShape(18.dp),color=Brand,shadowElevation=14.dp){Text(if(value.from=="local")"本地存档"else "云存档 ${value.from.removePrefix("cloud")}",Modifier.padding(18.dp),color=Color.White)}}
    }
    pending?.let {operation->GlassAlertDialog(onDismissRequest={pending=null},title={Text("确认覆盖存档？")},text={Text(if(operation.from=="local")"目标云存档已有内容。继续后会由当前本地存档替换。"else "本地已有课表。继续后会由所选云存档替换本机课表。")},confirmButton={GlassButton(onClick={pending=null;transfer(operation,true)}){Text("确认覆盖")}},dismissButton={TextButton(onClick={pending=null}){Text("取消")}})}
}

@Composable private fun SaveSlotCard(title:String,detail:String,locked:Boolean,dragging:Boolean,modifier:Modifier){
    GlassSurface(modifier.fillMaxWidth().heightIn(min=82.dp).graphicsLayer {scaleX=if(dragging).98f else 1f;scaleY=scaleX;alpha=if(dragging).55f else 1f}.border(1.dp,if(dragging)Brand.copy(alpha=.5f)else MaterialTheme.colorScheme.outline.copy(alpha=.22f),RoundedCornerShape(20.dp)),shape=RoundedCornerShape(20.dp),color=SurfaceColor){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){GlassSurface(shape=RoundedCornerShape(14.dp),color=if(locked)MaterialTheme.colorScheme.surfaceVariant else Mint,modifier=Modifier.size(48.dp)){Box(contentAlignment=Alignment.Center){Text(if(locked)"锁"else "云",color=if(locked)Muted else Brand)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall)};if(!locked)Text("长按拖动",color=Brand,style=MaterialTheme.typography.labelSmall)}}
}

@Composable private fun CaptchaBlock(model:KejianViewModel,answer:String,onAnswer:(String)->Unit,purpose:String){
    val challenge=model.captcha?.takeIf {it.purpose==purpose}
    val bitmap=remember(challenge?.id){challenge?.image?.let {BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap()}}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            GlassSurface(Modifier.weight(1f).height(62.dp),shape=RoundedCornerShape(14.dp),color=Bg){if(bitmap!=null)Image(bitmap,"机器人验证码",Modifier.fillMaxSize())else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)}}
            TextButton(onClick={model.requestCaptcha(purpose)},enabled=!model.cloudBusy){Text("换一张")}
        }
        OutlinedTextField(answer,onAnswer,label={Text("输入图中的 5 个字符")},singleLine=true,modifier=Modifier.fillMaxWidth())
    }
}

@Composable private fun DeleteCloudDialog(model:KejianViewModel,onClose:()->Unit){
    var password by remember {mutableStateOf("")};var answer by remember {mutableStateOf("")}
    GlassAlertDialog(onDismissRequest=onClose,title={Text("注销云端账号？")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("云端账号和云端课表将永久删除；当前手机里的课程会保留。")
        OutlinedTextField(password,{password=it.take(72)},label={Text("当前密码")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
        CaptchaBlock(model,answer,{answer=it.take(5).uppercase()},"delete")
    }},confirmButton={GlassButton(onClick={model.deleteCloudAccount(password,answer);onClose()},enabled=!model.cloudBusy&&password.isNotBlank()&&answer.length==5,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("永久删除")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}
