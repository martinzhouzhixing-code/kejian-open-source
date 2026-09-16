package app.kejian.mobile

import kotlin.math.*

/** User-facing values are dp, independent of bitmap resolution and screen density. */
data class WidgetStyle(
    val cornerRadius:Float=24f,
    val top:Float=14f,
    val bottom:Float=14f,
    val left:Float=14f,
    val right:Float=14f
) {
    fun normalized():WidgetStyle {
        require(listOf(cornerRadius,top,bottom,left,right).all {it.isFinite()}) {"小组件外观数值无效"}
        return copy(cornerRadius=cornerRadius.coerceIn(0f,48f),top=top.coerceIn(0f,40f),bottom=bottom.coerceIn(0f,40f),left=left.coerceIn(0f,40f),right=right.coerceIn(0f,40f))
    }
}

data class WidgetInsets(val left:Int,val top:Int,val right:Int,val bottom:Int)

object WidgetGeometry {
    fun radius(style:WidgetStyle,width:Int,height:Int)=min(style.normalized().cornerRadius,min(width,height).coerceAtLeast(1)/2f)
    fun insets(style:WidgetStyle,width:Int,height:Int):WidgetInsets {
        val s=style.normalized();val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        val strip=h<105&&w>=95
        // A rectangle inset on both axes by r*(1-1/sqrt(2)) fits inside a round corner.
        val safe=ceil(radius(s,w,h)*(1f-1f/sqrt(2f))).toInt()
        fun fit(a:Float,b:Float,size:Int,minContent:Int):Pair<Int,Int> {
            val budget=max(2*safe,size-minContent.coerceAtMost(size)).coerceAtMost(size-1)
            val floor=min(safe,budget/2)
            val aa=max(a,floor.toFloat());val bb=max(b,floor.toFloat())
            if(aa+bb<=budget)return aa.roundToInt() to bb.roundToInt()
            val extra=(aa-floor)+(bb-floor)
            val first=if(extra==0f)floor else floor+((budget-2*floor)*(aa-floor)/extra).roundToInt()
            return first to budget-first
        }
        // A real 1×2 cell can be only ~64dp wide. Keep room for visible gutters first;
        // the clock renderer can then reduce its font size to fit the remaining 40dp.
        val horizontal=fit(s.left,s.right,w,if(strip)110 else if(w<130)40 else 92)
        val vertical=fit(s.top,s.bottom,h,if(strip)52 else 100)
        return WidgetInsets(horizontal.first,vertical.first,horizontal.second,vertical.second)
    }
}
