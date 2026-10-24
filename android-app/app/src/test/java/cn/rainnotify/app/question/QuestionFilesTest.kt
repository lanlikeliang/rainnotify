package cn.rainnotify.app.question

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class QuestionFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(at: Long) = QuestionRecord(UUID.randomUUID().toString(), QuestionRef("10", "20", "30"),
        at, QuestionContent(1, "题干", listOf(QuestionOption("A", "A", "选项")), emptyList(), 1, emptyList()),
        "已保存", false)

    @Test fun persistsSeparateQuestionRecordsAndImagesAcrossRepositoryInstances() {
        val folder = temporary.newFolder()
        val store = QuestionFiles(folder)
        val first = record(1)
        val second = record(2)
        store.write(first); store.saveImage(first.id, 0, byteArrayOf(1, 2, 3))
        store.write(second); store.saveImage(second.id, 0, byteArrayOf(4, 5))
        val reopened = QuestionFiles(folder)
        assertEquals(listOf(second.id, first.id), reopened.records().map { it.id })
        assertEquals("题干", reopened.records().last().content!!.bodyHtml)
        assertArrayEquals(byteArrayOf(1, 2, 3), reopened.image(first.id, 0)!!.readBytes())
        assertArrayEquals(byteArrayOf(4, 5), reopened.image(second.id, 0)!!.readBytes())
    }

    @Test fun retentionRemovesOldestRecordTogetherWithItsImages() {
        val store = QuestionFiles(temporary.newFolder(), maxRecords = 2)
        val first = record(1)
        store.write(first); store.saveImage(first.id, 0, byteArrayOf(1))
        store.write(record(2)); store.write(record(3))
        assertEquals(2, store.records().size)
        assertNull(store.image(first.id, 0))
    }

    @Test fun refreshingCannotReuseAnOldImageAtTheSameIndex() {
        val store = QuestionFiles(temporary.newFolder())
        val record = record(1)
        store.write(record); store.saveImage(record.id, 0, byteArrayOf(1))
        store.removeImages(record.id)
        assertNull(store.image(record.id, 0))
        assertEquals(record.id, store.records().single().id)
    }

    @Test fun rejectsPathTraversalAndClearDoesNotDeleteUnrelatedFiles() {
        val root = temporary.newFolder()
        val unrelated = java.io.File(root, "keep.txt").apply { writeText("keep") }
        val store = QuestionFiles(root)
        assertNull(store.image("../other", 0))
        assertThrows(IllegalArgumentException::class.java) { store.saveImage("../other", 0, byteArrayOf(1)) }
        store.write(record(1))
        store.clear()
        assertTrue(store.records().isEmpty())
        assertEquals("keep", unrelated.readText())
    }

    @Test fun byteBudgetAlsoPrunesRecordsBelowTheCountLimit() {
        val store = QuestionFiles(temporary.newFolder(), maxBytes = 1500)
        val first = record(1)
        store.write(first); store.saveImage(first.id, 0, ByteArray(800))
        val second = record(2)
        store.write(second); store.saveImage(second.id, 0, ByteArray(800))
        assertEquals(listOf(second.id), store.records().map { it.id })
        assertNull(store.image(first.id, 0))
    }
}
