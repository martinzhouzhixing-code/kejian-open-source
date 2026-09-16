package app.kejian.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur

val LocalPageTopInset=staticCompositionLocalOf {0.dp}

/** Blur the complete photograph + content. Never insert a solid color behind the status bar. */
@Composable fun EdgeBlur(modifier:Modifier=Modifier,top:Boolean=false){
    val layers=LocalLiquidLayers.current
    val tint=Color(LocalAppPalette.current.background)
    Box(modifier.testTag(if(top)"top-edge-blur" else "bottom-edge-blur")){
        if(LocalGlassEnabled.current&&layers!=null&&android.os.Build.VERSION.SDK_INT>=31){
            val backdrop=rememberCombinedBackdrop(layers.landscape,layers.page)
            repeat(1){band->
                val feather=remember(top,band){
                    val stops=if(band==0)arrayOf(0f to 0f,.3f to .18f,.65f to .72f,1f to 1f)
                        else arrayOf(0f to 0f,.35f to 0f,.7f to .5f,1f to 1f)
                    Brush.verticalGradient(*stops.map{(p,a)->(if(top)1f-p else p) to Color.Black.copy(alpha=a)}.sortedBy{it.first}.toTypedArray())
                }
                Box(Modifier.matchParentSize().graphicsLayer {compositingStrategy=CompositingStrategy.Offscreen}
                    .drawWithContent {drawContent();drawRect(feather,blendMode=BlendMode.DstIn)}
                    .drawBackdrop(backdrop,shape={RectangleShape},effects={blur(14.dp.toPx())},highlight=null,shadow=null))
            }
        }
        if(!top&&LocalGlassEnabled.current)Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,tint.copy(alpha=.22f)))))
    }
}
