package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    @Test fun onlyGreaterVersionCodeIsAnUpdate(){
        assertTrue(isNewerRelease(14,13))
        assertFalse(isNewerRelease(13,13))
        assertFalse(isNewerRelease(12,13))
    }
    @Test fun expandedCoursePaletteAcceptsTwelveIndexes(){
        assertNull(Course(color=11).error())
        assertEquals("课程颜色无效",Course(color=12).error())
    }
}
