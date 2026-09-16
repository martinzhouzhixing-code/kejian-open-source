package app.kejian.mobile

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.Log
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max

/** Effects apply only to the background; source images and text remain untouched. */
object WidgetMaterials {
    fun background(context:Context,settings:Settings,palette:AppPalette,width:Int,height:Int,tutorial:Boolean=false):Bitmap {
        val w=width.coerceAtLeast(1);val h=height.coerceAtLeast(1)
        // Keep the aspect ratio and the dp radius together. Never stretch a fixed 256px mask.
        val scale=minOf(2f,1024f/maxOf(w,h))
        val bitmap=Bitmap.createBitmap((w*scale).toInt().coerceAtLeast(1),(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).apply {density=Bitmap.DENSITY_NONE}
        val allowed=ProductAccess.supporterAppearance||(tutorial&&settings.widgetBackgroundUri?.startsWith("android.resource://${context.packageName}/")==true)
        val mode=if(allowed)settings.widgetBackgroundMode else "solid"
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=palette.surface;alpha=(if(mode=="translucent")settings.widgetOpacity.coerceIn(.35f,1f) else 1f).times(255).toInt()}
        val radius=WidgetGeometry.radius(settings.widgetStyle,w,h)*scale
        val canvas=Canvas(bitmap)
        if(mode=="image"&&settings.widgetBackgroundUri!=null&&drawImage(context,canvas,settings.widgetBackgroundUri,bitmap.width,bitmap.height,radius,settings.widgetBackgroundTone,settings.widgetFrosted))return bitmap
        canvas.drawRoundRect(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat(),radius,radius,paint)
        return bitmap
    }
    private fun drawImage(context:Context,canvas:Canvas,uriText:String,width:Int,height:Int,radius:Float,tone:Float,frosted:Boolean):Boolean=runCatching {
        val uri=Uri.parse(uriText)
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        // BitmapFactory intentionally returns null in bounds-only mode; the
        // dimensions, not the returned bitmap, determine whether decoding worked.
        val boundsStream=openImage(context,uri)?:return false
        boundsStream.use {BitmapFactory.decodeStream(it,null,bounds)}
        if(bounds.outWidth<=0||bounds.outHeight<=0)return false
        val desired=max(width,height).coerceAtLeast(256)*2
        var sample=1
        while(bounds.outWidth/sample>desired*2||bounds.outHeight/sample>desired*2)sample*=2
        val options=BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val source=openImage(context,uri)?.use {BitmapFactory.decodeStream(it,null,options)}?:return false
        val clip=Path().apply {addRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,Path.Direction.CW)}
        canvas.save();canvas.clipPath(clip)
        val rendered=if(frosted)FrostedBitmap.create(source)else source
        val factor=max(width/rendered.width.toFloat(),height/rendered.height.toFloat())
        val dw=rendered.width*factor;val dh=rendered.height*factor
        canvas.drawBitmap(rendered,null,RectF((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val adjusted=tone.coerceIn(-1f,1f)
        if(adjusted!=0f)canvas.drawColor((if(adjusted<0)Color.BLACK else Color.WHITE) and 0x00FFFFFF or ((abs(adjusted)*170).toInt() shl 24))
        if(frosted){val rim=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;alpha=70;style=Paint.Style.STROKE;strokeWidth=2f};canvas.drawRoundRect(1f,1f,width-1f,height-1f,radius,radius,rim)}
        canvas.restore();if(rendered!==source)rendered.recycle();source.recycle();true
    }.onFailure {Log.e("KejianWidget","Unable to render selected background",it)}.getOrDefault(false)
    private fun openImage(context:Context,uri:Uri):InputStream?=if(uri.scheme=="file")uri.path?.let {FileInputStream(it)} else context.contentResolver.openInputStream(uri)
    /** Small analysis copy only: the stored image stays untouched. */
    fun imageLuminance(context:Context,uriText:String):Float?=runCatching {
        val uri=Uri.parse(uriText);val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        openImage(context,uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0&&bounds.outHeight>0)
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>128)sample*=2
        val bitmap=openImage(context,uri)?.use {BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply {inSampleSize=sample})}?:return null
        fun linear(value:Int):Double {val v=value/255.0;return if(v<=.04045)v/12.92 else Math.pow((v+.055)/1.055,2.4)}
        var total=0.0;var weight=0.0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);val a=Color.alpha(c)/255.0;total+=a*(.2126*linear(Color.red(c))+.7152*linear(Color.green(c))+.0722*linear(Color.blue(c)));weight+=a}
        bitmap.recycle();if(weight==0.0)null else (total/weight).toFloat()
    }.getOrNull()
    fun textColor(settings:Settings):Int {
        if(settings.widgetTextMode=="black")return Color.BLACK
        if(settings.widgetTextMode=="white")return Color.WHITE
        val tone=settings.widgetBackgroundTone;val l=settings.widgetImageLuminance?:.08f
        val adjusted=if(tone>=0)l+(1-l)*tone*.667f else l*(1+tone*.667f)
        return if(adjusted>.179f)Color.BLACK else Color.WHITE
    }
    fun tag(color:Int):Bitmap=Bitmap.createBitmap(12,32,Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawRoundRect(0f,0f,12f,32f,6f,6f,Paint(Paint.ANTI_ALIAS_FLAG).apply {this.color=color})
    }
}
