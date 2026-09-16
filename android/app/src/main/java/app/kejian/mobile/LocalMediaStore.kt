package app.kejian.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest
import kotlin.math.max

/** Copies Photo Picker results into app storage so temporary picker grants never break later. */
object LocalMediaStore {
    private fun decode(context:Context,uri:Uri,maxSide:Int):Bitmap {
        if(Build.VERSION.SDK_INT>=28){
            val source=ImageDecoder.createSource(context.contentResolver,uri)
            return ImageDecoder.decodeBitmap(source){decoder,info,_->
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                val width=info.size.width;val height=info.size.height
                val scale=minOf(1f,maxSide/max(width,height).toFloat())
                if(scale<1f)decoder.setTargetSize((width*scale).toInt().coerceAtLeast(1),(height*scale).toInt().coerceAtLeast(1))
            }
        }
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,bounds)}?:error("图片无法读取")
        require(bounds.outWidth>0&&bounds.outHeight>0){"图片格式无效"}
        var sample=1
        while(max(bounds.outWidth,bounds.outHeight)/sample>maxSide*2)sample*=2
        val options=BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        return context.contentResolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,options)}?:error("图片无法读取")
    }

    private fun writeJpeg(bitmap:Bitmap,target:File,quality:Int):File {
        target.parentFile?.mkdirs();val temporary=File(target.parentFile,".${target.name}.tmp")
        temporary.outputStream().buffered().use {require(bitmap.compress(Bitmap.CompressFormat.JPEG,quality,it)){"图片保存失败"}}
        if(target.exists()&&!target.delete())error("旧图片无法替换")
        require(temporary.renameTo(target)){"图片保存失败"}
        return target
    }

    private fun key(userId:String)=MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).take(12).joinToString(""){"%02x".format(it)}

    fun saveAvatar(context:Context,userId:String,uri:Uri):File {
        val source=decode(context,uri,720);val side=minOf(source.width,source.height)
        val left=(source.width-side)/2;val top=(source.height-side)/2
        val cropped=Bitmap.createBitmap(source,left,top,side,side)
        val output=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(cropped,null,android.graphics.Rect(0,0,384,384),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val file=writeJpeg(output,File(context.filesDir,"avatars/${key(userId)}.jpg"),88)
        if(cropped!==source)cropped.recycle();source.recycle();output.recycle();return file
    }

    fun avatarFile(context:Context,userId:String)=File(context.filesDir,"avatars/${key(userId)}.jpg")

    fun saveAppBackground(context:Context,uri:Uri):Uri {
        val bitmap=decode(context,uri,2048)
        try {return Uri.fromFile(writeJpeg(bitmap,File(context.filesDir,"appearance/background-${System.currentTimeMillis()}.jpg"),94))}
        finally {bitmap.recycle()}
    }
    fun readAppBackground(context:Context,value:String):Bitmap? {
        val uri=Uri.parse(value)
        if(uri.scheme!="file")return null
        val file=File(uri.path?:return null).canonicalFile
        val directory=File(context.filesDir,"appearance").canonicalFile
        if(file.parentFile!=directory||!file.isFile)return null
        return decode(context,Uri.fromFile(file),2048)
    }

    fun saveWidgetBackground(context:Context,uri:Uri):Uri {
        // Keep enough detail for large 4x2 widgets while still bounding decode
        // memory on older phones. A new filename also invalidates Compose and
        // launcher bitmap caches immediately when the user replaces an image.
        val bitmap=decode(context,uri,3072)
        val directory=File(context.filesDir,"widget")
        val file=writeJpeg(bitmap,File(directory,"background-${System.currentTimeMillis()}.jpg"),96)
        bitmap.recycle()
        directory.listFiles()?.filter {it!=file&&it.name.startsWith("background-")&&it.extension=="jpg"}?.forEach {it.delete()}
        return Uri.fromFile(file)
    }
}
