package app.kejian.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable fun AppearanceModeChoices(settings:Settings,onChange:(Settings)->Unit){
    val en=AppLanguage.english
    val supported=liquidGlassSupported(LocalContext.current)
    Text(if(en)"Rendering mode" else "显示模式",style=MaterialTheme.typography.titleLarge)
    Column(Modifier.fillMaxWidth().selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        AppearanceMode.entries.forEach {mode->
            val chosen=settings.appearanceMode==mode
            val title=when(mode){AppearanceMode.Pure->if(en)"Pure" else "纯净模式";AppearanceMode.Blur->if(en)"Blur" else "模糊模式";AppearanceMode.Liquid->if(en)"Liquid glass" else "液态玻璃模式"}
            val description=when(mode){
                AppearanceMode.Pure->if(en)"Solid colors, no background blur or refraction" else "纯色界面，不使用背景模糊与折射，负载最低"
                AppearanceMode.Blur->if(en)"Blurred translucent surfaces, without refraction" else "模糊、半透明外观，不使用液态折射"
                AppearanceMode.Liquid->if(en)"Blur and refraction; uses more graphics power" else "模糊与光线折射，图形负载较高"
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if(chosen)Mint else SurfaceColor)
                .testTag("appearance-mode-${mode.name}").selectable(chosen,enabled=mode!=AppearanceMode.Liquid||supported,role=Role.RadioButton,onClick={onChange(settings.withAppearanceMode(mode))})
                .padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                RadioButton(chosen,onClick=null,enabled=mode!=AppearanceMode.Liquid||supported)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                    Text(title,color=Ink,style=MaterialTheme.typography.titleSmall)
                    Text(description,color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if(!supported)Text(if(en)"Liquid refraction is unavailable on this device; blur mode remains available." else "当前设备不支持液态折射，可选择模糊模式。",color=Muted,style=MaterialTheme.typography.bodySmall)
}
