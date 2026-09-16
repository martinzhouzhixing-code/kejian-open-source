package app.kejian.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI
import kotlin.math.sin

internal fun safeNoteUrl(value:String):Boolean=runCatching {
    val u=URI(value);val h=u.host.orEmpty().lowercase()
    u.scheme=="https"&&u.userInfo==null&&(u.port==-1||u.port==443)&&h.contains('.')&&!h.endsWith(".local")&&!h.endsWith(".internal")&&!h.endsWith(".localhost")&&!h.matches(Regex("[0-9.]+"))
}.getOrDefault(false)

@Composable internal fun NoteSourceLink(url:String){
    val handler=LocalUriHandler.current
    if(safeNoteUrl(url))TextButton(onClick={runCatching {handler.openUri(url)}}){
        Text(recordingCopy("查看来源 · ","Open source · ")+runCatching {URI(url).host}.getOrDefault(url),fontSize=12.sp)
    }
}

/** Whitelisted mathematical examples, never execute model code or load remote images. */
@Composable internal fun NoteMathFigure(kind:String){
    if(kind !in setOf("derivative","integral","sine"))return
    val ink=MaterialTheme.colorScheme.onSurface;val accent=Brand
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        Canvas(Modifier.fillMaxWidth().height(190.dp)){
            fun point(x:Float,y:Float)=Offset(size.width*(.1f+x/4f*.8f),size.height*(if(kind=="sine") .5f-y*.3f else .86f-y/5f*.76f))
            drawLine(ink.copy(alpha=.4f),point(0f,0f),point(4f,0f),2f)
            drawLine(ink.copy(alpha=.4f),point(0f,0f),point(0f,if(kind=="sine")1.35f else 5f),2f)
            val curve=Path()
            for(i in 0..160){val x=i/80f;val y=if(kind=="sine")sin(x*Math.PI).toFloat() else x*x;val p=point(x*1.65f,y);if(i==0)curve.moveTo(p.x,p.y)else curve.lineTo(p.x,p.y)}
            if(kind=="integral"){
                val fill=Path();fill.moveTo(point(0f,0f).x,point(0f,0f).y)
                for(i in 0..80){val x=i/80f;val p=point(x*1.65f,x*x);fill.lineTo(p.x,p.y)}
                fill.lineTo(point(1.65f,0f).x,point(1.65f,0f).y);fill.close();drawPath(fill,accent.copy(alpha=.24f))
            }
            drawPath(curve,accent,style=Stroke(3.dp.toPx()))
            if(kind=="derivative"){
                drawLine(ink,point(.5f*1.65f,0f),point(2f*1.65f,3f),2.dp.toPx())
                drawCircle(accent,5.dp.toPx(),point(1.65f,1f))
            }
        }
        Text(when(kind){"derivative"->"y = x² · x = 1 · y′ = 2 · y = 2x − 1";"integral"->"y = x² · ∫₀¹ x² dx = ⅓";else->"y = sin(x) · T = 2π"},fontSize=13.sp,color=Muted)
        Text(recordingCopy("示意图用于辅助理解，不代表老师的原题。","Illustrative example, not the teacher’s original problem."),fontSize=11.sp,color=Muted)
    }
}
