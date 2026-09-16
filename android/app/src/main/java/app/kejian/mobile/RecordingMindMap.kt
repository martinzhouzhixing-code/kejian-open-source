package app.kejian.mobile

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import kotlin.math.max

internal data class MindMapBox(val node:MindMapNode,val x:Float,val y:Float,val width:Float,val height:Float,val lines:List<String>)
internal data class MindMapViewport(val zoom:Float,val pan:Offset)
internal val MindMapViewportKey=SemanticsPropertyKey<List<Float>>("MindMapViewport")

/** The camera belongs to the document, not the canvas's current measured height. */
@Stable internal class MindMapCameraState(initial:MindMapViewport?=null,initialWidth:Float=0f,initialHeight:Float=0f,initialOrientation:Int=0){
    var viewport by mutableStateOf(initial);private set
    var width=initialWidth;private set
    var height=initialHeight;private set
    var orientation=initialOrientation;private set
    fun resized(newWidth:Float,newHeight:Float,newOrientation:Int,fitted:MindMapViewport){
        if(newWidth<=0f||newHeight<=0f)return
        val old=viewport
        viewport=when {
            old==null->fitted
            // Rotation retains zoom and the world point at the visible center.
            // A details panel merely changes height: it must not move the map.
            orientation!=0&&orientation!=newOrientation->old.copy(pan=old.pan+Offset(newWidth-width,newHeight-height)/(2f*old.zoom))
            else->old
        }
        width=newWidth;height=newHeight;orientation=newOrientation
    }
    fun fit(value:MindMapViewport){viewport=value}
    fun transform(focus:Offset,delta:Offset,factor:Float,minimumZoom:Float){
        val old=viewport?:return
        val next=(old.zoom*factor).coerceIn(minimumZoom,2.5f)
        val worldFocus=focus/old.zoom-old.pan
        viewport=MindMapViewport(next,focus/next-worldFocus+delta/next)
    }
    companion object {
        val Saver=listSaver<MindMapCameraState,Any>(
            save={listOf(it.viewport?.zoom?:0f,it.viewport?.pan?.x?:0f,it.viewport?.pan?.y?:0f,it.width,it.height,it.orientation)},
            restore={values->MindMapCameraState((values[0] as Float).takeIf {it>0f}?.let {MindMapViewport(it,Offset(values[1] as Float,values[2] as Float))},values[3] as Float,values[4] as Float,values[5] as Int)}
        )
    }
}
internal fun fitMindMap(boxes:List<MindMapBox>,width:Float,height:Float,demo:Boolean=false):MindMapViewport{
    if(boxes.isEmpty()||width<=0f||height<=0f)return MindMapViewport(1f,Offset.Zero)
    val left=boxes.minOf {it.x};val top=boxes.minOf {it.y}
    val right=boxes.maxOf {it.x+it.width};val bottom=boxes.maxOf {it.y+it.height}
    // The walkthrough card occupies the top of the phone. Its immutable example uses the lower stage.
    val insetTop=if(demo)height*.32f else 20f
    val insetBottom=minOf(72f,height*.2f)
    val usableWidth=(width-32f).coerceAtLeast(1f)
    val usableHeight=(height-insetTop-insetBottom).coerceAtLeast(1f)
    val scale=minOf(usableWidth/(right-left).coerceAtLeast(1f),usableHeight/(bottom-top).coerceAtLeast(1f),1f).coerceAtLeast(.005f)
    val centeredX=16f+(usableWidth-(right-left)*scale)/2f
    val centeredY=insetTop+(usableHeight-(bottom-top)*scale)/2f
    return MindMapViewport(scale,Offset(centeredX/scale-left,centeredY/scale-top))
}

