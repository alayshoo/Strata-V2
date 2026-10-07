package com.strata.app.backup

import android.content.ContentResolver
import android.net.Uri
import com.strata.app.data.db.AssetClassEntity
import com.strata.app.data.db.ImportEntity
import com.strata.app.data.db.ProductEntity
import com.strata.app.data.db.SnapshotEntity
import com.strata.app.data.db.SourceEntity
import com.strata.app.data.db.SpendingCategoryEntity
import com.strata.app.data.db.StrataDatabase
import com.strata.app.data.db.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long,
    val sources: List<SourceEntity>,
    val assetClasses: List<AssetClassEntity>,
    val spendingCategories: List<SpendingCategoryEntity>,
    val products: List<ProductEntity>,
    val snapshots: List<SnapshotEntity>,
    val transactions: List<TransactionEntity>,
    val imports: List<ImportEntity>,
)

class BackupException(message: String) : Exception(message)

/**
 * Passphrase-protected export of the ledger (not chats or the API key).
 * File: "STRATA1" | salt(16) | iv(12) | AES-256-GCM(json), key from PBKDF2-HMAC-SHA256.
 */
class BackupManager(private val db: StrataDatabase, private val resolver: ContentResolver) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun export(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        val dao = db.backupDao()
        val payload = BackupPayload(
            createdAt = System.currentTimeMillis(),
            sources = dao.sources(),
            assetClasses = dao.assetClasses(),
            spendingCategories = dao.spendingCategories(),
            products = dao.products(),
            snapshots = dao.snapshots(),
            transactions = dao.transactions(),
            imports = dao.imports(),
        )
        val plain = json.encodeToString(BackupPayload.serializer(), payload).toByteArray()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt)) }
        val sealed = cipher.doFinal(plain)
        resolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(cipher.iv)
            out.write(sealed)
        } ?: throw BackupException("Could not write the backup file.")
        payload.products.size to payload.transactions.size
    }

    /** Replaces the whole ledger with the backup's contents. */
    suspend fun restore(uri: Uri, passphrase: CharArray): BackupPayload = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw BackupException("Could not read the file.")
        if (bytes.size < MAGIC.size + 28 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("This is not a Strata backup.")
        }
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv)) }
                .doFinal(bytes, MAGIC.size + 28, bytes.size - MAGIC.size - 28)
        } catch (_: AEADBadTagException) {
            throw BackupException("Wrong passphrase.")
        }
        val payload = json.decodeFromString(BackupPayload.serializer(), plain.decodeToString())
        db.backupDao().replaceAll(
            payload.sources, payload.assetClasses, payload.spendingCategories,
            payload.products, payload.snapshots, payload.transactions, payload.imports,
        )
        payload
    }

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private companion object {
        val MAGIC = "STRATA1".toByteArray()
        const val ITERATIONS = 310_000
    }
}
