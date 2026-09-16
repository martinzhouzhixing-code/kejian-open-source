package app.kejian.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

object NotificationArtwork {
    fun large(context:Context):Bitmap {
        val size=(64*context.resources.displayMetrics.density).toInt().coerceAtLeast(64)
        return Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888).also {bitmap->
            val inset=(size*.22f).toInt()
            ContextCompat.getDrawable(context,R.drawable.ic_launcher)?.mutate()?.apply {setBounds(inset,inset,size-inset,size-inset);draw(Canvas(bitmap))}
        }
    }
}