/** Lay out a rooted tree by each branch's true text height; siblings never overlap. */
internal fun layoutMindMap(map:LessonMindMap,wrap:(String)->List<String>):List<MindMapBox>{
    val children=map.nodes.groupBy {it.parentId}
    val lines=map.nodes.associate {it.id to wrap(it.label)}
    fun ownHeight(node:MindMapNode)=max(74f,38f+lines.getValue(node.id).size*21f)
    val heights=mutableMapOf<String,Float>()
    fun measure(node:MindMapNode):Float {
        val descendants=children[node.id].orEmpty()
        return max(ownHeight(node),descendants.sumOf {measure(it).toDouble()}.toFloat()+max(0,descendants.size-1)*28f).also {heights[node.id]=it}
    }
    val root=map.nodes.single {it.parentId==null};measure(root)
    val boxes=mutableListOf<MindMapBox>()
    fun place(node:MindMapNode,depth:Int,top:Float){
        val height=ownHeight(node);val branchHeight=heights.getValue(node.id)
        boxes+=MindMapBox(node,24f+depth*278f,top+(branchHeight-height)/2f,220f,height,lines.getValue(node.id))
        val childrenHere=children[node.id].orEmpty()
        val total=childrenHere.sumOf {heights.getValue(it.id).toDouble()}.toFloat()+max(0,childrenHere.size-1)*28f
        var childTop=top+(branchHeight-total)/2f
        childrenHere.forEach {child->place(child,depth+1,childTop);childTop+=heights.getValue(child.id)+28f}
    }
    place(root,0,24f);return boxes
}

@Composable fun RecordingMindMapScreen(model:KejianViewModel,noteId:String,onBack:()->Unit,demoNote:LessonNote?=null){
    val context=LocalContext.current
    val note=demoNote?:model.savedData.notes.firstOrNull {it.id==noteId}
    val revision=RecordingLibrary.revision
    val map=remember(note,revision){note?.let {if(demoNote!=null)demoRecordingMindMap(it)else RecordingLibrary.loadMap(context,it)}}
    RecordingMindMapContent(map,onBack,noteId,demoNote!=null)
}

@Composable internal fun RecordingMindMapContent(map:LessonMindMap?,onBack:()->Unit,viewportKey:String,demo:Boolean=false){
    var selected by remember(viewportKey,map){mutableStateOf<MindMapNode?>(null)}
    var listView by rememberSaveable(viewportKey){mutableStateOf(false)}
    // Hoisting keeps the camera alive while the accessible list replaces Canvas.
    val camera=rememberSaveable(viewportKey,map,saver=MindMapCameraState.Saver){MindMapCameraState()}
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(top=LocalPageTopInset.current).padding(horizontal=8.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onBack){Icon(Icons.Filled.ArrowBack,recordingCopy("返回","Back"))}
            Text(recordingCopy("思维导图","Mind map"),Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
            if(map!=null)IconButton(onClick={listView=!listView;selected=null},modifier=Modifier.testTag("mindmap-view-toggle")){Icon(if(listView)Icons.Filled.AccountTree else Icons.Filled.FormatListBulleted,if(listView)recordingCopy("显示导图","Show mind map")else recordingCopy("以可读列表查看所有节点","Read all nodes as a list"))}
        }
        if(map==null){
            Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text(recordingCopy("请先在录音笔记中生成思维导图","Create a mind map from your recording notes first."),fontSize=18.sp)
                Text(recordingCopy("笔记更新后，旧导图不会冒充最新结果。","When notes change, an older map is never shown as the current result."),color=Muted)
            }
        }else {
            Text(map.title,Modifier.padding(horizontal=20.dp,vertical=8.dp),fontSize=23.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold)
            Text(recordingCopy("拖动探索 · 双指缩放 · 点击节点查看细节","Drag to explore · pinch to zoom · tap a node for details"),Modifier.padding(horizontal=20.dp,vertical=4.dp),fontSize=12.sp,lineHeight=18.sp,color=Muted)
            var detailsHeight by remember {mutableStateOf(0.dp)}
            val panelDensity=LocalDensity.current
            Box(Modifier.weight(1f).fillMaxWidth()){
            if(listView)LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                items(map.nodes,key={it.id}){node->
                    MindMapPanel(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        node.parentId?.let {parent->map.nodes.firstOrNull {it.id==parent}?.let {Text(it.label,fontSize=12.sp,color=Brand)}}
                        Text(node.label,fontSize=17.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold)
                        if(node.details.isNotBlank())Text(node.details,fontSize=14.sp,lineHeight=24.sp,color=Muted)
                    }}
                }
            }else MindMapCanvas(map,camera,Modifier.fillMaxSize().onboardingTarget("record.mindmap.canvas"),demo=demo,controlsBottomInset=if(selected!=null)detailsHeight else 0.dp){selected=it}
            selected?.let {node->
                val shape=RoundedCornerShape(20.dp)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {detailsHeight=with(panelDensity){it.height.toDp()}}.padding(12.dp).heightIn(max=240.dp).clip(shape).testTag("mindmap-node-details")){
                    // Keep backdrop sampling separate from scrollable text and elevation layers.
                    Box(Modifier.matchParentSize().liveGlass(shape,SurfaceColor,LocalGlassLayers.current.timetable,radius=14.dp,tintAlpha=if(LocalAppPalette.current.dark).44f else .36f)
                        .background(if(LocalGlassEnabled.current)Color.Transparent else SurfaceColor).glassHighlight(shape))
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Text(node.label,Modifier.weight(1f),fontSize=17.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold)
                            IconButton(onClick={selected=null},modifier=Modifier.testTag("mindmap-close-details")){Icon(Icons.Filled.Close,recordingCopy("关闭节点详情","Close node details"))}
                        }
                        Text(node.details.ifBlank {recordingCopy("此节点没有额外说明，可查看相关子节点。","No additional details here. Explore its child nodes.")},fontSize=14.sp,lineHeight=24.sp,color=Muted)
                    }
                }
            }
        }
    }
}

}

