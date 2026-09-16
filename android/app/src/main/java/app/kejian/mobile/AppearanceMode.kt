package app.kejian.mobile

/** Keep the existing stored flags for seamless upgrades and older cloud archives. */
enum class AppearanceMode { Pure, Blur, Liquid }
val Settings.appearanceMode:AppearanceMode get()=when {
    !glassBackground->AppearanceMode.Pure
    liquidGlass->AppearanceMode.Liquid
    else->AppearanceMode.Blur
}
fun Settings.withAppearanceMode(mode:AppearanceMode)=copy(
    glassBackground=mode!=AppearanceMode.Pure,liquidGlass=mode==AppearanceMode.Liquid)
