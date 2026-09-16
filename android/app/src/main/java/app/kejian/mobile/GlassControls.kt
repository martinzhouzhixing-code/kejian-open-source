package app.kejian.mobile

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp

/** Controls sample the timetable when overlaid on it, never their own captured page. */
val LocalControlOnTimetable=staticCompositionLocalOf {false}
@Composable private fun Modifier.controlGlass(shape:Shape,tint:Color,alpha:Float):Modifier {
    val state=when {LocalControlOnTimetable.current->LocalGlassLayers.current.timetable;LocalGlassPopup.current->LocalGlassLayers.current.page;else->LocalGlassLayers.current.landscape}
    return liveGlass(shape,tint,state,radius=6.dp,window=LocalGlassPopup.current,tintAlpha=alpha).glassHighlight(shape)
}

@Composable fun GlassButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=ButtonDefaults.shape,colors:ButtonColors=ButtonDefaults.buttonColors(),
    elevation:ButtonElevation?=ButtonDefaults.buttonElevation(),border:BorderStroke?=null,
    contentPadding:PaddingValues=ButtonDefaults.ContentPadding,interactionSource:MutableInteractionSource?=null,
    content:@Composable RowScope.()->Unit){
    val liquid=LocalLiquidEnabled.current
    val source=interactionSource?:remember {MutableInteractionSource()}
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if(pressed&&liquid).96f else 1f,spring(.72f,480f),label="glassButtonPress")
    val tint=if(enabled)colors.containerColor else colors.disabledContainerColor
    val foreground=if(enabled)colors.contentColor else colors.disabledContentColor
    if(!liquid){
        androidx.compose.material3.Button(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,source,content)
        return
    }
    // The visible surface and its sample share exact bounds. Material Button's separate
    // minimum touch target used to create a second, larger refractive capsule behind it.
    CompositionLocalProvider(LocalContentColor provides foreground){
        ProvideTextStyle(MaterialTheme.typography.labelLarge){
            Row(modifier.defaultMinSize(minWidth=58.dp,minHeight=48.dp)
                .graphicsLayer {scaleX=scale;scaleY=scale}.clip(shape)
                .controlGlass(shape,tint,0f)
                .background(tint.copy(alpha=if(enabled).62f else .42f),shape)
                .clickable(interactionSource=source,indication=null,enabled=enabled,role=Role.Button,onClick=onClick)
                .padding(contentPadding),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically,
                content=content)
        }
    }
}
@Composable fun GlassOutlinedButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=ButtonDefaults.outlinedShape,colors:ButtonColors=ButtonDefaults.outlinedButtonColors(),
    elevation:ButtonElevation?=null,border:BorderStroke?=ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding:PaddingValues=ButtonDefaults.ContentPadding,interactionSource:MutableInteractionSource?=null,
    content:@Composable RowScope.()->Unit){
    if(!LocalLiquidEnabled.current)androidx.compose.material3.OutlinedButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)
    else GlassButton(onClick,modifier,enabled,shape,colors.copy(containerColor=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface),elevation,null,contentPadding,interactionSource,content)
}
@Composable fun GlassFilledTonalButton(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=ButtonDefaults.filledTonalShape,colors:ButtonColors=ButtonDefaults.filledTonalButtonColors(),
    elevation:ButtonElevation?=ButtonDefaults.filledTonalButtonElevation(),border:BorderStroke?=null,
    contentPadding:PaddingValues=ButtonDefaults.ContentPadding,interactionSource:MutableInteractionSource?=null,
    content:@Composable RowScope.()->Unit)=GlassButton(onClick,modifier,enabled,shape,colors,elevation,border,contentPadding,interactionSource,content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GlassSlider(value:Float,onValueChange:(Float)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    valueRange:ClosedFloatingPointRange<Float> =0f..1f,steps:Int=0,onValueChangeFinished:(()->Unit)?=null,
    colors:SliderColors=SliderDefaults.colors(),interactionSource:MutableInteractionSource=remember {MutableInteractionSource()}){
    if(!LocalLiquidEnabled.current){androidx.compose.material3.Slider(value,onValueChange,modifier,enabled,valueRange,steps,onValueChangeFinished,colors,interactionSource);return}
    val dragging by interactionSource.collectIsDraggedAsState();val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if(dragging||pressed)1.18f else 1f,spring(.7f,450f),label="liquidThumb")
    val shape=RoundedCornerShape(50)
    androidx.compose.material3.Slider(value=value,onValueChange=onValueChange,modifier=modifier,enabled=enabled,
        valueRange=valueRange,steps=steps,onValueChangeFinished=onValueChangeFinished,interactionSource=interactionSource,
        thumb={Box(Modifier.size(34.dp,28.dp).graphicsLayer {scaleX=scale;scaleY=scale}.controlGlass(shape,Color.White,.32f)
            .background(Color.White.copy(alpha=if(enabled).48f else .2f),shape))},
        track={state->
            val fraction=((state.value-valueRange.start)/(valueRange.endInclusive-valueRange.start)).coerceIn(0f,1f)
            Box(Modifier.fillMaxWidth().height(10.dp).controlGlass(shape,MaterialTheme.colorScheme.surface,.65f)){
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Brand.copy(alpha=if(enabled).65f else .2f),shape))
            }
        })
}

@Composable fun GlassSwitch(checked:Boolean,onCheckedChange:((Boolean)->Unit)?,modifier:Modifier=Modifier,
    thumbContent:(@Composable ()->Unit)?=null,enabled:Boolean=true,colors:SwitchColors=SwitchDefaults.colors(),
    interactionSource:MutableInteractionSource?=null){
    if(!LocalLiquidEnabled.current){
        androidx.compose.material3.Switch(checked,onCheckedChange,modifier,thumbContent,enabled,colors,interactionSource)
        return
    }
    val source=interactionSource?:remember {MutableInteractionSource()}
    val pressed by source.collectIsPressedAsState()
    val position by animateDpAsState(if(checked)24.dp else 4.dp,spring(.82f,480f),label="glassSwitchPosition")
    val scale by animateFloatAsState(if(pressed&&enabled).96f else 1f,spring(.8f,480f),label="glassSwitchPress")
    val track=if(enabled)if(checked)colors.checkedTrackColor else colors.uncheckedTrackColor
        else if(checked)colors.disabledCheckedTrackColor else colors.disabledUncheckedTrackColor
    val shape=RoundedCornerShape(50)
    // Keep the 48 dp accessible hit target invisible; only the 32 dp track is glass.
    val input=if(onCheckedChange!=null)Modifier.toggleable(checked,source,null,enabled,Role.Switch,onCheckedChange)else Modifier
    Box(modifier.defaultMinSize(minWidth=52.dp,minHeight=48.dp).then(input),contentAlignment=Alignment.Center){
        Box(Modifier.size(52.dp,32.dp).graphicsLayer {scaleX=scale;scaleY=scale}.clip(shape)
            .controlGlass(shape,track,0f).background(track.copy(alpha=if(enabled).56f else .3f),shape)){
            Box(Modifier.align(Alignment.CenterStart).offset(x=position).size(24.dp)
                .background(Color.White.copy(alpha=if(enabled).9f else .45f),shape),contentAlignment=Alignment.Center){thumbContent?.invoke()}
        }
    }
}