@Composable private fun MindMapCanvas(map:LessonMindMap,camera:MindMapCameraState,modifier:Modifier,demo:Boolean=false,controlsBottomInset:Dp=0.dp,onSelect:(MindMapNode)->Unit){
    val density=LocalDensity.current.density
    val orientation=LocalConfiguration.current.orientation
    val select by rememberUpdatedState(onSelect)
    val textColor=Ink.toArgb();val nodeColor=SurfaceColor.toArgb();val rootColor=Mint.toArgb();val lineColor=Brand.copy(alpha=.3f).toArgb();val borderColor=Brand.toArgb()
    val paint=remember {Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=14f;typeface=Typeface.create(Typeface.DEFAULT,Typeface.NORMAL)}}
    val boxes=remember(map){layoutMindMap(map){text->wrapMindMapLabel(text,paint,186f)}}
    val byId=remember(boxes){boxes.associateBy {it.node.id}}
    BoxWithConstraints(modifier){
        val fitted=remember(boxes,maxWidth,maxHeight,demo){fitMindMap(boxes,maxWidth.value,maxHeight.value,demo)}
        LaunchedEffect(fitted,maxWidth,maxHeight,orientation){camera.resized(maxWidth.value,maxHeight.value,orientation,fitted)}
        val guide=LocalOnboardingTargets.current
        LaunchedEffect(demo,guide?.beat,fitted){
            if(demo&&guide?.scene==14){
                val target=if(guide.beat in 2..4)MindMapViewport(fitted.zoom*1.6f,fitted.pan+Offset(-80f,-30f))else fitted
                val start=camera.viewport?:fitted
                androidx.compose.animation.core.animate(0f,1f,animationSpec=androidx.compose.animation.core.tween(550)){v,_->camera.fit(MindMapViewport(start.zoom+(target.zoom-start.zoom)*v,start.pan+(target.pan-start.pan)*v))}
            }
        }
        val viewport=camera.viewport?:fitted
        val zoom=viewport.zoom;val pan=viewport.pan
        // A panel/rotation must not raise the gesture floor above a zoom the
        // user already chose, otherwise the next plain drag would snap it.
        val minimumZoom=minOf(.15f,fitted.zoom*.6f,zoom)
        val gestureMinimum by rememberUpdatedState(minimumZoom)
        val canvasCenter=Offset(maxWidth.value/2,maxHeight.value/2)
        Canvas(Modifier.fillMaxSize().clipToBounds().glassCapture(LocalGlassLayers.current.timetable)
            .testTag("mindmap-canvas").semantics {this[MindMapViewportKey]=listOf(zoom,pan.x,pan.y,density)}
            .pointerInput(map,density){detectTransformGestures {centroid,panDelta,zoomDelta,_->
                camera.transform(centroid/density,panDelta/density,zoomDelta,gestureMinimum)
            }}
            .pointerInput(boxes,density){detectTapGestures {position->
                val current=camera.viewport?:return@detectTapGestures
                val p=position/(density*current.zoom)-current.pan
                boxes.lastOrNull {p.x in it.x..it.x+it.width&&p.y in it.y..it.y+it.height}?.let {select(it.node)}
            }}){
            val canvas=drawContext.canvas.nativeCanvas
            canvas.save();canvas.scale(density*zoom,density*zoom);canvas.translate(pan.x,pan.y)
            paint.style=Paint.Style.STROKE;paint.color=lineColor;paint.strokeWidth=2f
            boxes.forEach {box->box.node.parentId?.let {id->byId[id]?.let {parent->
                val path=android.graphics.Path();val startX=parent.x+parent.width;val startY=parent.y+parent.height/2;val endY=box.y+box.height/2
                path.moveTo(startX,startY);path.cubicTo(startX+30f,startY,box.x-30f,endY,box.x,endY);canvas.drawPath(path,paint)
            }}}
            boxes.forEach {box->
                paint.style=Paint.Style.FILL;paint.color=if(box.node.parentId==null)rootColor else nodeColor
                canvas.drawRoundRect(box.x,box.y,box.x+box.width,box.y+box.height,15f,15f,paint)
                paint.style=Paint.Style.STROKE;paint.color=borderColor;paint.alpha=if(box.node.parentId==null)180 else 70;paint.strokeWidth=1f
                canvas.drawRoundRect(box.x,box.y,box.x+box.width,box.y+box.height,15f,15f,paint)
                paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=textColor;paint.textSize=14f
                box.lines.forEachIndexed {i,line->canvas.drawText(line,box.x+17f,box.y+28f+i*21f,paint)}
            }
            canvas.restore()
        }
        MindMapPanel(Modifier.align(Alignment.BottomEnd).padding(bottom=controlsBottomInset).padding(12.dp).testTag("mindmap-zoom-panel")){
            Row(verticalAlignment=Alignment.CenterVertically){
                IconButton(onClick={camera.transform(canvasCenter,Offset.Zero,1/1.2f,minimumZoom)},modifier=Modifier.testTag("mindmap-zoom-out")){Icon(Icons.Filled.Remove,recordingCopy("缩小","Zoom out"))}
                TextButton(onClick={camera.fit(fitted)},modifier=Modifier.testTag("mindmap-fit")){Text(recordingCopy("全图","Fit")+" ${(zoom*100).toInt()}%",fontSize=12.sp)}
                IconButton(onClick={camera.transform(canvasCenter,Offset.Zero,1.2f,minimumZoom)},modifier=Modifier.testTag("mindmap-zoom-in")){Icon(Icons.Filled.Add,recordingCopy("放大","Zoom in"))}
            }
        }
    }
}

