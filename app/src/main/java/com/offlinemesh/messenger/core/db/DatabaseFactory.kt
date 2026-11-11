package com.offlinemesh.messenger.core.db

import android.content.Context
import androidx.room.Room
import com.offlinemesh.messenger.core.crypto.KeystoreHelper
import net.sqlcipher.database.SupportFactory

object DatabaseFactory {
    fun create(context: Context): AppDatabase {
        val passphrase = KeystoreHelper.getDatabasePassphrase(context)
        val factory = SupportFactory(passphrase)
        return Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "messenger.db"
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }
}
