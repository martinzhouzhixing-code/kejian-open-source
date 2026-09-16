package app.kejian.mobile

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

@Composable fun LaunchOverlay(progress:Float,destination:Rect?) {
    val density=LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit){awaitPointerEventScope {while(true)awaitPointerEvent().changes.forEach {it.consume()}}}){
        val flight=FastOutSlowInEasing.transform(((progress-.28f)/.72f).coerceIn(0f,1f))
        val fade=((progress-.32f)/.46f).coerceIn(0f,1f)
        Box(Modifier.fillMaxSize().background(Bg.copy(alpha=1-fade)))
        val startSize=88.dp;val endSize=with(density){destination?.width?.toDp()}?:36.dp
        val side=startSize+(endSize-startSize)*flight
        val x=(maxWidth-startSize)/2;val y=(maxHeight-startSize)/2
        val endX=with(density){destination?.left?.toDp()}?:24.dp
        val endY=with(density){destination?.top?.toDp()}?:52.dp
        Image(painterResource(R.drawable.ic_launcher),if(AppLanguage.english)"Kejian launch icon"else"课间启动标志",Modifier.offset(x+(endX-x)*flight,y+(endY-y)*flight).size(side).graphicsLayer {alpha=(progress/.2f).coerceIn(0f,1f)})
    }
}

// The guide now lives on real application routes; see OnboardingGuide.kt.
