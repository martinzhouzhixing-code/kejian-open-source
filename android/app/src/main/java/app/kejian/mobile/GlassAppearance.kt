package app.kejian.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppSkin(val id:String,val zh:String,val en:String,val seed:Int,val photo:Int)
val appSkins=listOf(
    AppSkin("forest","林间","Forest",0xFF256E54.toInt(),R.raw.skin_forest),
    AppSkin("ocean","海盐蓝","Ocean",0xFF245C9B.toInt(),R.raw.skin_ocean),
    AppSkin("dusk","暮光紫","Twilight",0xFF745195.toInt(),R.raw.skin_dusk),
    AppSkin("sunset","落日琥珀","Amber",0xFF945527.toInt(),R.raw.skin_dusk)
)
fun appSkin(id:String)=appSkins.firstOrNull {it.id==id}?:appSkins.first()
object TutorialAppearance {var skin by mutableStateOf<String?>(null);var mode by mutableStateOf<String?>(null)}
val LocalGlassEnabled=staticCompositionLocalOf {false}

/** Bounded, separable box blur: works on Android 8+, including RemoteViews. Never edits the source. */
object FrostedBitmap {
    fun create(source:Bitmap,radius:Int=9,maxDimension:Int=256):Bitmap {
        val scale=minOf(1f,maxDimension.coerceIn(64,1024).toFloat()/maxOf(source.width,source.height))
        val w=(source.width*scale).toInt().coerceAtLeast(1);val h=(source.height*scale).toInt().coerceAtLeast(1)
        val small=Bitmap.createScaledBitmap(source,w,h,true)
        var src=IntArray(w*h);var dst=IntArray(w*h);small.getPixels(src,0,w,0,0,w,h)
        if(small!==source)small.recycle()
        val r=radius.coerceIn(1,24);val n=r*2+1
        repeat(3){
            for(horizontal in listOf(true,false)){
                val outer=if(horizontal)h else w;val length=if(horizontal)w else h
                for(line in 0 until outer){
                    fun index(v:Int)=if(horizontal)line*w+v.coerceIn(0,w-1) else v.coerceIn(0,h-1)*w+line
                    var a=0;var red=0;var g=0;var b=0
                    fun add(c:Int,sign:Int){a+=(c ushr 24)*sign;red+=((c ushr 16)and 255)*sign;g+=((c ushr 8)and 255)*sign;b+=(c and 255)*sign}
                    for(k in -r..r)add(src[index(k)],1)
                    for(v in 0 until length){dst[index(v)]=((a/n)shl 24)or((red/n)shl 16)or((g/n)shl 8)or(b/n);add(src[index(v-r)],-1);add(src[index(v+r+1)],1)}
                }
                val swap=src;src=dst;dst=swap
            }
        }
        return Bitmap.createBitmap(src,w,h,Bitmap.Config.ARGB_8888)
    }
    private val cache=android.util.LruCache<Int,Bitmap>(3)
    @Synchronized fun skin(context:Context,id:Int):Bitmap {
        cache.get(id)?.let {return it}
        val original=BitmapFactory.decodeResource(context.resources,id,BitmapFactory.Options().apply {inSampleSize=4})
        return create(original,3).also {original.recycle();cache.put(id,it)}
    }
}

@Composable fun GlassAppBackground(skinId:String,enabled:Boolean,settings:Settings=Settings(),secondary:Boolean=false){
    val context=LocalContext.current;val palette=LocalAppPalette.current;val selected=appSkin(skinId)
    Box(Modifier.fillMaxSize().glassCapture(LocalGlassLayers.current.landscape).then(if(enabled&&secondary)Modifier.blur(8.dp)else Modifier).background(Color(palette.background))){
        if(enabled && settings.customTheme && settings.appBackgroundUri!=null) {
            val bitmap by produceState<Bitmap?>(null,settings.appBackgroundUri,settings.appBackgroundBlur){
                value=withContext(Dispatchers.Default){runCatching {
                    LocalMediaStore.readAppBackground(context,settings.appBackgroundUri)?.let {source->
                        if(settings.appBackgroundBlur<=.01f)source else FrostedBitmap.create(source,(settings.appBackgroundBlur*24).toInt()).also {source.recycle()}
                    }
                }.getOrNull()}
            }
            bitmap?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            val tone=settings.appBackgroundTone
            Box(Modifier.fillMaxSize().background((if(tone<0)Color.Black else Color.White).copy(alpha=kotlin.math.abs(tone)*.85f)))
        } else if(enabled)Crossfade(selected,animationSpec=tween(450),label="skinBackdrop"){skin->
            val bitmap by produceState<Bitmap?>(null,skin.photo){value=withContext(Dispatchers.Default){FrostedBitmap.skin(context,skin.photo)}}
            Box(Modifier.fillMaxSize()){
                bitmap?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
                Box(Modifier.fillMaxSize().background(Color(palette.background).copy(alpha=landscapeVeil(palette.dark))))
                Box(Modifier.fillMaxSize().background(Color(skin.seed).copy(alpha=.04f)))
            }
        }
        if(enabled&&secondary)Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=if(palette.dark).16f else .08f)))
    }
}

