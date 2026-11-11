package com.offlinemesh.messenger.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        RelayEntity::class,
        SeenPacketEntity::class,
        IdentityEntity::class,
        SessionEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        KyberPreKeyEntity::class,
        FileTransferEntity::class,
        KeyValueEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun relayDao(): RelayDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun signalDao(): SignalDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun keyValueDao(): KeyValueDao
}
