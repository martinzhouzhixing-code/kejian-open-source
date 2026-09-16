package app.kejian.mobile

import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun WidgetsScreen(data:AppData,refreshToken:Int,onPin:(Int)->Unit,onOpacity:(Float)->Unit={},onStyle:(WidgetStyle)->Unit={},onBackgroundMode:(String)->Unit={},onBackgroundTone:(Float)->Unit={},onTextMode:(String)->Unit={},onFrosted:(Boolean)->Unit={},onChooseBackground:()->Unit={}){
    val context=LocalContext.current;val palette=LocalAppPalette.current
    var kind by rememberSaveable {mutableStateOf("course")}
    var utilityRevision by remember {mutableIntStateOf(0)}
    var selected by rememberSaveable {mutableIntStateOf(1)}
    val guide=LocalOnboardingTargets.current
    LaunchedEffect(guide?.scene,guide?.beat){if(guide?.scene==15)selected=when(guide.beat){0,1->0;2,3->3;else->1}}
    val sizes=listOf("1 × 2","2 × 2","2 × 4","4 × 2","2 × 1")
    val dimensions=listOf(96 to 165,210 to 180,210 to 300,300 to 165,150 to 80)[selected]
    val width by animateDpAsState(dimensions.first.dp,spring(dampingRatio=.85f,stiffness=380f),label="widgetWidth")
    val height by animateDpAsState(dimensions.second.dp,spring(dampingRatio=.85f,stiffness=380f),label="widgetHeight")
    var opacity by remember(data.settings.widgetOpacity){mutableFloatStateOf(data.settings.widgetOpacity)}
    var tone by remember(data.settings.widgetBackgroundTone){mutableFloatStateOf(data.settings.widgetBackgroundTone)}
    var styleEditor by rememberSaveable {mutableStateOf(false)}
    var containerMode by remember {mutableStateOf(KejianWidgetInstall.compatibilityContainer(context))}
    fun copy(zh:String,en:String)=if(data.settings.language=="en")en else zh
    val unlocked=ProductAccess.supporterAppearance||guide?.activeKey!=null
    val effectiveMode=if(unlocked)data.settings.widgetBackgroundMode else "solid"
    val previewData=data.copy(settings=data.settings.copy(widgetOpacity=if(unlocked)opacity else 1f,widgetBackgroundMode=effectiveMode,widgetBackgroundTone=tone))
    val preview by produceState<RemoteViews?>(null,previewData,selected,refreshToken,palette.dark,kind,utilityRevision){value=withContext(Dispatchers.Default){KejianWidgets.render(context,previewData,dimensions.first,dimensions.second,tutorial=guide?.activeKey!=null,kind=kind)}}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top=LocalPageTopInset.current).padding(24.dp).padding(bottom=LocalDockInset.current),verticalArrangement=Arrangement.spacedBy(20.dp)){
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(copy("小组件","Widgets"),style=AppTextStyles.pageTitle);Text(copy("把日程、天气与灵感，留在桌面。","Your schedule, weather and inspiration at a glance."),color=Muted,style=AppTextStyles.pageSubtitle)}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("course" to copy("课程","Courses"),"quote" to copy("今日名句","Daily quote"),"weather" to copy("天气","Weather")).forEach {(key,label)->FilterChip(kind==key,{kind=key},label={Text(label)})}}
        Box(Modifier.onboardingTarget("widgets.preview").fillMaxWidth().height(330.dp).background(Brush.linearGradient(if(palette.dark)listOf(Color(0xFF213E35),Color(0xFF242539),Color(0xFF1B302C))else listOf(Color(0xFF8DAEAC),Color(0xFFCEC6DB),Color(0xFFD3DBCB))),RoundedCornerShape(28.dp)),contentAlignment=Alignment.Center){
            preview?.let {views->AndroidView(factory={FrameLayout(it)},update={host->host.removeAllViews();host.addView(views.apply(context,host))},modifier=Modifier.size(width,height))}
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){sizes.forEachIndexed {i,label->FilterChip(selected==i,{selected=i},label={Text(label)})}}
        if(unlocked)WhiteCard {
            Row(Modifier.onboardingTarget("widgets.background").fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(copy("会员背景","Member backgrounds"),style=MaterialTheme.typography.titleMedium);if(unlocked)Text(if(ProductAccess.role=="developer")"开发者" else "会员已开放",color=Brand,style=MaterialTheme.typography.labelMedium)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("solid" to "纯色","translucent" to "半透明","image" to "图片").forEach {(mode,label)->FilterChip(selected=effectiveMode==mode,onClick={onBackgroundMode(mode)},label={Text(label)},modifier=Modifier.weight(1f))}}
            if(effectiveMode=="translucent"){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("背景不透明度");Text("${(opacity*100).toInt()}%",color=Brand)}
                GlassSlider(value=opacity,onValueChange={opacity=it},valueRange=.35f..1f,onValueChangeFinished={onOpacity(opacity)})
                Text("只改变背景透明度，文字和彩色标签始终保持清晰。",color=Muted,style=MaterialTheme.typography.bodySmall)
            }
            if(effectiveMode=="image"){
                GlassOutlinedButton(onClick=onChooseBackground,modifier=Modifier.fillMaxWidth()){Text(if(data.settings.widgetBackgroundUri==null)"选择背景图片" else "更换背景图片")}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("背景明暗");Text(when{tone<-.05f->"调暗 ${(-tone*100).toInt()}%";tone>.05f->"调亮 ${(tone*100).toInt()}%";else->"原图"},color=Brand)}
                GlassSlider(value=tone,onValueChange={tone=it},valueRange=-1f..1f,onValueChangeFinished={onBackgroundTone(tone)})
                Text(copy("文字颜色","Text color"),style=MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("auto" to copy("自动","Auto"),"black" to copy("黑字","Black"),"white" to copy("白字","White")).forEach {(mode,label)->
                        FilterChip(selected=data.settings.widgetTextMode==mode,onClick={onTextMode(mode)},label={Text(label)},modifier=Modifier.weight(1f))
                    }
                }
                Text(copy("自动在本机分析图片明暗：亮图黑字，暗图白字。导入保留原图亮度，也可以手动选择。","Brightness is analyzed on-device. Bright images use black text; dark images use white. Import keeps the original brightness. You can override the choice."),color=Muted,style=MaterialTheme.typography.bodySmall)

                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(copy("磨砂玻璃","Frosted glass"));Text(copy("柔化图片纹理，不模糊课程文字","Soften the image; keep text sharp"),color=Muted,style=MaterialTheme.typography.bodySmall)};GlassSwitch(data.settings.widgetFrosted,onFrosted)}
                Text("图片只保存在本机；调整明暗可以让课程文字更容易辨认。",color=Muted,style=MaterialTheme.typography.bodySmall)
            }
        } else WhiteCard {
            Text(copy("Plus / Pro 外观权益","Plus / Pro appearance"),style=MaterialTheme.typography.titleMedium)
            Text("当前使用清晰纯色背景。圆角与文字边距仍可免费调整。",color=Muted)
            Text("半透明材质、自选背景图片和背景明暗调节属于支持者会员权益；圆角和文字边距始终免费。",color=Muted,style=MaterialTheme.typography.bodySmall)
            Text(copy("Plus ¥5.99 / 月起 · 详情见设置页","Plus from ¥5.99/month · See Settings"),color=Brand)
        }
        WhiteCard {
            Text("圆角与文字留白",style=MaterialTheme.typography.titleMedium)
            Text("圆角 ${data.settings.widgetStyle.cornerRadius.toInt()} dp · 上下左右独立调节",color=Muted)
            Text("让四角更自然，文字离边缘更从容。设置应用到所有已添加的小组件。",color=Muted,style=MaterialTheme.typography.bodySmall)
            GlassOutlinedButton(onClick={styleEditor=true},modifier=Modifier.fillMaxWidth()){Text("调整圆角与边距")}
        }
        PrimaryButton(if(kind=="course")"添加 ${sizes[selected]} 小组件" else if(kind=="quote")copy("添加今日名句","Add daily quote")else copy("添加天气小组件","Add weather widget"),{onPin(when(kind){"quote"->5;"weather"->6;else->selected})})
        if(kind!="course")Text(copy("从 2 × 2 开始，添加后可长按调整大小。上方尺寸展示为预览。","Starts at 2 × 2. Resize after adding; sizes above are previews."),color=Muted)
        UtilityWidgetSettings(onRefresh={utilityRevision++})
        Text("支持 1 × 2、2 × 1、2 × 2、2 × 4 和 4 × 2。实际格数由手机桌面网格决定，添加后可长按调整大小。添加成功后课间会明确提示。",color=Muted,style=MaterialTheme.typography.bodySmall)
        WhiteCard {
            Text("桌面列表找不到课间？",style=MaterialTheme.typography.titleMedium)
            Text("先打开一次课间，再回到桌面搜索“小组件”。小米等部分系统若启用了应用锁、手机分身或隐私空间，桌面可能隐藏第三方组件；把课间安装在主空间、关闭对应限制并重启桌面后再试。",color=Muted,style=MaterialTheme.typography.bodySmall)
            Text(copy("鸿蒙 4.x：双指捏合桌面 → 服务卡片 → 底部的窗口小工具。","HarmonyOS 4.x: pinch the Home screen → Service cards → Window widgets at the bottom."),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
        WhiteCard {
            Text(copy("卓易通 / 鸿蒙兼容性","Zhuoyitong / HarmonyOS compatibility"),style=MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(copy("我通过卓易通运行课间","I run Kejian through Zhuoyitong"),Modifier.weight(1f))
                GlassSwitch(checked=containerMode,onCheckedChange={containerMode=it;KejianWidgetInstall.setCompatibilityContainer(context,it)})
            }
            Text(copy("最新鸿蒙通过卓易通运行安卓应用时，安卓小组件不能直接添加到鸿蒙原生桌面。本版可在应用内预览，但暂不提供原生服务卡片；重新安装 APK 无法解决此限制。","When an Android app runs through Zhuoyitong on current HarmonyOS, its Android widgets cannot be added directly to the native Home screen. In-app previews remain available, but this APK does not provide native service cards. Reinstalling the APK will not remove this limitation."),color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
    if(styleEditor)WidgetStyleEditor(previewData,refreshToken,selected,onDismiss={styleEditor=false},onApply={onStyle(it);styleEditor=false})
}

@Composable internal fun WidgetStyleEditor(data:AppData,refreshToken:Int,selected:Int,onDismiss:()->Unit,onApply:(WidgetStyle)->Unit){
    val context=LocalContext.current;val palette=LocalAppPalette.current
    var style by remember {mutableStateOf(data.settings.widgetStyle)}
    val dimensions=listOf(96 to 165,210 to 180,210 to 300,300 to 165,150 to 80)[selected]
    val edited=data.copy(settings=data.settings.copy(widgetStyle=style))
    val preview by produceState<RemoteViews?>(null,edited,refreshToken){value=withContext(Dispatchers.Default){KejianWidgets.render(context,edited,dimensions.first,dimensions.second)}}
    val insets=WidgetGeometry.insets(style,dimensions.first,dimensions.second)
    GlassDialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        GlassSurface(Modifier.padding(horizontal=16.dp).fillMaxWidth().fillMaxHeight(.93f),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surface){
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Text("圆角与文字留白",style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick=onDismiss){Text("取消")}
                }
                BoxWithConstraints(Modifier.fillMaxWidth().height(170.dp).background(Brush.linearGradient(if(palette.dark)listOf(Color(0xFF213E35),Color(0xFF242539))else listOf(Color(0xFF8DAEAC),Color(0xFFCEC6DB))),RoundedCornerShape(20.dp)),contentAlignment=Alignment.Center){
                    val scale=minOf(maxWidth.value/dimensions.first,maxHeight.value/dimensions.second,1f)*.92f
                    preview?.let {views->AndroidView(factory={FrameLayout(it)},update={host->host.removeAllViews();host.addView(views.apply(context,host))},modifier=Modifier.requiredSize(dimensions.first.dp,dimensions.second.dp).graphicsLayer {scaleX=scale;scaleY=scale})}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    WidgetDimensionSlider("圆角大小",style.cornerRadius,48f,{style=style.copy(cornerRadius=it)})
                    HorizontalDivider(Modifier.padding(vertical=8.dp))
                    Text("文字内边距",style=MaterialTheme.typography.titleMedium)
                    WidgetDimensionSlider("上边距",style.top,40f,{style=style.copy(top=it)})
                    WidgetDimensionSlider("下边距",style.bottom,40f,{style=style.copy(bottom=it)})
                    WidgetDimensionSlider("左边距",style.left,40f,{style=style.copy(left=it)})
                    WidgetDimensionSlider("右边距",style.right,40f,{style=style.copy(right=it)})
                    Text("预览即时更新。保留圆角安全区；边距过大时按尺寸缩减，避免挤掉文字。",color=Muted,style=MaterialTheme.typography.bodySmall)
                    Text("当前尺寸实际留白：上 ${insets.top} / 下 ${insets.bottom} / 左 ${insets.left} / 右 ${insets.right} dp",color=Muted,style=MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    GlassOutlinedButton(onClick={style=WidgetStyle()},modifier=Modifier.weight(1f)){Text("恢复默认")}
                    GlassButton(onClick={onApply(style.normalized())},modifier=Modifier.weight(1.35f)){Text("应用到小组件")}
                }
            }
        }
    }
}

@Composable private fun WidgetDimensionSlider(label:String,value:Float,maximum:Float,onChange:(Float)->Unit){
    Column {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label);Text("${value.toInt()} dp",color=Brand)}
        GlassSlider(value=value,onValueChange={onChange(kotlin.math.round(it))},valueRange=0f..maximum,modifier=Modifier.semantics {contentDescription=label})
    }
}
