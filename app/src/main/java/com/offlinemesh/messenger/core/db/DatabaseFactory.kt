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
 */
object DatabaseFactory {

    private const val TAG = "DatabaseFactory"
    private const val DB_NAME = "mesh.db"

    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) { instance ?: open(context).also { instance = it } }

    private fun open(context: Context): AppDatabase {
        // Required once per process before any SQLCipher class is touched.
        System.loadLibrary("sqlcipher")
        return try {
            build(context, KeystoreHelper.getDatabasePassphrase(context)).also { probe(it) }
        } catch (t: Throwable) {
            // A keystore reset (factory reset restore, key invalidation) leaves the database
            // permanently unreadable. Rather than writing anything unencrypted we drop the
            // unreadable file and start over with a fresh passphrase.
            Log.e(TAG, "Encrypted database could not be opened; recreating", t)
            context.deleteDatabase(DB_NAME)
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
            .fallbackToDestructiveMigration()
            .build()
    }

    /** Forces Room to actually open the SQLCipher file so a bad key fails here, not later. */
    private fun probe(db: AppDatabase) {
        db.openHelper.writableDatabase
            .query("SELECT count(*) FROM sqlite_master")
            .use { it.moveToFirst() }
    }

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
