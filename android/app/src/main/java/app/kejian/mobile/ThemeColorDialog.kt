package app.kejian.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/** HSV wheel: angle selects hue, distance selects saturation, slider selects value. */
@Composable internal fun ThemeColorDialog(label:String,initial:String,preview:((String)->Settings)?=null,onDismiss:()->Unit,onSave:(String)->Unit){
    val hsv=remember {FloatArray(3).also {android.graphics.Color.colorToHSV(themeSeed(initial),it)}}
    var hue by remember {mutableFloatStateOf(hsv[0])}
    var saturation by remember {mutableFloatStateOf(hsv[1])}
    var brightness by remember {mutableFloatStateOf(hsv[2])}
    val color=Color.hsv(hue,saturation,brightness)
    val hex="#%06X".format(color.toArgb() and 0xFFFFFF)
    val currentPreview by rememberUpdatedState(preview)
    LaunchedEffect(hex){currentPreview?.let {ThemeColorPreview.settings=it(hex)}}
    DisposableEffect(Unit){onDispose {ThemeColorPreview.settings=null}}
    val en=AppLanguage.english
    GlassAlertDialog(onDismissRequest=onDismiss,title={Text(label)},
        confirmButton={GlassButton(onClick={onSave(hex)}){Text(if(en)"Use color" else "使用此颜色")}},
        dismissButton={TextButton(onClick=onDismiss){Text(if(en)"Cancel" else "取消")}},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            fun pick(offset:Offset,width:Int,height:Int){
                val dx=offset.x-width/2f;val dy=offset.y-height/2f
                hue=((atan2(dy,dx)*180f/PI.toFloat())+360f)%360f
                saturation=(hypot(dx,dy)/(minOf(width,height)*.46f)).coerceIn(0f,1f)
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).testTag("theme-color-wheel")
                .semantics {contentDescription=if(en)"Color wheel" else "色环"}
                .pointerInput(Unit){detectTapGestures {pick(it,size.width,size.height)}}
                .pointerInput(Unit){detectDragGestures(onDragStart={pick(it,size.width,size.height)}){change,_->change.consume();pick(change.position,size.width,size.height)}}){
                val radius=size.minDimension*.46f
                val rainbow=(0..12).map {Color.hsv((it%12)*30f,1f,1f)}
                drawCircle(Brush.sweepGradient(rainbow,center),radius)
                drawCircle(Brush.radialGradient(listOf(Color.White,Color.Transparent),center,radius),radius)
                drawCircle(Color.Black.copy(alpha=1f-brightness),radius)
                val angle=hue*PI.toFloat()/180f
                val position=center+Offset(cos(angle),sin(angle))*(radius*saturation)
                drawCircle(Color.Black.copy(alpha=.45f),10.dp.toPx(),position,style=Stroke(4.dp.toPx()))
                drawCircle(color,9.dp.toPx(),position)
                drawCircle(Color.White,10.dp.toPx(),position,style=Stroke(2.dp.toPx()))
            }
            Text(if(en)"Brightness" else "亮度",style=AppTextStyles.pageSubtitle)
            GlassSlider(brightness,{brightness=it},valueRange=.08f..1f,modifier=Modifier.semantics {contentDescription=if(en)"Color brightness" else "颜色亮度"})
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                GlassOutlinedButton(onClick={hue=(hue+350f)%360f},modifier=Modifier.weight(1f)){Text(if(en)"Hue −" else "色相 −")}
                GlassOutlinedButton(onClick={hue=(hue+10f)%360f},modifier=Modifier.weight(1f)){Text(if(en)"Hue +" else "色相 +")}
            }
            Row(Modifier.fillMaxWidth().background(color,RoundedCornerShape(16.dp)).padding(16.dp)){
                val ink=if(ColorUtils.calculateContrast(android.graphics.Color.BLACK,color.toArgb())>=4.5)Color.Black else Color.White
                Text(hex,color=ink);Spacer(Modifier.weight(1f));Text(if(en)"Live preview" else "实时预览",color=ink)
            }
        }})
}
