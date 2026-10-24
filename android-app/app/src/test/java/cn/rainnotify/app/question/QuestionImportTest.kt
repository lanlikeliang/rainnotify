package cn.rainnotify.app.question

import cn.rainnotify.app.answer.AnswerFailure
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuestionImportTest {
    @get:Rule val temp = TemporaryFolder()
    private val info = QuestionExportInfo("test", 36, "fixture", "fixture")
    private val jpeg = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 0, 1, 2)
    private fun record() = QuestionRecord(UUID.randomUUID().toString(), QuestionRef("10", "20", "30"), 1,
        QuestionContent(1, "stem", listOf(QuestionOption("A", "1", ""), QuestionOption("B", "2", "")),
            listOf(QuestionAsset("cover", "https://example.invalid/private?token=secret")), 1, emptyList()), "ready", false)

    @Test fun exportedSampleImportsAsOfflineAndPreparedImagesSurviveCacheRemoval() {
        val source = QuestionFiles(temp.newFolder())
        val original = record()
        source.write(original); source.saveImage(original.id, 0, jpeg)
        val zip = ByteArrayOutputStream().also { source.export(it, info) }.toByteArray()
        val destination = QuestionFiles(temp.newFolder())
        assertEquals(1, QuestionImport.read(zip.inputStream(), temp.newFolder(), destination))
        val imported = destination.records().single()
        assertTrue(imported.offline); assertNotEquals(original.id, imported.id)
        assertEquals("offline", imported.reference.lessonId)
        assertFalse(imported.json().toString().contains("secret"))
        val prepared = destination.prepare(imported.id, temp.newFolder())
        destination.clear()
        assertArrayEquals(jpeg, prepared.images.single().readBytes())
        assertTrue(prepared.record.offline)
    }
    @Test fun traversalEntryNeverWritesOutsideStagingOrImportsAnything() {
        val staging = temp.newFolder()
        val store = QuestionFiles(temp.newFolder())
        val zip = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("../escape")); it.write(byteArrayOf(1)); it.closeEntry()
        } }.toByteArray()
        assertThrows(AnswerFailure::class.java) { QuestionImport.read(zip.inputStream(), staging, store) }
        assertFalse(File(staging.parentFile, "escape").exists()); assertTrue(store.records().isEmpty())
    }
    @Test fun incompleteExportCannotBePresentedAsCompleteAiInput() {
        val source = QuestionFiles(temp.newFolder())
        source.write(record())
        val zip = ByteArrayOutputStream().also { source.export(it, info) }.toByteArray()
        val destination = QuestionFiles(temp.newFolder())
        assertThrows(AnswerFailure::class.java) { QuestionImport.read(zip.inputStream(), temp.newFolder(), destination) }
        assertTrue(destination.records().isEmpty())
    }
    @Test fun preparationRejectsMissingImageAndUnsupportedBytes() {
        val store = QuestionFiles(temp.newFolder())
        val original = record()
        store.write(original)
        assertThrows(AnswerFailure::class.java) { store.prepare(original.id, temp.newFolder()) }
        store.saveImage(original.id, 0, byteArrayOf(1, 2, 3))
        assertThrows(AnswerFailure::class.java) { store.prepare(original.id, temp.newFolder()) }
    }
}
