package app.kejian.mobile

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AccountProfile(
    val role:String="default",
    val aiPoints:Int?=0,
    val aiTokenLimit:Long?=0,
    val aiTokenUsed:Long=0,
    val audioSecondsLimit:Int?=0,
    val audioSecondsUsed:Int=0,
    val audioBonusSeconds:Int=0,
    val membershipExpiresAt:String?=null,
    val aiAssistant:Boolean=false,
    val audioSummary:Boolean=false,
    val widgetBackground:Boolean=false,
    val multipleCloudSlots:Boolean=false
){
    val isMember get()=role in setOf("plus","pro","supporter","developer")||aiAssistant||widgetBackground||multipleCloudSlots
    val isDeveloper get()=role=="developer"
    val roleLabel get()=when(role){"developer"->"开发者";"pro"->"Pro";"plus","supporter"->"Plus";else->"普通用户"}
    val aiPercentRemaining:Int get()=if(aiTokenLimit==null)100 else if((aiTokenLimit?:0)<=0)0 else (((aiTokenLimit-aiTokenUsed).coerceAtLeast(0)*100)/(aiTokenLimit?:1)).toInt()
    val audioSecondsRemaining:Int? get()=audioSecondsLimit?.let {(it+audioBonusSeconds-audioSecondsUsed).coerceAtLeast(0)}
}

data class CloudSession(
    val userId:String,
    val email:String,
    val accessToken:String,
    val refreshToken:String,
    val accessExpiresAt:String,
    val refreshExpiresAt:String,
    val revision:Int=0,
    val lastSyncAt:String?=null,
    val profile:AccountProfile=AccountProfile()
)

data class CaptchaChallenge(val id:String,val image:ByteArray,val purpose:String)
data class CloudSchedule(val data:AppData,val revision:Int,val updatedAt:String,val slot:Int=1)
data class CloudConflict(val cloud:CloudSchedule)
class CloudApiException(val status:Int,message:String):Exception(message)
class AudioJobFailedException(message:String):Exception(message)
class MindMapRequestException(val status:Int,val code:String,message:String):Exception(message)
class NoteInsightRequestException(val status:Int,val code:String,message:String):Exception(message)
internal fun mindMapOwnerMatches(expected:String,visible:String?,persisted:String?)=expected.isNotBlank()&&visible==expected&&persisted==expected

