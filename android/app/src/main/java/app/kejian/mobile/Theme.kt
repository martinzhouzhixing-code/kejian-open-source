package app.kejian.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/** Shared semantic palette for Compose, Canvas and RemoteViews. */
data class AppPalette(val dark:Boolean,val background:Int,val surface:Int,val ink:Int,val muted:Int,val brand:Int,val onBrand:Int,val container:Int,val line:Int,val danger:Int,val fills:List<Int>,val courseInk:List<Int>,val accents:List<Int>)
private fun hex(value:String)=AndroidColor.parseColor(value)
object AppPalettes {
    val light=AppPalette(false,hex("#F6F8F5"),hex("#FFFFFF"),hex("#172F2A"),hex("#5C7068"),hex("#287464"),hex("#FFFFFF"),hex("#DDEFE4"),hex("#CFDCD4"),hex("#B33D36"),
        listOf("#DDEFE4","#E2EBF9","#F7E8D7","#EAE5F5","#F8DEE1","#DDF1F1","#F5E4C3","#E2EDD5","#E7E3DC","#DCE7F3","#F1E0F0","#E5E8C9").map(::hex),
        listOf("#172F2A","#3D5D86","#805B38","#695587","#8B4650","#2F6E70","#7A5C22","#526C32","#625C55","#3E6282","#744D73","#5E672B").map(::hex),
        listOf("#357D5D","#4D73A0","#9B6B3F","#8264A0","#A95562","#3F8588","#A77B2D","#6F8B42","#7A7168","#52799A","#8D608D","#78833A").map(::hex))
    val dark=AppPalette(true,hex("#101A18"),hex("#1B2925"),hex("#E7F0EA"),hex("#ABBCB3"),hex("#9BD8BA"),hex("#0A3528"),hex("#29483B"),hex("#465B50"),hex("#FFB4AB"),
        listOf("#244638","#293C56","#4B392C","#40374F","#503238","#244648","#4A3D24","#34462A","#403D39","#293F54","#49364A","#3F4428").map(::hex),
        listOf("#D2EFDD","#CFE2FF","#F9DFC4","#E5DAFC","#FFD9DE","#C8F0F1","#F5DC9C","#D9EDBC","#E7DFD7","#D2E5FA","#F2D7F0","#E4E9B6").map(::hex),
        listOf("#B0E9C8","#ACCFFF","#ECC092","#CFBAF0","#F1AAB4","#9DDBDE","#E3C16B","#BCDA8F","#D0C4B9","#A9CBEA","#DDB0DA","#CBD77D").map(::hex))
    fun forContext(context:Context,mode:String,skin:String="forest")=(if(isDark(mode,context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES))dark else light).withSkin(skin)
    fun isDark(mode:String,systemDark:Boolean)=when(mode){"dark"->true;"light"->false;else->systemDark}
}
val LocalAppPalette=staticCompositionLocalOf { AppPalettes.light }
val Bg:Color @Composable get()=MaterialTheme.colorScheme.background.copy(alpha=if(LocalGlassEnabled.current).14f else 1f)
val SurfaceColor:Color @Composable get()=MaterialTheme.colorScheme.surface.copy(alpha=if(LocalGlassEnabled.current).78f else 1f)
val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
val Brand:Color @Composable get()=MaterialTheme.colorScheme.primary
val OnBrand:Color @Composable get()=MaterialTheme.colorScheme.onPrimary
val Mint:Color @Composable get()=MaterialTheme.colorScheme.primaryContainer
val CourseColors:List<Color> @Composable get()=LocalAppPalette.current.fills.map { Color(it) }

@Composable fun KejianTheme(mode:String="system",skin:String="forest",glass:Boolean=true,liquid:Boolean=false,settings:Settings?=null,content:@Composable ()->Unit){
    val liquidLandscape=com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val liquidPage=com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val liquidTimetable=com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val liquidLayers=remember(liquidLandscape,liquidPage,liquidTimetable){LiquidLayers(liquidLandscape,liquidPage,liquidTimetable)}
    val supported=liquidGlassSupported(LocalContext.current)
    val layers=remember {GlassLayers()};val dark=AppPalettes.isDark(mode,isSystemInDarkTheme());val palette=(if(dark)AppPalettes.dark else AppPalettes.light).withSkin(skin).withCustomTheme(ThemeColorPreview.settings?:settings)
    @Composable fun animated(value:Int):Color=animateColorAsState(Color(value),tween(240),label="themeColor").value
    val background=animated(palette.background);val surface=animated(palette.surface);val ink=animated(palette.ink);val muted=animated(palette.muted);val brand=animated(palette.brand);val onBrand=animated(palette.onBrand);val container=animated(palette.container);val line=animated(palette.line);val danger=animated(palette.danger)
    val base=if(dark)darkColorScheme()else lightColorScheme()
    val scheme=base.copy(primary=brand,onPrimary=onBrand,primaryContainer=container,onPrimaryContainer=ink,secondary=brand,onSecondary=onBrand,secondaryContainer=container,onSecondaryContainer=ink,tertiary=brand,onTertiary=onBrand,tertiaryContainer=container,onTertiaryContainer=ink,surfaceTint=brand,background=background,onBackground=ink,surface=surface,onSurface=ink,surfaceVariant=container,onSurfaceVariant=muted,outline=line,outlineVariant=line.copy(alpha=.65f),error=danger,onError=if(dark)Color(0xFF601410)else Color.White,inverseSurface=ink,inverseOnSurface=surface,inversePrimary=if(dark)Color(AppPalettes.light.withSkin(skin).withCustomTheme(ThemeColorPreview.settings?:settings).brand)else Color(AppPalettes.dark.withSkin(skin).withCustomTheme(ThemeColorPreview.settings?:settings).brand),surfaceContainer=surface,surfaceContainerLow=surface,surfaceContainerHigh=container,surfaceContainerHighest=container,surfaceContainerLowest=background,surfaceBright=surface,surfaceDim=background)
    val activity=LocalActivity.current
    SideEffect {activity?.let {if(android.os.Build.VERSION.SDK_INT>=29){it.window.isStatusBarContrastEnforced=false};it.window.statusBarColor=android.graphics.Color.TRANSPARENT;WindowCompat.getInsetsController(it.window,it.window.decorView).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}}}
    CompositionLocalProvider(LocalLiquidLayers provides liquidLayers,LocalLiquidEnabled provides (liquid&&glass&&supported),LocalAppPalette provides palette,LocalGlassEnabled provides glass,LocalGlassLayers provides layers){MaterialTheme(colorScheme=scheme,typography=KejianTypography){CompositionLocalProvider(LocalContentColor provides ink,content=content)}}
}
