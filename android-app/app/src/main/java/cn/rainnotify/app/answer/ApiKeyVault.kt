package cn.rainnotify.app.answer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted in noBackupFilesDir. Excluded from samples and diagnostics. */
class ApiKeyVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "deepseek-key-v1.enc"))
    private val alias = "rainnotify.deepseek.v1"
    private val aad = "RainNotifyDeepSeek:v1".toByteArray()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    fun exists() = file.baseFile.exists()
    fun read(): String {
        try {
            val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
            cipher.updateAAD(aad)
            return cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw AnswerFailure("无法读取 API Key，请在设置中重新保存。") }
    }
    fun write(value: String) {
        if (!Regex("[!-~]{16,256}").matches(value)) throw AnswerFailure("密钥格式不正确，请粘贴完整 API Key，不要包含空格。")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(aad)
            val data = cipher.doFinal(value.toByteArray())
            val json = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            val stream = file.startWrite()
            try { stream.write(json.toString().toByteArray()); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        } catch (_: Exception) { throw AnswerFailure("密钥保存失败，请重试。") }
    }
    fun clear() { file.delete() }
}
