package app.kejian.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.chrisbanes.haze.*
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

class LiquidLayers(val landscape:LayerBackdrop,val page:LayerBackdrop,val timetable:LayerBackdrop)
val LocalLiquidLayers=staticCompositionLocalOf<LiquidLayers?> {null}
val LocalLiquidEnabled=staticCompositionLocalOf {false}
fun liquidGlassSupported(context:android.content.Context)=android.os.Build.VERSION.SDK_INT>=33 &&
    !(context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
@Composable private fun liquidPlane(state:HazeState):LayerBackdrop? {
    val haze=LocalGlassLayers.current;val liquid=LocalLiquidLayers.current?:return null
    return when(state){haze.page->liquid.page;haze.timetable->liquid.timetable;else->liquid.landscape}
}

/** Separate capture planes prevent a glass surface from recursively sampling itself. */
class GlassLayers {
    val landscape=HazeState()
    val page=HazeState()
    val timetable=HazeState()
}
val LocalGlassLayers=staticCompositionLocalOf { GlassLayers() }
val LocalGlassPopup=staticCompositionLocalOf { false }
val LocalDockInset=staticCompositionLocalOf { 0.dp }

/** A small, feathered glint. No perimeter border, bevel band or surface wash. */
fun Modifier.glassHighlight(shape:Shape,strength:Float=1f):Modifier=clip(shape).drawWithCache {
    val outline=shape.createOutline(size,layoutDirection,this)
    val light=Brush.radialGradient(
        0f to Color.White.copy(alpha=(.18f*strength).coerceIn(0f,1f)),
        .35f to Color.White.copy(alpha=(.07f*strength).coerceIn(0f,1f)),
        1f to Color.Transparent,
        center=Offset(size.width*.18f,0f),radius=minOf(26.dp.toPx(),size.width*.22f).coerceAtLeast(1f))
    onDrawWithContent {
        drawContent()
        drawOutline(outline,light,style=Stroke(.65.dp.toPx()))
    }
}

@Composable fun Modifier.glassCapture(state:HazeState):Modifier {
    if(!LocalGlassEnabled.current)return this
    val plane=liquidPlane(state)
    // API 31+ uses one capture pipeline for both blur and refraction, not Haze + Kyant.
    return if(android.os.Build.VERSION.SDK_INT>=31&&plane!=null)layerBackdrop(plane) else hazeSource(state)
}

@Composable fun Modifier.liveGlass(shape:Shape,tint:Color,state:HazeState=LocalGlassLayers.current.landscape,radius:Dp=18.dp,window:Boolean=false,tintAlpha:Float=if(LocalAppPalette.current.dark).80f else .44f):Modifier {
    if(!LocalGlassEnabled.current)return this
    if(window)return windowGlass(shape,tint)
    val background=Color(LocalAppPalette.current.background)
    val plane=liquidPlane(state)
    val liquid=LocalLiquidEnabled.current
    if(android.os.Build.VERSION.SDK_INT>=31&&plane!=null)return clip(shape).drawBackdrop(
        backdrop=if(state==LocalGlassLayers.current.timetable||state==LocalGlassLayers.current.page)com.kyant.backdrop.backdrops.rememberCombinedBackdrop(LocalLiquidLayers.current!!.landscape,plane)else plane,shape={shape},effects={blur((if(liquid)minOf(radius.value,6f)else radius.value).dp.toPx());if(liquid)lens(minOf(18.dp.toPx(),size.minDimension*.3f),26.dp.toPx(),depthEffect=true)},
        highlight=null,shadow=null,
        onDrawSurface={if(!window)drawRect(tint.copy(alpha=(tintAlpha*(if(liquid).53f else 1f)).coerceIn(0f,1f)))})
    return clip(shape).hazeEffect(state,style=HazeStyle(backgroundColor=background,
        tints=if(window)emptyList()else listOf(HazeTint(tint.copy(alpha=tintAlpha.coerceIn(0f,1f)))),
        blurRadius=radius,noiseFactor=0f,fallbackTint=HazeTint(tint.copy(alpha=if(LocalAppPalette.current.dark).95f else .72f)))) {
        // Android 8–11 uses Haze's bounded asynchronous RenderScript path; 12+ uses RenderEffect.
        blurEnabled=true
        inputScale=HazeInputScale.None
    }
}

@Composable fun GlassSurface(modifier:Modifier=Modifier,shape:Shape=MaterialTheme.shapes.medium,
    color:Color=SurfaceColor,contentColor:Color=contentColorFor(color),tonalElevation:Dp=0.dp,
    shadowElevation:Dp=0.dp,border:BorderStroke?=null,content:@Composable ()->Unit) {
    val glass=LocalGlassEnabled.current&&color.alpha>.12f
    val popup=LocalGlassPopup.current
    val state=if(popup)LocalGlassLayers.current.page else LocalGlassLayers.current.landscape
    androidx.compose.material3.Surface(
        modifier=if(glass)modifier.liveGlass(shape,color,state,window=popup).glassHighlight(shape)else modifier,
        shape=shape,color=if(glass)if(popup)color.copy(alpha=if(LocalLiquidEnabled.current).32f else if(LocalAppPalette.current.dark).80f else .68f)else Color.Transparent else color,
        contentColor=if(contentColor==Color.Unspecified)Ink else contentColor,tonalElevation=tonalElevation,shadowElevation=shadowElevation,
        border=border,content=content)
}

@Composable fun GlassSurface(onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,
    shape:Shape=MaterialTheme.shapes.medium,color:Color=SurfaceColor,contentColor:Color=contentColorFor(color),
    tonalElevation:Dp=0.dp,shadowElevation:Dp=0.dp,border:BorderStroke?=null,
    interactionSource:MutableInteractionSource?=null,content:@Composable ()->Unit) {
    val glass=LocalGlassEnabled.current&&color.alpha>.12f
    val popup=LocalGlassPopup.current
    val state=if(popup)LocalGlassLayers.current.page else LocalGlassLayers.current.landscape
    androidx.compose.material3.Surface(onClick=onClick,enabled=enabled,interactionSource=interactionSource,
        modifier=if(glass)modifier.liveGlass(shape,color,state,window=popup).glassHighlight(shape)else modifier,
        shape=shape,color=if(glass)if(popup)color.copy(alpha=if(LocalLiquidEnabled.current).32f else if(LocalAppPalette.current.dark).80f else .68f)else Color.Transparent else color,
        contentColor=if(contentColor==Color.Unspecified)Ink else contentColor,tonalElevation=tonalElevation,shadowElevation=shadowElevation,
        border=border,content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GlassAlertDialog(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,
    modifier:Modifier=Modifier,dismissButton:(@Composable ()->Unit)?=null,icon:(@Composable ()->Unit)?=null,
    title:(@Composable ()->Unit)?=null,text:(@Composable ()->Unit)?=null,
    shape:Shape=RoundedCornerShape(28.dp),containerColor:Color=SurfaceColor,
    properties:DialogProperties=DialogProperties()) {
    val glass=LocalGlassEnabled.current
    val windowFrame=rememberWindowGlassFrame()
    CompositionLocalProvider(LocalWindowGlassFrame provides windowFrame,LocalGlassPopup provides true,LocalContentColor provides Ink) {
        // Resolve the blur inside the dialog window so its sampling coordinates
        // belong to this window, not to the page that opened it.
        BasicAlertDialog(onDismissRequest=onDismissRequest,modifier=modifier,properties=properties){
            val view=LocalView.current
            SideEffect {(view.parent as? DialogWindowProvider)?.window?.setDimAmount(.20f)}
            Column(Modifier.fillMaxWidth().testTag("glass-dialog").semantics {stateDescription=if(windowFrame.value!=null)"背景就绪" else "背景准备中"}.liveGlass(shape,containerColor,LocalGlassLayers.current.page,16.dp,window=true)
                .clip(shape).background(containerColor.copy(alpha=if(glass)if(LocalLiquidEnabled.current).44f else if(LocalAppPalette.current.dark).62f else .52f else 1f))
                .glassHighlight(shape).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                icon?.let {Box(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally)){it()}}
                title?.let {ProvideTextStyle(MaterialTheme.typography.headlineSmall){it()}}
                text?.let {Box(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())){ProvideTextStyle(MaterialTheme.typography.bodyMedium){it()}}}
                Row(Modifier.align(androidx.compose.ui.Alignment.End),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    dismissButton?.invoke();confirmButton()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GlassModalBottomSheet(onDismissRequest:()->Unit,modifier:Modifier=Modifier,
    sheetState:SheetState=rememberModalBottomSheetState(),shape:Shape=RoundedCornerShape(topStart=28.dp,topEnd=28.dp),
    containerColor:Color=SurfaceColor,contentColor:Color=Ink,scrimColor:Color=Color.Black.copy(alpha=.32f),
    content:@Composable ColumnScope.()->Unit) {
    val glass=LocalGlassEnabled.current
    val windowFrame=rememberWindowGlassFrame()
    CompositionLocalProvider(LocalWindowGlassFrame provides windowFrame,LocalGlassPopup provides true) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest=onDismissRequest,sheetState=sheetState,
            modifier=modifier,shape=shape,
            containerColor=if(glass)Color.Transparent else containerColor,contentColor=contentColor,
            tonalElevation=0.dp,scrimColor=scrimColor,dragHandle=null){
                Column(Modifier.fillMaxWidth().semantics {stateDescription=if(windowFrame.value!=null)"背景就绪" else "背景准备中"}.liveGlass(shape,containerColor,LocalGlassLayers.current.page,24.dp,window=true).glassHighlight(shape).background(containerColor.copy(alpha=if(glass)if(LocalLiquidEnabled.current).30f else if(LocalAppPalette.current.dark).85f else .72f else 1f))){
                    BottomSheetDefaults.DragHandle(modifier=Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally))
                    content()
                }
            }
    }
}

@Composable fun GlassDialog(onDismissRequest:()->Unit,properties:DialogProperties=DialogProperties(),content:@Composable ()->Unit){
    val windowFrame=rememberWindowGlassFrame()
    CompositionLocalProvider(LocalWindowGlassFrame provides windowFrame,LocalGlassPopup provides true){
        androidx.compose.ui.window.Dialog(onDismissRequest=onDismissRequest,properties=properties,content=content)
    }
}
