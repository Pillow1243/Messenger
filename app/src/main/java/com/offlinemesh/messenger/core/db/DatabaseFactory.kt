package com.offlinemesh.messenger.core.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.offlinemesh.messenger.core.crypto.KeystoreHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Builds the single app database on top of SQLCipher.
 *
 * The database passphrase is 32 random bytes generated on first launch and stored wrapped by an
 * Android Keystore AES-GCM key (see [KeystoreHelper]). SQLCipher encrypts the whole file with
 * AES-256, so no plaintext message, key or contact ever reaches the flash.
 *
 * Recovery policy: if the wrapped passphrase cannot be unwrapped (Keystore reset, restored
 * backup) the encrypted file is **quarantined**, never deleted, and a fresh database is created
 * next to it. Data is therefore never destroyed by the app, and an operator can still recover it
 * with the original Keystore material.
 */
object DatabaseFactory {

    private const val TAG = "DatabaseFactory"
    private const val DB_NAME = "mesh.db"

    private const val RECOVERY_PREFS = "mesh_recovery"
    private const val KEY_LAST_RECOVERY = "last_recovery_at"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) { instance ?: open(context.applicationContext).also { instance = it } }

    private fun open(context: Context): AppDatabase {
        // Required once per process before any SQLCipher class is touched.
        System.loadLibrary("sqlcipher")
        val passphrase = try {
            KeystoreHelper.getDatabasePassphrase(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Keystore passphrase unavailable", t)
            quarantine(context, "keystore")
            KeystoreHelper.rotatePassphrase(context)
            KeystoreHelper.getDatabasePassphrase(context)
        }
        return try {
            build(context, passphrase).also { probe(it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Encrypted database could not be opened with the current passphrase", t)
            quarantine(context, "unreadable")
            KeystoreHelper.rotatePassphrase(context)
            build(context, KeystoreHelper.getDatabasePassphrase(context)).also { probe(it) }
        }
    }

    private fun build(context: Context, passphrase: ByteArray): AppDatabase {
        val factory = SupportOpenHelperFactory(passphrase)
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            DB_NAME
        )
            .openHelperFactory(factory)
            // No destructive fallback: every schema change ships with an explicit migration.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    /** Forces Room to actually open the SQLCipher file so a bad key fails here, not later. */
    private fun probe(db: AppDatabase) {
        db.openHelper.writableDatabase
            .query("SELECT count(*) FROM sqlite_master")
            .use { it.moveToFirst() }
    }

    /**
     * Moves an unreadable database (and its WAL/SHM side files) aside instead of deleting it.
     * A timestamped name keeps every attempted recovery, so nothing is ever lost silently.
     */
    private fun quarantine(context: Context, reason: String) {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return
        val stamp = System.currentTimeMillis()
        val target = File(dbFile.parentFile, "$DB_NAME.unreadable-$reason-$stamp")
        val renamed = dbFile.renameTo(target)
        File(dbFile.absolutePath + "-wal").let { wal -> if (wal.exists()) wal.renameTo(File(target.absolutePath + "-wal")) }
        File(dbFile.absolutePath + "-shm").let { shm -> if (shm.exists()) shm.renameTo(File(target.absolutePath + "-shm")) }
        Log.w(TAG, "Quarantined database to ${target.name} (renamed=$renamed)")
        context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_RECOVERY, stamp)
            .putString("reason", reason)
            .apply()
    }

    /** Timestamp of the last quarantine, so the UI can tell the user their old data is preserved. */
    fun lastRecoveryAt(context: Context): Long =
        context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_RECOVERY, 0L)

    /** Used by "erase all data". */
    fun closeAndDelete(context: Context) {
        synchronized(this) {
            try {
                instance?.close()
            } catch (e: Exception) {
                Log.w(TAG, "close ignored", e)
            }
            instance = null
            context.deleteDatabase(DB_NAME)
            File(context.getDatabasePath(DB_NAME).absolutePath + "-wal").delete()
            File(context.getDatabasePath(DB_NAME).absolutePath + "-shm").delete()
        }
    }
}
