package app.kejian.mobile

/** Server-facing contract kept independent from any model vendor. */
sealed interface AiRecognitionState {
    data object Unconfigured:AiRecognitionState
    data class Uploading(val progress:Float):AiRecognitionState
    data object Analysing:AiRecognitionState
    data class Preview(val strictKj1:String,val courses:List<Course>):AiRecognitionState
    data class Failed(val message:String,val creditReturned:Boolean):AiRecognitionState
}

interface AiRecognitionGateway {
    suspend fun recognize(bytes:ByteArray,mimeType:String,fileName:String?):AiRecognitionState
    suspend fun confirmImport(jobId:String):Boolean
    suspend fun cancel(jobId:String)
}

/** Used until the server-side model API and payment callbacks are configured. */
object UnconfiguredAiRecognitionGateway:AiRecognitionGateway {
    override suspend fun recognize(bytes:ByteArray,mimeType:String,fileName:String?)=AiRecognitionState.Unconfigured
    override suspend fun confirmImport(jobId:String)=false
    override suspend fun cancel(jobId:String)=Unit
}
