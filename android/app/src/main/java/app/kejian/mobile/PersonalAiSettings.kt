package app.kejian.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal object PersonalAiStatus {
    var error by mutableStateOf<String?>(null)
}

/** Credentials never enter AppData, backups, analytics, logs or saved UI state. */
@Composable fun PersonalAiSettings(model:KejianViewModel,onBack:()->Unit){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var gateway by remember {mutableStateOf(context.getSharedPreferences("kejian_byok",0).getString("gateway","").orEmpty())}
    var key by remember {mutableStateOf("")}
    var speechKey by remember {mutableStateOf("")}
    var busy by remember {mutableStateOf(false)}
    var notice by remember {mutableStateOf<String?>(if(model.cloudSession==null)"尚未配置 API Key。AI 功能需要有效密钥；课表、备注和本机录音可以照常使用。"else null)}
    Column(Modifier.fillMaxSize().padding(top=LocalPageTopInset.current)){
        ScreenHeading("个人 AI 服务",onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            Text("使用自己的 API Key",style=MaterialTheme.typography.headlineSmall)
            Text("填写你部署或信任的课间开源服务器。密钥会通过 HTTPS 发给该服务器，再由它向配置的 AI 服务商提交文件。服务费用由你的服务商账号承担。",color=Muted)
            OutlinedTextField(gateway,{gateway=it},label={Text("服务器地址")},placeholder={Text("https://ai.example.com")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(key,{key=it},label={Text("AI API Key")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(speechKey,{speechKey=it},label={Text("豆包语音 API Key（使用转写时填写）")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
            Text("AI 模型与服务商地址由自托管服务器配置。图片识别需要支持视觉的模型；豆包转写使用独立的语音密钥。密钥在本机加密保存，不包含在课表导出中。",color=Muted)
            GlassButton(onClick={
                if(key.isBlank()){notice="缺少 API Key，请先填写。";return@GlassButton}
                busy=true
                scope.launch {
                    try {
                        validatePersonalAi(gateway,key)
                        if(model.configurePersonalAi(gateway,key,speechKey)){
                            key="";speechKey="";notice="API Key 验证通过，配置已加密保存在本机。"
                        }else notice=model.message?:"配置保存失败，请检查输入。"
                    }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
                    catch(error:Exception){notice=error.message?:"无法连接 AI 服务，请检查服务器地址与 API Key。"}
                    finally{busy=false}
                }
            },enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text(if(busy)"正在验证…"else"验证并保存")}
            GlassOutlinedButton(onClick={model.logoutCloud();notice="本机 API Key 已移除。"},enabled=!busy&&model.cloudSession!=null,modifier=Modifier.fillMaxWidth()){Text("移除 API Key")}
        }
    }
    notice?.let {text->GlassAlertDialog(onDismissRequest={notice=null},title={Text("AI 服务提示")},text={Text(text)},confirmButton={TextButton(onClick={notice=null}){Text("知道了")}})}
}

private suspend fun validatePersonalAi(gateway:String,key:String){
    val uri=java.net.URI(gateway.trim().trimEnd('/'))
    require(uri.scheme=="https"&&!uri.host.isNullOrBlank()&&uri.userInfo==null&&uri.query==null&&uri.fragment==null&&(uri.path.isNullOrBlank()||uri.path=="/")){"请填写 HTTPS 服务器域名，不包含路径或参数。"}
    require(key.length<=512&&!key.any {it.isWhitespace()}){"API Key 格式无效。"}
    val response=CloudHttpTransport().execute("GET","${uri.toString().trimEnd('/')}/api/v1/byok/check",mapOf("Authorization" to "Bearer $key","Accept" to "application/json"),readTimeoutMs=30_000)
    when(response.code){
        200->Unit
        401,403->error("API Key 无效、已过期或无权使用服务器配置的模型。")
        429->error("服务商限流或额度不足，请检查 API Key 的可用额度。")
        else->error("服务器验证失败（${response.code}），请检查服务商配置后重试。")
    }
}
