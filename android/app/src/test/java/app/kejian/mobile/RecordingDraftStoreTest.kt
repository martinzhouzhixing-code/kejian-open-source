package app.kejian.mobile

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingDraftStoreTest {
    private fun audio(directory:File,name:String,modified:Long)=File(directory,name).apply {writeBytes(ByteArray(512));setLastModified(modified)}

    @Test fun upgradeRecoversLatestValidUnsavedRecordingWithoutChangingFiles()=runBlocking {
        val directory=Files.createTempDirectory("recording-draft-").toFile()
        val older=audio(directory,"kejian-old.m4a",1000)
        val saved=audio(directory,"kejian-saved.m4a",3000)
        val wanted=audio(directory,"kejian-recover.m4a",2000)
        try {
            val result=findRecoverableRecording(directory,null,setOf(saved.absolutePath)){4741}
            assertEquals(wanted.absolutePath,result?.file?.absolutePath);assertEquals(4741,result?.seconds)
            listOf(older,saved,wanted).forEach {assertTrue(it.isFile);assertEquals(512L,it.length())}
        }finally{listOf(older,saved,wanted).forEach {it.delete()};directory.delete()}
    }

    @Test fun ignoresOutsideDirectoryUnsupportedNamesAndShortOrInvalidAudio()=runBlocking {
        val directory=Files.createTempDirectory("recording-draft-").toFile()
        val outside=File.createTempFile("kejian-outside-",".m4a").apply {writeBytes(ByteArray(512))}
        val other=audio(directory,"not-our-recording.m4a",5000)
        val corrupt=audio(directory,"kejian-corrupt.m4a",4000)
        val short=audio(directory,"kejian-short.m4a",3000)
        val valid=audio(directory,"kejian-valid.m4a",2000)
        try {
            val visited=mutableListOf<String>()
            val result=findRecoverableRecording(directory,outside.absolutePath,emptySet()){
                visited+=it.name;when(it.name){corrupt.name->0;short.name->9;else->10}
            }
            assertEquals(valid.absolutePath,result?.file?.absolutePath)
            assertFalse(outside.name in visited);assertFalse(other.name in visited)
            assertTrue(corrupt.exists());assertTrue(short.exists())
        }finally{listOf(outside,other,corrupt,short,valid).forEach {it.delete()};directory.delete()}
    }

    @Test fun activeDraftIsPreferredButSavedDraftNeverReappears()=runBlocking {
        val directory=Files.createTempDirectory("recording-draft-").toFile()
        val draft=audio(directory,"kejian-draft.m4a",1000)
        val newer=audio(directory,"kejian-newer.m4a",2000)
        try {
            assertEquals(draft.absolutePath,findRecoverableRecording(directory,draft.absolutePath,emptySet()){10}?.file?.absolutePath)
            assertEquals(newer.absolutePath,findRecoverableRecording(directory,draft.absolutePath,setOf(draft.absolutePath)){10}?.file?.absolutePath)
            assertNull(findRecoverableRecording(directory,draft.absolutePath,setOf(draft.absolutePath,newer.absolutePath)){10})
        }finally{listOf(draft,newer).forEach {it.delete()};directory.delete()}
    }
}
