package app.kejian.mobile

import org.junit.Assert.*
import org.junit.Test

class BackgroundExecutionSettingsTest {
    @Test fun directExemptionRequiresBothApplicableStateAndExplicitImpactConfirmation(){
        val applicable=BackgroundExecutionState(batteryExempt=false,directExemptionAvailable=true)
        assertFalse(canRequestDirectBatteryExemption(applicable,false))
        assertTrue(canRequestDirectBatteryExemption(applicable,true))
        assertFalse(canRequestDirectBatteryExemption(applicable.copy(batteryExempt=true),true))
        assertFalse(canRequestDirectBatteryExemption(applicable.copy(batteryExempt=null),true))
        assertFalse(canRequestDirectBatteryExemption(applicable.copy(directExemptionAvailable=false),true))
    }
    @Test fun blockedTaskChannelIsReportedEvenWhenApplicationNotificationsAreAllowed(){
        val old=AppLanguage.code
        try {
            AppLanguage.code="en"
            assertEquals("Check notifications",backgroundExecutionSummary(BackgroundExecutionState(notificationsAllowed=true,
                channels=listOf(BackgroundNotificationChannel("kejian_ai_work",BackgroundChannelState.BLOCKED)))))
        }finally {AppLanguage.code=old}
    }
    @Test fun unavailableCompatibilityApisDoNotProduceAnAllClearClaim(){
        val old=AppLanguage.code
        try {
            AppLanguage.code="en"
            assertEquals("Notifications, battery & limits",backgroundExecutionSummary(BackgroundExecutionState()))
            assertFalse(canRequestDirectBatteryExemption(BackgroundExecutionState(),true))
        }finally {AppLanguage.code=old}
    }
}