@Composable private fun MindMapPanel(modifier:Modifier=Modifier,content:@Composable ()->Unit){
    val shape=RoundedCornerShape(18.dp)
    Box(modifier.clip(shape)){
        Box(Modifier.matchParentSize().liveGlass(shape,SurfaceColor,LocalGlassLayers.current.timetable,radius=14.dp,tintAlpha=if(LocalAppPalette.current.dark).44f else .36f)
            .background(if(LocalGlassEnabled.current)Color.Transparent else SurfaceColor).glassHighlight(shape))
        content()
    }
}

private fun wrapMindMapLabel(text:String,paint:Paint,maxWidth:Float):List<String>{
    val out=mutableListOf<String>();var line=""
    text.forEach {ch->
        if(ch=='\n'){out+=line;line=""}
        else if(paint.measureText(line+ch)>maxWidth&&line.isNotEmpty()){out+=line;line=ch.toString()}
        else line+=ch
    }
    if(line.isNotEmpty()||out.isEmpty())out+=line
    return out
}

private fun demoRecordingMindMap(note:LessonNote)=LessonMindMap(note.title,listOf(
    MindMapNode("root",null,note.title),
    MindMapNode("overview","root",recordingCopy("课程概要","Overview"),note.summary),
    MindMapNode("concepts","root",recordingCopy("核心知识","Key concepts"),note.keyPoints.joinToString("\n\n")),
    MindMapNode("actions","root",recordingCopy("作业与提醒","Assignments & reminders"),note.actionItems.joinToString("\n\n"))
),noteContentDigest(note))