internal class SecureAccountStore(context:Context){
    companion object {private val writeLock=Any()}
    private val prefs=context.getSharedPreferences("kejian_cloud",Context.MODE_PRIVATE)
    private val alias="kejian_cloud_auth_v1"
    private fun key():SecretKey{
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    fun load():CloudSession?=runCatching{
        val bytes=Base64.decode(prefs.getString("session",null)?:return null,Base64.NO_WRAP)
        require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val json=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        CloudSession(json.getString("userId"),json.getString("email"),json.getString("accessToken"),json.getString("refreshToken"),json.getString("accessExpiresAt"),json.getString("refreshExpiresAt"),json.optInt("revision",0),json.optString("lastSyncAt").takeIf {it.isNotBlank()},parseProfile(json.optJSONObject("profile")))
    }.getOrElse {null}
    fun save(value:CloudSession)=synchronized(writeLock){
        val json=JSONObject().apply {put("userId",value.userId);put("email",value.email);put("accessToken",value.accessToken);put("refreshToken",value.refreshToken);put("accessExpiresAt",value.accessExpiresAt);put("refreshExpiresAt",value.refreshExpiresAt);put("revision",value.revision);put("lastSyncAt",value.lastSyncAt);put("profile",profileJson(value.profile))}.toString().toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=cipher.iv+cipher.doFinal(json)
        check(prefs.edit().putString("session",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
    }
    /** Merge metadata into the persisted session so another client cannot roll back refreshed credentials. */
    fun updateMetadata(userId:String,transform:(CloudSession)->CloudSession):CloudSession?=synchronized(writeLock){
        val latest=load()?.takeIf {it.userId==userId}?:return@synchronized null
        transform(latest).also(::save)
    }
    fun clearIfMatches(userId:String,refreshToken:String):Boolean=synchronized(writeLock){
        val latest=load()
        if(latest?.userId!=userId||latest.refreshToken!=refreshToken)return@synchronized false
        prefs.edit().remove("session").commit()
    }
    fun clear(){synchronized(writeLock){runCatching {prefs.edit().remove("session").commit()}}}
}

class CloudAccountClient(context:Context){
    private val endpointPrefs=context.getSharedPreferences("kejian_byok",Context.MODE_PRIVATE)
    private val store=SecureAccountStore(context)
    private val clientVersion=runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull()?:"unknown"
    companion object {private val sessionRefreshLock=Mutex()}
    private val transport=CloudHttpTransport()
    @Volatile var currentSession:CloudSession?=store.load();private set
    private val base get()=endpointPrefs.getString("gateway","").orEmpty().trimEnd('/')+"/api/v1"

    private data class Response(val code:Int,val json:JSONObject?)
    private suspend fun raw(method:String,path:String,body:JSONObject?=null,token:String?=null,readTimeoutMs:Int=20_000):Response{
        require(base.startsWith("https://")){"请先在设置中填写 HTTPS AI 服务器地址和 API Key"}
        val headers=mutableMapOf("Accept" to "application/json","User-Agent" to "Kejian-Android/$clientVersion")
        if(method=="POST"&&path in setOf("/ai/recognize","/ai/task","/ai/mindmap","/ai/note-insights")&&
            (body?.optString("clientRequestId")?.isNotBlank()==true||body?.optString("requestId")?.isNotBlank()==true))headers["X-Kejian-Background"]="1"
        if(token!=null)headers["Authorization"]="Bearer $token"
        currentSession?.refreshToken?.takeIf {it.isNotBlank()}?.let {headers["X-Kejian-ASR-Key"]=it}
        val response=transport.execute(method,base+path,headers,body?.toString()?.toByteArray(Charsets.UTF_8),readTimeoutMs=readTimeoutMs)
        if(response.code in setOf(401,403)){PersonalAiStatus.error=runCatching {JSONObject(response.text).optString("error")}.getOrNull()?.takeIf {it.isNotBlank()}?:"API Key 无效、已过期或无权使用此模型，请在设置中检查。";throw CloudApiException(response.code,PersonalAiStatus.error!!)}
        return Response(response.code,response.text.takeIf {it.isNotBlank()}?.let {runCatching {JSONObject(it)}.getOrNull()})
    }
    private fun failure(response:Response,audio:Boolean=false):Nothing{
        val fallback=when(response.code){
            413->if(audio)"音频上传分段过大，请稍后重试" else "图片过大，请选择 24 MB 以内的图片"
            429->"请求较频繁，请稍后再试"
            502,503,504->"AI 服务暂时繁忙，请稍后重试；本次不扣积分"
            else->"网络请求失败（${response.code}）"
        }
        throw CloudApiException(response.code,response.json?.optString("error")?.takeIf {it.isNotBlank()}?:fallback)
    }
    private suspend fun refreshIfNeeded(stale:String):CloudSession?=sessionRefreshLock.withLock{
        val cached=currentSession?:return@withLock null
        val persisted=store.load()
        if(persisted==null||persisted.userId!=cached.userId)throw CloudApiException(401,"登录状态已变化，请重新登录")
        val existing=persisted.also {currentSession=it}
        if(existing.accessToken!=stale)return@withLock existing
        val response=raw("POST","/auth/refresh",JSONObject().put("refreshToken",existing.refreshToken))
        if(response.code !in 200..299){
            if(response.code in setOf(400,401,403)){
                if(store.clearIfMatches(existing.userId,existing.refreshToken)){currentSession=null;ProductAccess.update(null)}
                return@withLock null
            };failure(response)
        }
        val json=response.json?:return@withLock null
        val updated=store.updateMetadata(existing.userId){latest->
            if(latest.refreshToken!=existing.refreshToken)latest
            else latest.copy(accessToken=json.getString("accessToken"),refreshToken=json.getString("refreshToken"),accessExpiresAt=json.getString("accessExpiresAt"),refreshExpiresAt=json.getString("refreshExpiresAt"))
        }?:throw CloudApiException(401,"登录状态已变化，请重新登录")
        currentSession=updated;updated
    }
    private suspend fun validSession():CloudSession {
        val value=store.load()?:throw CloudApiException(401,"缺少 API Key，请先在设置中填写。")
        require(value.accessToken.isNotBlank()){ "缺少 API Key，请先在设置中填写。" }
        return value.also {currentSession=it}
    }
    fun configure(gateway:String,key:String,asrKey:String):CloudSession {
        val url=java.net.URI(gateway.trim().trimEnd('/'))
        require(url.scheme=="https"&&!url.host.isNullOrBlank()&&url.userInfo==null&&url.query==null&&url.fragment==null&&(url.path.isNullOrBlank()||url.path=="/")){"服务器地址必须为 HTTPS 域名，不包含路径、用户名或参数"}
        require(key.isNotBlank()&&key.length<=512&&!key.any {it.isWhitespace()}){"请填写有效的 API Key"}
        require(asrKey.length<=512&&!asrKey.any {it.isWhitespace()}){"语音 API Key 格式无效"}
        val profile=AccountProfile(role="developer",aiPoints=null,aiTokenLimit=null,audioSecondsLimit=null,aiAssistant=true,audioSummary=true,widgetBackground=true)
        val value=CloudSession(java.util.UUID.randomUUID().toString(),"个人 API Key",key,asrKey,"9999-12-31T00:00:00Z","9999-12-31T00:00:00Z",profile=profile)
        check(endpointPrefs.edit().putString("gateway",url.toString().trimEnd('/')).commit())
        store.save(value);currentSession=value;ProductAccess.update(profile)
        return value
    }
    private suspend fun authorized(method:String,path:String,body:JSONObject?=null,readTimeoutMs:Int=20_000):Response{
        val session=validSession()
        var response=raw(method,path,body,session.accessToken,readTimeoutMs)
        if(response.code==401){val fresh=refreshIfNeeded(session.accessToken)?:throw CloudApiException(401,"登录已失效，请重新登录");response=raw(method,path,body,fresh.accessToken,readTimeoutMs)}
        if(response.code !in 200..299)failure(response,audio=path.startsWith("/audio/"))
        return response
    }
    suspend fun administratorVerified(expectedUserId:String):Boolean {
        if(currentSession?.userId!=expectedUserId)return false
        val response=authorized("GET","/admin/session")
        return currentSession?.userId==expectedUserId && response.json?.optString("email")?.isNotBlank()==true
    }
    suspend fun adminLoginCode():String=authorized("POST","/admin/login-code").json?.getString("code")?:throw CloudApiException(500,"登录码响应无效")
    suspend fun captcha(purpose:String):CaptchaChallenge{
        val response=raw("POST","/captcha",JSONObject().put("purpose",purpose));if(response.code !in 200..299)failure(response)
        val json=response.json?:throw CloudApiException(500,"验证码响应无效")
        return CaptchaChallenge(json.getString("id"),Base64.decode(json.getString("imageBase64"),Base64.DEFAULT),purpose)
    }
    private suspend fun authenticate(path:String,email:String,password:String,captcha:CaptchaChallenge?=null,answer:String=""):CloudSession{
        val body=JSONObject().apply {put("email",email);put("password",password);if(captcha!=null){put("captchaId",captcha.id);put("captchaAnswer",answer)};put("deviceName","${Build.MANUFACTURER} ${Build.MODEL}".trim())}
        val response=raw("POST",path,body);if(response.code !in 200..299)failure(response)
        val json=response.json?:throw CloudApiException(500,"登录响应无效");val user=json.getJSONObject("user")
        return CloudSession(user.getString("id"),user.getString("email"),json.getString("accessToken"),json.getString("refreshToken"),json.getString("accessExpiresAt"),json.getString("refreshExpiresAt"),profile=parseProfile(user)).also {currentSession=it;store.save(it);ProductAccess.update(it.profile)}
    }
    suspend fun register(email:String,password:String,captcha:CaptchaChallenge,answer:String)=authenticate("/auth/register",email,password,captcha,answer)
    suspend fun login(email:String,password:String)=authenticate("/auth/login",email,password)
    suspend fun fetchSchedule(slot:Int=1):CloudSchedule?{
        require(slot in 1..3)
        val root=authorized("GET","/schedule?slot=$slot").json?:return null
        if(root.isNull("schedule"))return null
        val schedule=root.getJSONObject("schedule")
        return CloudSchedule(DataJson.decode(schedule.getJSONObject("data").toString()),schedule.getInt("revision"),schedule.getString("updatedAt"),slot)
    }
    suspend fun uploadSchedule(data:AppData,baseRevision:Int?,force:Boolean=false,slot:Int=1):CloudSession{
        require(slot in 1..3)
        val body=JSONObject().put("data",JSONObject(DataJson.encode(data,includeLocalMedia=false))).put("force",force);if(baseRevision!=null)body.put("baseRevision",baseRevision)
        val json=authorized("PUT","/schedule?slot=$slot",body).json?:throw CloudApiException(500,"同步响应无效")
        val session=currentSession?:throw CloudApiException(401,"登录状态已变化，请重新同步")
        val updated=store.updateMetadata(session.userId){it.copy(revision=json.getInt("revision"),lastSyncAt=json.getString("updatedAt"))}
            ?:throw CloudApiException(401,"登录状态已变化，请重新同步")
        currentSession=updated;return updated
    }
    private suspend fun ownerAiResponse(method:String,path:String,body:JSONObject?,owner:String,timeout:Int=150_000):Response{
        fun checkOwner(){check(mindMapOwnerMatches(owner,currentSession?.userId,store.load()?.userId)){"Account changed"}}
        checkOwner();val session=validSession();checkOwner()
        var response=raw(method,path,body,session.accessToken,timeout);checkOwner()
        if(response.code==401){val fresh=refreshIfNeeded(session.accessToken)?:throw CloudApiException(401,"Session expired");checkOwner();response=raw(method,path,body,fresh.accessToken,timeout);checkOwner()}
        return response
    }
    suspend fun queryAiRequest(requestId:String,expectedOwner:String):JSONObject?{
        require(requestId.matches(Regex("[A-Za-z0-9_-]{16,80}")))
        val response=ownerAiResponse("GET","/ai/requests/$requestId",null,expectedOwner,30_000)
        if(response.code==404)return null
        if(response.code==410)throw AiBackgroundException("LOCAL_CACHE_EXPIRED","The recovery window has expired")
        if(response.code !in 200..299)failure(response)
        return response.json?.also {require(it.getString("requestId")==requestId)}?:error("Invalid recovery response")
    }
    suspend fun recognizeImage(bytes:ByteArray,mimeType:String,fileName:String?,colorOffset:Int,requestId:String?=null,expectedOwner:String?=null):JSONObject{
        require(bytes.isNotEmpty()&&bytes.size<=24*1024*1024){"图片为空或超过 24 MB"}
        require(mimeType in setOf("image/jpeg","image/png","image/webp","image/gif")){"暂不支持这种图片格式"}
        val body=JSONObject().apply {
            put("contentBase64",Base64.encodeToString(bytes,Base64.NO_WRAP));put("mimeType",mimeType);put("fileName",fileName);put("colorOffset",colorOffset.coerceIn(0,11))
            if(requestId!=null)put("clientRequestId",requestId)
        }
        val response=ownerAiResponse("POST","/ai/recognize",body,expectedOwner?:currentSession?.userId?:error("Sign in first"))
        if(response.code !in 200..299)failure(response)
        return response.json?:throw CloudApiException(500,"识别响应无效")
    }
    suspend fun runAiTask(command:String,scheduleLines:List<String>,requestId:String?=null,expectedOwner:String?=null):JSONObject{
        val body=JSONObject().put("command",command).put("scheduleLines",org.json.JSONArray(scheduleLines))
        if(requestId!=null)body.put("clientRequestId",requestId)
        val response=ownerAiResponse("POST","/ai/task",body,expectedOwner?:currentSession?.userId?:error("Sign in first"))
        if(response.code !in 200..299)failure(response)
        return response.json?:throw CloudApiException(500,"任务响应无效")
    }
    suspend fun generateRecordingMindMap(note:LessonNote,requestId:String,expectedOwnerId:String):LessonMindMap{
        require(note.summary.isNotBlank())
        val digest=noteContentDigest(note)
        val body=JSONObject().put("requestId",requestId).put("noteId",note.id).put("contentDigest",digest)
            .put("title",note.title).put("summary",note.summary).put("keyPoints",org.json.JSONArray(note.keyPoints))
            .put("actionItems",org.json.JSONArray(note.actionItems)).put("transcript",note.transcript)
        fun ensureOwner(){
            if(!mindMapOwnerMatches(expectedOwnerId,currentSession?.userId,store.load()?.userId))
                throw MindMapRequestException(401,"MINDMAP_ACCOUNT_CHANGED",if(AppLanguage.english)"The account changed. The previous request will not update this account." else "账号已变化，之前的请求不会更新当前账号。")
        }
        ensureOwner()
        val session=validSession()
        ensureOwner()
        if(session.userId!=expectedOwnerId)throw MindMapRequestException(401,"MINDMAP_ACCOUNT_CHANGED","Account changed")
        var response=raw("POST","/ai/mindmap",body,session.accessToken,550_000)
        // A 401 retry must never use the credentials of an account selected while this request was pending.
        ensureOwner()
        if(response.code==401){
            val fresh=refreshIfNeeded(session.accessToken)?:throw CloudApiException(401,"登录已失效，请重新登录")
            ensureOwner()
            if(fresh.userId!=expectedOwnerId)throw MindMapRequestException(401,"MINDMAP_ACCOUNT_CHANGED","Account changed")
            response=raw("POST","/ai/mindmap",body,fresh.accessToken,550_000)
            ensureOwner()
        }
        if(response.code !in 200..299){
            val code=response.json?.optString("code").orEmpty()
            throw MindMapRequestException(response.code,code,response.json?.optString("error")?.takeIf {it.isNotBlank()}?:if(AppLanguage.english)"Service unavailable (${response.code})" else "服务暂不可用（${response.code}）")
        }
        val root=response.json?:error(if(AppLanguage.english)"Invalid mind map response" else "思维导图响应无效")
        require(root.getString("requestId")==requestId&&root.getString("noteId")==note.id&&root.getString("contentDigest")==digest)
        val result=LessonMindMap.parse(root.getJSONObject("mindMap"),digest)
        ensureOwner()
        root.optJSONObject("profile")?.let {p->
            val profile=parseProfile(p)
            val updated=store.updateMetadata(expectedOwnerId){it.copy(profile=profile)}
                ?:throw MindMapRequestException(401,"MINDMAP_ACCOUNT_CHANGED","Account changed")
            ensureOwner();currentSession=updated;ProductAccess.update(profile)
        }
        return result
    }
    suspend fun askRecordingInsight(note:LessonNote,turn:NoteInsightTurn,expectedOwnerId:String):NoteInsightAnswer{
        require(note.summary.isNotBlank()&&turn.owner==expectedOwnerId&&turn.noteId==note.id&&turn.digest==noteContentDigest(note))
        val body=JSONObject().put("requestId",turn.requestId).put("noteId",note.id).put("contentDigest",turn.digest)
            .put("title",note.title).put("summary",note.summary).put("keyPoints",org.json.JSONArray(note.keyPoints))
            .put("actionItems",org.json.JSONArray(note.actionItems)).put("transcript",note.transcript)
            .put("webSearch",turn.webSearch).put("question",turn.question).put("history",org.json.JSONArray(turn.history))
        fun ensureOwner(){
            if(!mindMapOwnerMatches(expectedOwnerId,currentSession?.userId,store.load()?.userId))
                throw NoteInsightRequestException(401,"INSIGHTS_ACCOUNT_CHANGED","Account changed")
        }
        ensureOwner()
        val session=validSession();ensureOwner()
        if(session.userId!=expectedOwnerId)throw NoteInsightRequestException(401,"INSIGHTS_ACCOUNT_CHANGED","Account changed")
        var response=raw("POST","/ai/note-insights",body,session.accessToken,360_000)
        ensureOwner()
        if(response.code==401){
            val fresh=refreshIfNeeded(session.accessToken)?:throw CloudApiException(401,"Session expired")
            ensureOwner()
            if(fresh.userId!=expectedOwnerId)throw NoteInsightRequestException(401,"INSIGHTS_ACCOUNT_CHANGED","Account changed")
            response=raw("POST","/ai/note-insights",body,fresh.accessToken,360_000);ensureOwner()
        }
        if(response.code !in 200..299)throw NoteInsightRequestException(response.code,response.json?.optString("code").orEmpty(),"Insights HTTP ${response.code}")
        val root=response.json?:error("Invalid Insights response")
        require(root.getString("requestId")==turn.requestId&&root.getString("noteId")==note.id&&root.getString("contentDigest")==turn.digest)
        val answer=NoteInsightAnswer.parse(root.getJSONObject("answer"),note,root.getJSONObject("usage"))
        ensureOwner()
        root.optJSONObject("profile")?.let {p->
            val profile=parseProfile(p)
            val updated=store.updateMetadata(expectedOwnerId){it.copy(profile=profile)}
                ?:throw NoteInsightRequestException(401,"INSIGHTS_ACCOUNT_CHANGED","Account changed")
            ensureOwner();currentSession=updated;ProductAccess.update(profile)
        }
        return answer
    }
    suspend fun recordingSummary(body:JSONObject,owner:String,quote:Boolean=false):JSONObject{
        val response=ownerAiResponse("POST","/ai/recording-summary"+(if(quote)"/quote"else""),body,owner,if(quote)30_000 else 550_000)
        if(response.code !in 200..299)throw AiBackgroundException(response.json?.optString("code")?.takeIf {it.isNotBlank()}?:"SUMMARY_FAILED","Summary HTTP ${response.code}")
        val root=response.json?:error("Invalid summary response")
        if(!quote){
            require(root.getString("requestId")==body.getString("requestId")&&root.getString("noteId")==body.getString("noteId")&&root.getString("contentDigest")==body.getString("contentDigest"))
            require(root.getJSONObject("summary").getString("language")==body.getString("language"))
            root.optJSONObject("profile")?.let {p->
                check(currentSession?.userId==owner&&store.load()?.userId==owner){"Account changed"}
                val profile=parseProfile(p);currentSession=store.updateMetadata(owner){it.copy(profile=profile)}?:error("Account changed");ProductAccess.update(profile)
            }
        }
        return root
    }
    suspend fun cancelAiRequest(requestId:String,expectedOwner:String?=null){
        require(requestId.matches(Regex("[A-Za-z0-9_-]{16,80}"))){"Invalid request identifier"}
        val response=ownerAiResponse("POST","/ai/requests/$requestId/cancel",JSONObject(),expectedOwner?:currentSession?.userId?:error("Sign in first"),30_000)
        if(response.code !in 200..299)failure(response)
    }
    private fun ensureAudioOwner(expectedOwnerId:String){
        if(currentSession?.userId!=expectedOwnerId||store.load()?.userId!=expectedOwnerId)
            throw CloudApiException(401,"登录状态已变化，请登录发起此录音任务的账号")
    }
    private suspend fun audioAuthorized(expectedOwnerId:String,method:String,path:String,body:JSONObject?=null,timeout:Int=30_000):JSONObject{
        ensureAudioOwner(expectedOwnerId)
        val response=kotlinx.coroutines.withTimeoutOrNull(timeout.toLong()+30_000){authorized(method,path,body,timeout)}
            ?:throw java.net.SocketTimeoutException("Audio request timed out")
        ensureAudioOwner(expectedOwnerId)
        return response.json?:throw CloudApiException(500,"录音任务响应无效")
    }
    private suspend fun audioChunk(requestId:String,offset:Long,bytes:ByteArray,expectedOwnerId:String):JSONObject{
        suspend fun send(session:CloudSession):Response{
            val response=kotlinx.coroutines.withTimeoutOrNull(120_000){transport.execute("PUT","$base/audio/uploads/$requestId?offset=$offset",
                mapOf("Accept" to "application/json","Authorization" to "Bearer ${session.accessToken}","User-Agent" to "Kejian-Android/$clientVersion","X-Kejian-ASR-Key" to session.refreshToken),
                bytes,contentType="audio/mp4",readTimeoutMs=60_000)}?:throw java.net.SocketTimeoutException("Audio chunk upload timed out")
            if(response.code in setOf(401,403)){PersonalAiStatus.error=runCatching {JSONObject(response.text).optString("error")}.getOrNull()?.takeIf {it.isNotBlank()}?:"API Key 无效、已过期或无权使用此模型，请在设置中检查。";throw CloudApiException(response.code,PersonalAiStatus.error!!)}
        return Response(response.code,response.text.takeIf {it.isNotBlank()}?.let {runCatching {JSONObject(it)}.getOrNull()})
        }
        ensureAudioOwner(expectedOwnerId)
        val session=validSession();ensureAudioOwner(expectedOwnerId)
        var response=send(session)
        if(response.code==401){
            val fresh=refreshIfNeeded(session.accessToken)?:throw CloudApiException(401,"登录已失效，请重新登录")
            ensureAudioOwner(expectedOwnerId)
            response=send(fresh)
        }
        ensureAudioOwner(expectedOwnerId)
        if(response.code !in 200..299)failure(response,audio=true)
        return response.json?:throw CloudApiException(500,"上传响应无效")
    }
    suspend fun enqueueAudio(
        file:java.io.File,durationSeconds:Int,requestId:String=java.util.UUID.randomUUID().toString(),
        sha256:String?=null,expectedOwnerId:String=currentSession?.userId.orEmpty(),summaryLanguage:String="zh-CN",summaryRequirements:String="",onProgress:(JSONObject)->Unit={}
    ):JSONObject{
        val fingerprint=sha256?:audioSha256(file)
        val api=object:AudioUploadApi{
            override suspend fun begin(requestId:String,duration:Int,size:Long,sha256:String)=
                audioAuthorized(expectedOwnerId,"POST","/audio/uploads",JSONObject().put("requestId",requestId).put("durationSeconds",duration).put("sizeBytes",size).put("sha256",sha256).put("summaryLanguage",summaryLanguage).put("summaryRequirements",summaryRequirements))
            override suspend fun status(requestId:String)=
                audioAuthorized(expectedOwnerId,"GET","/audio/uploads/$requestId")
            override suspend fun put(requestId:String,offset:Long,bytes:ByteArray)=audioChunk(requestId,offset,bytes,expectedOwnerId)
            override suspend fun complete(requestId:String)=
                audioAuthorized(expectedOwnerId,"POST","/audio/uploads/$requestId/complete",JSONObject(),60_000)
        }
        return ResumableAudioTransfer(api).upload(file,durationSeconds,requestId,fingerprint,onProgress)
    }
    suspend fun audioJobStatus(jobId:String,expectedOwnerId:String=currentSession?.userId.orEmpty()):JSONObject=
        audioAuthorized(expectedOwnerId,"GET","/audio/jobs/$jobId")
    suspend fun transcribeAudio(file:java.io.File,durationSeconds:Int,onProgress:(JSONObject)->Unit={}):JSONObject{
        val state=enqueueAudio(file,durationSeconds,onProgress=onProgress)
        return waitForAudioJob(state.getString("jobId"),onProgress=onProgress)
    }
    suspend fun waitForAudioJob(jobId:String,expectedOwnerId:String=currentSession?.userId.orEmpty(),onProgress:(JSONObject)->Unit={}):JSONObject=
        pollAudioJob(jobId,{audioJobStatus(it,expectedOwnerId)},onProgress)
    suspend fun confirmAiJob(jobId:String):AccountProfile{
        val root=authorized("POST","/ai/jobs/$jobId/confirm",JSONObject()).json?:throw CloudApiException(500,"确认响应无效")
        val profile=parseProfile(root.getJSONObject("profile"));currentSession?.let {session->currentSession=store.updateMetadata(session.userId){it.copy(profile=profile)}};ProductAccess.update(profile);return profile
    }
    suspend fun refreshProfile():AccountProfile{
        val profile=parseProfile(authorized("GET","/me").json?:throw CloudApiException(500,"账号信息无效"))
        currentSession?.let {session->currentSession=store.updateMetadata(session.userId){it.copy(profile=profile)}};ProductAccess.update(profile);return profile
    }
    suspend fun uploadClientError(jobId:String?,category:String,message:String,context:JSONObject=JSONObject()){
        val body=JSONObject().put("jobId",jobId).put("category",category.take(60)).put("message",message.take(1000)).put("context",context)
            .put("appVersion",clientVersion).put("device","${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}".take(160))
        try {authorized("POST","/client-errors",body,20_000)}catch(cancelled:CancellationException){throw cancelled}catch(_:Exception){}
    }
    fun rememberRevision(revision:Int,updatedAt:String):CloudSession?=currentSession?.let {session->store.updateMetadata(session.userId){it.copy(revision=revision,lastSyncAt=updatedAt)}}.also {currentSession=it}
    suspend fun logout(){runCatching {authorized("POST","/auth/logout")};clear()}
    suspend fun delete(password:String,captcha:CaptchaChallenge,answer:String){authorized("DELETE","/account",JSONObject().put("password",password).put("captchaId",captcha.id).put("captchaAnswer",answer));clear()}
    fun clear(){currentSession=null;store.clear();ProductAccess.update(null)}
}

private fun parseProfile(json:JSONObject?):AccountProfile{
    if(json==null)return AccountProfile()
    val entitlements=json.optJSONObject("entitlements")
    return AccountProfile(
        role=json.optString("role","default"),
        aiPoints=if(json.isNull("aiPoints"))null else json.optInt("aiPoints",0),
        aiTokenLimit=if(json.isNull("aiTokenLimit"))null else json.optLong("aiTokenLimit",0),
        aiTokenUsed=json.optLong("aiTokenUsed",0),
        audioSecondsLimit=if(json.isNull("audioSecondsLimit"))null else json.optInt("audioSecondsLimit",0),
        audioSecondsUsed=json.optInt("audioSecondsUsed",0),audioBonusSeconds=json.optInt("audioBonusSeconds",0),
        membershipExpiresAt=json.optString("membershipExpiresAt").takeIf {it.isNotBlank()&&it!="null"},
        aiAssistant=entitlements?.optBoolean("aiAssistant",false)?:false,
        audioSummary=entitlements?.optBoolean("audioSummary",false)?:false,
        widgetBackground=entitlements?.optBoolean("widgetBackground",false)?:false,
        multipleCloudSlots=entitlements?.optBoolean("multipleCloudSlots",false)?:false
    )
}
private fun profileJson(profile:AccountProfile)=JSONObject().apply {
    put("role",profile.role);put("aiPoints",profile.aiPoints);put("aiTokenLimit",profile.aiTokenLimit);put("aiTokenUsed",profile.aiTokenUsed)
    put("audioSecondsLimit",profile.audioSecondsLimit);put("audioSecondsUsed",profile.audioSecondsUsed);put("audioBonusSeconds",profile.audioBonusSeconds);put("membershipExpiresAt",profile.membershipExpiresAt)
    put("entitlements",JSONObject().put("aiAssistant",profile.aiAssistant).put("audioSummary",profile.audioSummary).put("widgetBackground",profile.widgetBackground).put("multipleCloudSlots",profile.multipleCloudSlots))
}

fun cloudTimeLabel(value:String?):String?=value?.let {runCatching {Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).let {time->"${time.monthValue}/${time.dayOfMonth} %02d:%02d".format(time.hour,time.minute)}}.getOrNull()}
