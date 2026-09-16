/*
   Copyright 2025 Kyant

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package app.kejian.mobile

import android.graphics.*
import androidx.annotation.RequiresApi

internal object CourseGlassMaterial {const val blurDp=8f;const val captureLimit=512;const val cornerDp=10f;const val refractionHeightDp=18f;const val refractionAmountDp=26f;const val tintAlpha=.42f}

/** Android Canvas bridge of Kyant's Apache-2.0 rounded-rectangle lens.
 * One shader per timetable, never applied to foreground text. */
@RequiresApi(33)
internal class CourseLiquidLens {
    private val shader=RuntimeShader(SOURCE)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap:Bitmap?=null
    private var input:BitmapShader?=null
    private val matrix=Matrix()
    fun draw(canvas:Canvas,source:Bitmap,bounds:RectF,viewportWidth:Int,viewportHeight:Int,radius:Float,density:Float){
        if(bitmap!==source){bitmap=source;input=BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {setFilterMode(BitmapShader.FILTER_MODE_LINEAR)}}
        matrix.setScale(viewportWidth.toFloat()/source.width,viewportHeight.toFloat()/source.height)
        matrix.postTranslate(-bounds.left,-bounds.top)
        input!!.setLocalMatrix(matrix)
        shader.setInputShader("content",input!!)
        shader.setFloatUniform("size",bounds.width(),bounds.height())
        shader.setFloatUniform("offset",0f,0f)
        shader.setFloatUniform("cornerRadii",radius,radius,radius,radius)
        shader.setFloatUniform("refractionHeight",minOf(CourseGlassMaterial.refractionHeightDp*density,bounds.width()/3,bounds.height()/3))
        shader.setFloatUniform("refractionAmount",-CourseGlassMaterial.refractionAmountDp*density)
        shader.setFloatUniform("depthEffect",1f)
        paint.shader=shader
        canvas.save();canvas.translate(bounds.left,bounds.top)
        canvas.drawRoundRect(0f,0f,bounds.width(),bounds.height(),radius,radius,paint)
        canvas.restore()
    }
    companion object {private const val SOURCE="""

uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;


float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    return 1.0 - sqrt(1.0 - x * x);
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    float radius = radiusAt(coord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));

    float2 refractedCoord = coord + d * grad;
    return content.eval(refractedCoord);
}
"""}
}
