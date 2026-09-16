package app.kejian.mobile

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Shared by the real editor and tutorial; success is shown only after durable saving succeeds. */
@Composable fun EditSaveButton(editing:Boolean,onEdit:()->Unit,onSave:()->Boolean,modifier:Modifier=Modifier,saveRequest:Int=0){
    var confirming by remember {mutableStateOf(false)}
    var sequence by remember {mutableIntStateOf(0)}
    val textAlpha=remember {Animatable(1f)}
    val check=remember {Animatable(0f)}
    val checkAlpha=remember {Animatable(0f)}
    fun save(){if(!confirming&&onSave()){confirming=true;sequence++}}
    LaunchedEffect(saveRequest){if(saveRequest>0)save()}
    LaunchedEffect(sequence){
        if(sequence==0)return@LaunchedEffect
        if(ValueAnimator.areAnimatorsEnabled()){
            textAlpha.animateTo(0f,tween(100))
            delay(150) // Let the control settle into its browsing position before the stroke.
            checkAlpha.snapTo(1f)
            check.animateTo(1f,tween(360,easing=FastOutSlowInEasing))
            delay(320)
            checkAlpha.animateTo(0f,tween(140))
        }
        check.snapTo(0f);confirming=false
        textAlpha.animateTo(1f,tween(if(ValueAnimator.areAnimatorsEnabled())160 else 0))
    }
    val foreground=MaterialTheme.colorScheme.onPrimary
    GlassButton(onClick={if(!confirming){if(editing)save()else onEdit()}},
        modifier=modifier.width(76.dp).height(56.dp).semantics {if(confirming)stateDescription="课表已保存"},
        shape=RoundedCornerShape(28.dp),contentPadding=PaddingValues(0.dp)){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            Text(if(confirming||editing)"保存"else "编辑",Modifier.graphicsLayer {alpha=textAlpha.value})
            if(confirming)Canvas(Modifier.size(28.dp).graphicsLayer {alpha=checkAlpha.value}){
                val path=Path().apply {moveTo(size.width*.14f,size.height*.49f);lineTo(size.width*.39f,size.height*.74f);lineTo(size.width*.87f,size.height*.23f)}
                val measure=PathMeasure().apply {setPath(path,false)}
                val segment=Path();measure.getSegment(0f,measure.length*check.value,segment,true)
                drawPath(segment,foreground,style=Stroke(width=3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
            }
        }
    }
}
