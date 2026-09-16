package app.kejian.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalizationTest {
    @Test fun languagePreferenceRoundTripsAndRejectsUnknownValues() {
        val english=AppData(settings=Settings(language="en"))
        assertEquals("en",DataJson.decode(DataJson.encode(english)).settings.language)
        val invalid=DataJson.encode(english).replace("\"language\": \"en\"","\"language\": \"xx\"")
        assertEquals("zh",DataJson.decode(invalid).settings.language)
    }

    @Test fun coreInterfaceAndDynamicDatesTranslateWithoutTouchingUserText() {
        assertEquals("Widgets",localized("小组件",true))
        assertEquals("9/4 · Fri",localized("9 月 4 日 · 周五",true))
        assertEquals("Advanced Mathematics",localized("Advanced Mathematics",true))
        assertEquals("课间",localized("课间",false))
    }

    @Test fun englishScreensDoNotLeakChineseChrome() {
        val visibleChrome=listOf(
            "桌面上的小课表","课间 · 接下来 24 小时","24小时内没有课程，休息一下吧~","日程之外，也要记得照顾自己。",
            "支持者背景","当前使用清晰纯色背景。圆角与文字边距仍可免费调整。","圆角与文字留白","让四角更自然，文字离边缘更从容。设置应用到所有已添加的小组件。","调整圆角与边距",
            "录音与课堂总结","准备好后轻触开始","录音时间过短，至少需要录制 10 秒。","选择关联课程","在课表中点选一个课程模块","不关联课程",
            "暂停录音","继续录音","录音已暂停",
            "会员与额度","每月 150 万 AI token、120 分钟录音转写总结、3 个云存档与完整小组件外观。",
            "包含 Plus 全部功能，每月 AI 额度提升至 400 万 token，录音转写总结时长提升至 1120 分钟。",
            "不登录也能继续使用完整课表。登录后，课表才会加密传输到 kejian.im，并可在其他设备恢复。",
            "打开课间时课表处于浏览模式。点右下角「编辑」，才会出现保存、加号和周次切换。",
            "当前为浏览模式。点右下角编辑后，可复制、自定义或拖动课程。",
            "离开编辑模式？","还有未保存的课程改动。保存后才会更新通知和桌面组件。",
            "保存 23 行","已删除 3 行，可用撤销恢复","统一编辑 4 节课","有 2 组时间重叠，保存时会再次确认。",
            "当前尺寸实际留白：上 16 / 下 16 / 左 12 / 右 12 dp","课间 2.0.1 更新内容"
        )
        val han=Regex("[\\u4e00-\\u9fff]")
        visibleChrome.forEach {source->assertFalse("Untranslated: $source -> ${localized(source,true)}",han.containsMatchIn(localized(source,true)))}
    }
}
