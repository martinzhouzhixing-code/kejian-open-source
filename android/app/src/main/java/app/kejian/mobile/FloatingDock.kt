package app.kejian.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bare, independently animated controls floating over a feathered screen edge. */
@Composable fun FloatingDock(screen:String,onNavigate:(String)->Unit){
    val entries=listOf(Triple("home","课表",R.drawable.ic_calendar),Triple("import_hub",if(AppLanguage.english)"Import" else "导入",R.drawable.ic_scan),
        Triple("record","录音",R.drawable.ic_mic),Triple("widgets","小组件",R.drawable.ic_widgets),Triple("settings","设置",R.drawable.ic_settings))
    val current=if(screen=="recognize")"import_hub" else screen
    val selected=entries.indexOfFirst {it.first==current}
    Box(Modifier.fillMaxWidth().testTag("floating-dock")){
        EdgeBlur(Modifier.fillMaxWidth().height(152.dp+WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        Row(Modifier.align(Alignment.BottomCenter).widthIn(max=460.dp).fillMaxWidth()
            .navigationBarsPadding().padding(horizontal=22.dp).padding(bottom=10.dp)
            .height(84.dp).selectableGroup()){
            entries.forEachIndexed {index,(route,label,icon)->
                val active=current==route
                val ink by animateColorAsState(if(active)Brand else Ink,tween(240),label="dockInk")
                val scale by animateFloatAsState(if(active)1.24f else 1f,
                    spring(dampingRatio=.88f,stiffness=420f),label="dockScale")
                val lift by animateDpAsState(if(active)(-3).dp else 0.dp,
                    spring(dampingRatio=.9f,stiffness=420f),label="dockLift")
                // Neighbours retain size; the spring preserves velocity during rapid retaps.
                val shift by animateDpAsState(when(index-selected){-1->(-5).dp;1->5.dp;else->0.dp},
                    spring(dampingRatio=.9f,stiffness=420f),label="dockSpacing")
                Column(Modifier.weight(1f).fillMaxHeight().testTag("dock-$route")
                    .selectable(selected=active,role=Role.Tab,
                        interactionSource=remember {MutableInteractionSource()},indication=null,
                        onClick={onNavigate(route)}),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(5.dp,Alignment.CenterVertically)){
                    Column(Modifier.offset(x=shift).testTag("dock-content-$route"),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Box(Modifier.offset(y=lift).size(42.dp),contentAlignment=Alignment.Center){
                        Icon(painterResource(icon),contentDescription=null,tint=ink,
                            modifier=Modifier.size(24.dp).graphicsLayer {scaleX=scale;scaleY=scale})
                    }
                    Text(label,color=ink,fontSize=10.sp,lineHeight=12.sp,maxLines=1)
                    }
                }
            }
        }
    }
}
