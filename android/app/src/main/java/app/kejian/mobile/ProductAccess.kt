package app.kejian.mobile

import android.content.Context

/** Server-issued entitlements cached locally so widgets enforce the same policy. */
object ProductAccess {
    // 2.0 uses server-issued tier entitlements; the server can still enable a
    // time-limited launch promotion without baking permanent access into APKs.
    private const val PROMOTIONAL_SUPPORTER=true
    private var context:Context?=null
    fun initialize(value:Context){context=value.applicationContext}
    fun update(profile:AccountProfile?){
        val editor=context?.getSharedPreferences("kejian_entitlements",Context.MODE_PRIVATE)?.edit()?:return
        editor.putString("role",profile?.role?:"default")
            .putBoolean("widgetBackground",PROMOTIONAL_SUPPORTER||profile?.widgetBackground==true)
            .putBoolean("multipleCloudSlots",PROMOTIONAL_SUPPORTER||profile?.multipleCloudSlots==true)
            .apply()
    }
    private val prefs get()=context?.getSharedPreferences("kejian_entitlements",Context.MODE_PRIVATE)
    val role:String get()=prefs?.getString("role","default")?:"default"
    val supporterAppearance:Boolean get()=PROMOTIONAL_SUPPORTER||prefs?.getBoolean("widgetBackground",false)==true
    val cloudSlotCount:Int get()=if(PROMOTIONAL_SUPPORTER||prefs?.getBoolean("multipleCloudSlots",false)==true)3 else 1
}
