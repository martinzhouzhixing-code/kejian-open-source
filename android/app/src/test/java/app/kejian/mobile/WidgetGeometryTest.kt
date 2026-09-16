package app.kejian.mobile

import org.junit.Test
import org.junit.Assert.*

class WidgetGeometryTest {
    @Test fun normalSizeKeepsIndependentMargins(){
        assertEquals(WidgetInsets(18,20,22,24),WidgetGeometry.insets(WidgetStyle(top=20f,bottom=24f,left=18f,right=22f),210,180))
    }
    @Test fun smallWidgetsKeepReadableSpaceWhenMarginsAreLarge(){
        for((w,h)in listOf(56 to 110,70 to 180,96 to 165,150 to 80,210 to 180,210 to 300,300 to 165)){
            for(radius in 0..48 step 4)for(padding in 0..40 step 4){
                val s=WidgetStyle(radius.toFloat(),padding.toFloat(),padding.toFloat(),padding.toFloat(),padding.toFloat())
                val p=WidgetGeometry.insets(s,w,h)
                assertTrue(p.left+p.right<w);assertTrue(p.top+p.bottom<h)
                val r=WidgetGeometry.radius(s,w,h)
                // Actual content corners stay within the visible rounded material.
                for(x in listOf(p.left,p.right))for(y in listOf(p.top,p.bottom)){
                    if(x<r&&y<r)assertTrue("$w/$h r=$r insets=$p",(r-x)*(r-x)+(r-y)*(r-y)<=r*r+.01f)
                }
            }
        }
    }
    @Test fun zeroRadiusAllowsSquareCornersAndZeroInsets(){
        val s=WidgetStyle(0f,0f,0f,0f,0f)
        assertEquals(0f,WidgetGeometry.radius(s,70,180),0f)
        assertEquals(WidgetInsets(0,0,0,0),WidgetGeometry.insets(s,70,180))
    }
    @Test fun radiusClampsToHalfShortSide(){assertEquals(28f,WidgetGeometry.radius(WidgetStyle(cornerRadius=48f),56,180),0f)}
    @Test(expected=IllegalArgumentException::class)fun nonFiniteStylesAreRejected(){WidgetStyle(left=Float.NaN).normalized()}
}
