package app.kejian.mobile

import android.content.pm.PackageInstaller
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdateValidationTest {
    private val installed=UpdatePackageIdentity("app.kejian.mobile",33,setOf("release-certificate"))
    private val correct=installed.copy(versionCode=34)

    @Test fun acceptsOnlyTheAdvertisedNewPackageWithTheInstalledSigningIdentity(){
        assertNull(validateUpdateIdentity(installed,correct,34))
        assertEquals(UpdateValidationFailure.VERSION,validateUpdateIdentity(installed,correct,35))
        assertEquals(UpdateValidationFailure.VERSION,validateUpdateIdentity(installed,installed,33))
        assertEquals(UpdateValidationFailure.VERSION,validateUpdateIdentity(installed,installed.copy(versionCode=32),32))
        assertEquals(UpdateValidationFailure.PACKAGE,validateUpdateIdentity(installed,correct.copy(packageName="another.app"),34))
        assertEquals(UpdateValidationFailure.SIGNATURE,validateUpdateIdentity(installed,correct.copy(signers=setOf("different-key")),34))
        assertEquals(UpdateValidationFailure.SIGNATURE,validateUpdateIdentity(installed,correct.copy(signers=emptySet()),34))
        assertEquals(UpdateValidationFailure.INVALID_APK,validateUpdateIdentity(installed,null,34))
    }

    @Test fun signatureConflictNeverRecommendsDeletingLocalData(){
        val chinese=updateValidationMessage(UpdateValidationFailure.SIGNATURE,false)
        assertTrue(chinese.contains("请勿卸载"))
        assertTrue(chinese.contains("签名"))
        val english=updateValidationMessage(UpdateValidationFailure.SIGNATURE,true)
        assertTrue(english.contains("do not uninstall"))
        assertFalse(english.contains(Regex("[\\u4e00-\\u9fff]")))
    }

    @Test fun downloadedContentHashDetectsMutationInsteadOfTrustingTheFilename(){
        val file=File.createTempFile("kejian-update-test-",".apk")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",updateSha256(file))
            file.appendText("corrupt")
            assertNotEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",updateSha256(file))
        }finally{file.delete()}
    }

}
