package app.kejian.mobile
import org.junit.Assert.*
import org.junit.Test
class AppearanceModeTest {
    @Test fun legacyFlagsRemainUnambiguous(){
        assertEquals(AppearanceMode.Pure,Settings(glassBackground=false,liquidGlass=true).appearanceMode)
        assertEquals(AppearanceMode.Blur,Settings(glassBackground=true,liquidGlass=false).appearanceMode)
        assertEquals(AppearanceMode.Liquid,Settings(glassBackground=true,liquidGlass=true).appearanceMode)
    }
}