@Composable fun SkinChoices(settings:Settings,onChange:(Settings)->Unit){
    val en=AppLanguage.english
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val latest by rememberUpdatedState(settings);val update by rememberUpdatedState(onChange)
    var failure by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(false)}
    var licenses by remember {mutableStateOf<String?>(null)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
        if(uri!=null)scope.launch {loading=true;try {
            val local=withContext(Dispatchers.IO){LocalMediaStore.saveAppBackground(context,uri)}
            update(latest.copy(appBackgroundUri=local.toString(),customTheme=true,appBackgroundTone=0f))
        }catch(e:Exception){failure=if(en)"Could not open this image. Please choose another." else "无法读取图片，请选择另一张图片。"}finally{loading=false}}
    }
    licenses?.let {text->GlassAlertDialog(onDismissRequest={licenses=null},title={Text(if(en)"Open source & data sources" else "开源组件与数据来源")},text={Text(text)},confirmButton={TextButton({licenses=null}){Text(if(en)"Close" else "关闭")}})}
    failure?.let {message->GlassAlertDialog(onDismissRequest={failure=null},title={Text(if(en)"Image unavailable" else "图片不可用")},text={Text(message)},confirmButton={TextButton({failure=null}){Text(if(en)"OK" else "知道了")}})}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        AppearanceModeChoices(settings,onChange)
        Text(if(en)"Built-in themes" else "默认主题",style=MaterialTheme.typography.titleLarge)
        appSkins.chunked(2).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
            row.forEach {skin->
                val chosen=!settings.customTheme&&settings.skin==skin.id
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if(chosen)Mint else SurfaceColor).glassHighlight(RoundedCornerShape(18.dp)).clickable{onChange(settings.copy(skin=skin.id,customTheme=false))}.testTag("skin_${skin.id}")){
                    Box(Modifier.fillMaxWidth().height(84.dp)){
                        Image(painterResource(skin.photo),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(Color(skin.seed).copy(alpha=.24f)))
                    }
                    Text((if(en)skin.en else skin.zh)+if(chosen)" ✓"else "",Modifier.padding(12.dp),color=Ink)
                }
            }
        }}
        CustomThemeControls(settings,onChange)
        GlassOutlinedButton({picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))},enabled=!loading,modifier=Modifier.fillMaxWidth()) {Text(if(loading) {if(en)"Opening image…" else "正在处理图片…"} else {if(en)"Choose my background" else "从相册选择背景"})}
        if(settings.customTheme&&settings.appBackgroundUri!=null){
            var blur by remember(settings.appBackgroundBlur){mutableFloatStateOf(settings.appBackgroundBlur)}
            var tone by remember(settings.appBackgroundTone){mutableFloatStateOf(settings.appBackgroundTone)}
            Text((if(en)"Blur " else "模糊度 ")+"${(blur*100).toInt()}%")
            GlassSlider(blur,{blur=it},onValueChangeFinished={onChange(settings.copy(appBackgroundBlur=blur))})
            Text((if(en)"Brightness " else "明暗度 ")+"${(tone*100).toInt()}%")
            GlassSlider(tone,{tone=it},valueRange=-1f..1f,onValueChangeFinished={onChange(settings.copy(appBackgroundTone=tone))})
            Text(if(en)"0% keeps the original brightness. Only saved on this device. UI colors are set below; text contrast adapts to light and dark modes." else "0% 保留原图亮度。图片仅保存在本机；界面颜色可独立设置，文字对比度随深浅模式自动适配。",color=Muted,style=MaterialTheme.typography.bodySmall)
            TextButton({onChange(settings.copy(appBackgroundUri=null))}){Text(if(en)"Use built-in landscape" else "恢复内置背景")}
        }
        TextButton({licenses=context.assets.open("THIRD_PARTY_230.txt").bufferedReader().use {it.readText()}}){Text(if(en)"Open source & data sources" else "开源组件与数据来源")}
        Text(if(en)"Themes and custom images are kept when switching modes. Pure mode shows only solid UI colors." else "切换模式保留主题、自定义图片与配色；纯净模式只显示纯色界面，不显示背景图片。",color=Muted,style=MaterialTheme.typography.bodySmall)
    }
}

// All neutral roles share the landscape hue, rather than retaining blue-grey defaults.
fun AppPalette.withSkin(id:String):AppPalette = withSeed(appSkin(id).seed)
fun AppPalette.withSeed(seed:Int):AppPalette {
    val hsl=FloatArray(3);ColorUtils.colorToHSL(seed,hsl)
    fun tone(s:Float,l:Float)=ColorUtils.HSLToColor(floatArrayOf(hsl[0],s,l))
    return copy(background=tone(.16f,if(dark).19f else .82f),surface=tone(.14f,if(dark).23f else .95f),
        ink=tone(.22f,if(dark).96f else .12f),muted=tone(.12f,if(dark).77f else .24f),
        brand=if(dark)tone(.40f,.77f)else seed,onBrand=if(dark)tone(.30f,.13f)else 0xFFFFFFFF.toInt(),
        container=tone(.23f,if(dark).29f else .81f),line=tone(.12f,if(dark).39f else .68f),
        fills=if(dark)fills.map {ColorUtils.blendARGB(it,0xFFFFFFFF.toInt(),.055f)}else fills)
}
internal fun landscapeVeil(dark:Boolean)=.72f
internal const val courseGlassAlpha=.62f
