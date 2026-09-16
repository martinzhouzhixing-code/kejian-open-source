package app.kejian.mobile

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal data class WindowGlassFrame(val bitmap:Bitmap,val width:Int,val height:Int,val left:Int,val top:Int)
internal val LocalWindowGlassFrame=compositionLocalOf<State<WindowGlassFrame?>> { mutableStateOf(null) }

/** PixelCopy includes AndroidView content, which a Compose layer cannot export to another Window.
 * Capture the underlying Activity only (never its dialog); do not store or upload this image. */
@Composable internal fun rememberWindowGlassFrame():State<WindowGlassFrame?> {
    val activity=LocalActivity.current
    val parentView=LocalView.current
    val window=(parentView.parent as? DialogWindowProvider)?.window?:activity?.window
    val enabled=LocalGlassEnabled.current
    return produceState<WindowGlassFrame?>(null,window,enabled){
        if(window==null||!enabled)return@produceState
        val decor=window.decorView
        // A new window may not have a submitted buffer yet. Request the frame ourselves,
        // and retry only this short startup phase; idle dialogs never run a capture loop.
        repeat(3) {
            decor.postInvalidateOnAnimation()
            withFrameNanos {}
            withFrameNanos {}
            val w=decor.width;val h=decor.height
            if(w<=0||h<=0)return@repeat
            val location=IntArray(2);decor.getLocationOnScreen(location)
            val scale=minOf(1f,1024f/maxOf(w,h))
            val copy=Bitmap.createBitmap((w*scale).toInt().coerceAtLeast(1),(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            val result=suspendCancellableCoroutine<Int>{continuation->
                runCatching {PixelCopy.request(window,copy,{status->
                    if(continuation.isActive)continuation.resume(status)
                    else copy.recycle()
                },Handler(Looper.getMainLooper()))}.onFailure {
                    if(continuation.isActive)continuation.resume(PixelCopy.ERROR_SOURCE_INVALID)
                }
            }
            if(result==PixelCopy.SUCCESS){
                try {
                    val blurred=withContext(Dispatchers.Default){FrostedBitmap.create(copy,(18f*decor.resources.displayMetrics.density*scale).toInt(),1024)}
                    value=WindowGlassFrame(blurred,w,h,location[0],location[1])
                }finally {copy.recycle()}
                return@produceState
            }
            copy.recycle()
            if(result!=PixelCopy.ERROR_SOURCE_NO_DATA&&result!=PixelCopy.ERROR_TIMEOUT)return@produceState
        }
    }
}

@Composable internal fun Modifier.windowGlass(shape:Shape,tint:Color):Modifier {
    val frameState=LocalWindowGlassFrame.current
    val view=LocalView.current
    LaunchedEffect(frameState.value) {if(frameState.value!=null)view.postInvalidateOnAnimation()}
    val liquid=LocalLiquidEnabled.current
    val fallback=Color(LocalAppPalette.current.surface)
    var position by remember {mutableStateOf(Offset.Zero)}
    val lens=remember {if(android.os.Build.VERSION.SDK_INT>=33)CourseLiquidLens()else null}
    return clip(shape).onGloballyPositioned {position=it.positionOnScreen()}.drawWithContent {
        // Observe capture completion in the draw phase, including cached native layers.
        // No gesture or unrelated recomposition should be needed to reveal the backdrop.
        val frame=frameState.value
        if(frame!=null){
            val x=position.x-frame.left;val y=position.y-frame.top
            if(liquid&&lens!=null&&android.os.Build.VERSION.SDK_INT>=33){
                val outline=shape.createOutline(size,layoutDirection,this)
                val radius=(outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x?:0f
                val c=drawContext.canvas.nativeCanvas;c.save();c.translate(-x,-y)
                lens.draw(c,frame.bitmap,android.graphics.RectF(x,y,x+size.width,y+size.height),frame.width,frame.height,radius,density)
                c.restore()
            }else drawImage(frame.bitmap.asImageBitmap(),dstOffset=IntOffset(-x.toInt(),-y.toInt()),dstSize=IntSize(frame.width,frame.height),filterQuality=FilterQuality.Medium)
        }else drawRect(fallback) // Safe readable first frame, including secure-window capture failures.
        drawContent()
    }
}
