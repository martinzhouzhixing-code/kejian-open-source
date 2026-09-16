package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class IcsImportTest {
    private val from=LocalDate.of(2026,9,1)
    private fun parse(body:String,zone:String="Asia/Shanghai")=IcsImport.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Kejian Test//EN\r\n$body\r\nEND:VCALENDAR",from,from.plusMonths(2),ZoneId.of(zone))
    private fun event(extra:String)= "BEGIN:VEVENT\r\nUID:one\r\nDTSTAMP:20260901T000000Z\r\nSUMMARY:数学\r\n$extra\r\nEND:VEVENT"
    @Test fun utcConvertsToLocal(){val p=parse(event("DTSTART:20260907T020000Z\r\nDTEND:20260907T033000Z"));assertEquals(emptyList<String>(),p.errors);assertEquals(600,p.courses.single().start);assertEquals(690,p.courses.single().end)}
    @Test fun weeklyExcludesCancelledDate(){val p=parse(event("DTSTART:20260907T020000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=3\r\nEXDATE:20260914T020000Z"));assertEquals(emptyList<String>(),p.errors);assertEquals(listOf(7,21),p.courses.map {it.date!!.dayOfMonth})}
    @Test fun timezonePreservesLocalTimeAcrossDst(){val p=parse(event("DTSTART;TZID=America/New_York:20261025T100000\r\nDTEND;TZID=America/New_York:20261025T110000\r\nRRULE:FREQ=WEEKLY;COUNT=2"),"America/New_York");assertEquals(emptyList<String>(),p.errors);assertEquals(listOf(600,600),p.courses.map {it.start})}
    @Test fun allDayIsDeadline(){val old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));val p=parse(event("DTSTART;VALUE=DATE:20260907\r\nDTEND;VALUE=DATE:20260909"));assertTrue(p.courses.isEmpty());assertEquals(listOf(7,8),p.deadlines.map {it.dueDate.dayOfMonth})}finally{TimeZone.setDefault(old)}}
    @Test fun overnightSplitsAtMidnight(){val p=parse(event("DTSTART:20260907T153000Z\r\nDTEND:20260907T163000Z"));assertEquals(listOf(1410,0),p.courses.map {it.start});assertEquals(listOf(1440,30),p.courses.map {it.end})}
    @Test fun movedOccurrenceReplacesOriginal(){val body=event("DTSTART:20260907T020000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=2")+"\r\n"+event("RECURRENCE-ID:20260914T020000Z\r\nDTSTART:20260915T030000Z\r\nDURATION:PT1H");val p=parse(body);assertEquals(emptyList<String>(),p.errors);assertEquals(listOf(7,15),p.courses.map {it.date!!.dayOfMonth})}
    @Test fun duplicateImportDoesNotAddCourses(){val p=parse(event("DTSTART:20260907T020000Z\r\nDURATION:PT1H"));val second=IcsImport.withoutDuplicates(p,AppData(courses=p.courses));assertTrue(second.courses.isEmpty())}
    @Test fun malformedDurationIsReported(){val p=parse(event("DTSTART:20260907T020000Z\r\nDTEND:20260907T010000Z"));assertTrue(p.courses.isEmpty());assertEquals(1,p.errors.size)}
    @Test fun unboundedRecurrenceStaysInsideWindow(){val p=parse(event("DTSTART:20260101T020000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=DAILY"));assertEquals(62,p.courses.size);assertTrue(p.courses.all {it.date in from..from.plusMonths(2)})}
    @Test fun minuteFrequencyRejected(){assertTrue(parse(event("DTSTART:20260907T020000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=MINUTELY")).errors.isNotEmpty())}
    @Test fun appearancePreferencesRoundTrip(){val s=Settings(liquidGlass=false,appBackgroundUri="file:///example.jpg",appBackgroundBlur=.6f,appBackgroundTone=-.2f);assertEquals(s,DataJson.decode(DataJson.encode(AppData(settings=s))).settings);assertNull(DataJson.decode(DataJson.encode(AppData(settings=s),includeLocalMedia=false)).settings.appBackgroundUri)}
    @Test fun schoolBrowserRejectsUnsafeSchemes(){assertTrue(UniversityDirectory.allowedUrl("https://jw.example.edu.cn/login"));listOf("http://jw.example.edu.cn","javascript:alert(1)","file:///etc/passwd","https://name:password@example.com","intent://x","https://").forEach {assertFalse(it,UniversityDirectory.allowedUrl(it))}}
    @Test fun utcRuleDoesNotShiftWithDisplayDst(){val p=parse(event("DTSTART:20261025T140000Z\r\nDURATION:PT1H\r\nRRULE:FREQ=WEEKLY;COUNT=2"),"America/New_York");assertEquals(listOf(600,540),p.courses.map {it.start})}
    @Test fun recurrenceDatesIncludeInitialEvent(){val p=parse(event("DTSTART:20260907T020000Z\r\nDURATION:PT1H\r\nRDATE:20260914T020000Z"));assertEquals(listOf(7,14),p.courses.map {it.date!!.dayOfMonth})}
}
