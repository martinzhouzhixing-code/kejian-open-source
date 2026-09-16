package app.kejian.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils

/** Shared hierarchy for page chrome; note documents retain their reading rhythm. */
object AppTextStyles {
    val pageTitle=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.SemiBold,letterSpacing=0.sp)
    val pageSubtitle=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=13.sp,lineHeight=20.sp,fontWeight=FontWeight.Normal,letterSpacing=0.sp)
    val sectionTitle=pageTitle.copy(fontSize=22.sp,lineHeight=30.sp)
}
val KejianTypography=Typography(
    headlineLarge=AppTextStyles.pageTitle,headlineMedium=AppTextStyles.pageTitle,headlineSmall=AppTextStyles.sectionTitle,
    titleLarge=AppTextStyles.sectionTitle.copy(fontSize=20.sp,lineHeight=28.sp),
    titleMedium=AppTextStyles.sectionTitle.copy(fontSize=16.sp,lineHeight=24.sp),
    titleSmall=AppTextStyles.sectionTitle.copy(fontSize=14.sp,lineHeight=20.sp),
    bodyLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=16.sp,lineHeight=24.sp),
    bodyMedium=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=22.sp),
    bodySmall=AppTextStyles.pageSubtitle)

fun validThemeHex(value:String):String?=value.trim().let {if(it.matches(Regex("#[0-9a-fA-F]{6}")))it.uppercase() else null}
fun themeSeed(value:String):Int=validThemeHex(value)?.drop(1)?.toLong(16)?.or(0xFF000000)?.toInt()?:0xFF245C9B.toInt()
fun AppPalette.withCustomTheme(settings:Settings?):AppPalette {
    if(settings?.customTheme!=true)return this
    val neutral=withSeed(themeSeed(settings.customSurface))
    val seed=themeSeed(settings.customAccent)
    // Keep chosen hue/saturation, but derive an accessible role for each appearance.
    val hsl=FloatArray(3);ColorUtils.colorToHSL(seed,hsl)
    hsl[2]=if(dark).78f else .36f
    val brand=ColorUtils.HSLToColor(hsl)
    val onBrand=if(ColorUtils.calculateContrast(0xFF000000.toInt(),brand)>ColorUtils.calculateContrast(0xFFFFFFFF.toInt(),brand))0xFF000000.toInt()else 0xFFFFFFFF.toInt()
    hsl[2]=if(dark).29f else .81f;hsl[1]*=.45f
    return neutral.copy(brand=brand,onBrand=onBrand,container=ColorUtils.HSLToColor(hsl))
}

@Composable fun CustomThemeControls(settings:Settings,onChange:(Settings)->Unit){
    val en=AppLanguage.english
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
            Text(if(en)"My custom theme" else "自定义主题",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            GlassSwitch(settings.customTheme,{onChange(settings.copy(customTheme=it))},modifier=Modifier.testTag("custom-theme-toggle"))
        }
        Text(if(en)"Built-in themes remain available. Your custom image and colors are remembered when switching themes." else "默认主题随时可切回；自定义图片和配色会单独保留。",style=AppTextStyles.pageSubtitle,color=Muted)
        if(settings.customTheme){
            ThemeColorEditor(if(en)"Buttons & selected icons" else "按钮与选中图标颜色",settings.customAccent,"custom-accent",preview={settings.copy(customAccent=it)}){onChange(settings.copy(customAccent=it))}
            ThemeColorEditor(if(en)"Cards & background tint" else "卡片与界面底色",settings.customSurface,"custom-surface",preview={settings.copy(customSurface=it)}){onChange(settings.copy(customSurface=it))}
            Text(if(en)"Light and dark variants preserve your colors with readable text contrast. Course category colors remain unchanged." else "自动生成对应的深浅色搭配与清晰文字；课程分类颜色保持不变。",color=Muted,style=AppTextStyles.pageSubtitle)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                for(dark in listOf(false,true)){
                    val p=(if(dark)AppPalettes.dark else AppPalettes.light).withCustomTheme(settings)
                    Column(Modifier.weight(1f).background(Color(p.surface),RoundedCornerShape(16.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        Text(if(dark){if(en)"Dark" else "深色"}else{if(en)"Light" else "浅色"},color=Color(p.ink),style=MaterialTheme.typography.titleMedium)
                        Text(if(en)"Aa · Preview" else "文字 · 预览",color=Color(p.muted),style=AppTextStyles.pageSubtitle)
                        Text(if(en)"Selected" else "选中状态",color=Color(p.onBrand),modifier=Modifier.background(Color(p.brand),RoundedCornerShape(8.dp)).padding(8.dp))
                    }
                }
            }
        }
    }
}

/** Transient preview never writes preferences on every pointer movement. */
object ThemeColorPreview {var settings by mutableStateOf<Settings?>(null)}

@Composable internal fun ThemeColorEditor(label:String,value:String,tag:String,preview:((String)->Settings)?=null,onSave:(String)->Unit){
    var open by remember {mutableStateOf(false)}
    Text(label,style=MaterialTheme.typography.titleMedium)
    GlassOutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().testTag(tag)){
        Box(Modifier.size(22.dp).background(Color(themeSeed(value)),RoundedCornerShape(50)))
        Spacer(Modifier.width(12.dp));Text(if(AppLanguage.english)"Choose color" else "选择颜色")
        Spacer(Modifier.weight(1f));Text(value)
    }
    if(open)ThemeColorDialog(label,value,preview=preview,onDismiss={open=false},onSave={onSave(it);open=false})
}
