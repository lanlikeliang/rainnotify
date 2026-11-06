package cn.rainnotify.app.answer

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class SubmitOutcome { ACCEPTED, ALREADY_ANSWERED, REJECTED, UNKNOWN }

/** A durable write-ahead guard: once reserved, this client never automatically repeats that POST. */
class SubmissionJournal(private val root: File) {
    companion object { private val lock = Any() }
    init { root.mkdirs() }
    private fun file(key: String): File {
        require(Regex("[0-9a-f]{64}").matches(key))
        return File(root, key + ".json")
    }
    fun used(key: String): Boolean = synchronized(lock) { file(key).exists() }
    fun reserve(key: String, task: String) = synchronized(lock) {
        val destination = file(key)
        if (destination.exists()) throw AnswerFailure("本题已有提交尝试记录，请到微信核对；不会自动重复提交。")
        write(destination, JSONObject().put("task", task).put("state", "IN_FLIGHT"))
    }
    fun complete(key: String, outcome: SubmitOutcome) = synchronized(lock) {
        val destination = file(key)
        check(destination.exists())
        write(destination, JSONObject().put("state", outcome.name))
    }
    private fun write(file: File, value: JSONObject) {
        val temp = File(root, file.name + ".tmp")
        temp.outputStream().use { it.write(value.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
