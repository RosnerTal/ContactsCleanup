package com.example.contactscleaner.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Database
import androidx.room.RoomDatabase
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ignored_contacts")
data class IgnoredContact(
    @PrimaryKey val lookupKey: String,
    val displayName: String,
    val phoneNumber: String
)

@Entity(tableName = "whatsapp_interactions")
data class WhatsAppInteraction(
    @PrimaryKey val contactName: String,
    val timestamp: Long
)

@Entity(tableName = "contact_classifications")
data class ContactClassification(
    @PrimaryKey val contactId: String,
    val classification: String // "KEEP", "DELETE", "UNSORTED"
)

@Dao
interface IgnoredContactDao {
    @Query("SELECT * FROM ignored_contacts")
    fun getAllIgnoredFlow(): Flow<List<IgnoredContact>>

    @Query("SELECT * FROM ignored_contacts")
    suspend fun getAllIgnored(): List<IgnoredContact>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: IgnoredContact): Unit

    @Delete
    suspend fun delete(contact: IgnoredContact): Unit

    @Query("DELETE FROM ignored_contacts WHERE lookupKey = :lookupKey")
    suspend fun deleteByLookupKey(lookupKey: String): Unit
}

@Dao
interface WhatsAppInteractionDao {
    @Query("SELECT * FROM whatsapp_interactions")
    suspend fun getAllInteractions(): List<WhatsAppInteraction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(interaction: WhatsAppInteraction): Unit
}

@Dao
interface ContactClassificationDao {
    @Query("SELECT * FROM contact_classifications")
    suspend fun getAllClassifications(): List<ContactClassification>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(classification: ContactClassification): Unit

    @Query("DELETE FROM contact_classifications WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: String): Unit

    @Query("DELETE FROM contact_classifications")
    suspend fun clearAllClassifications(): Unit
}

@Database(entities = [IgnoredContact::class, WhatsAppInteraction::class, ContactClassification::class], version = 3, exportSchema = false)
abstract class IgnoreDatabase : RoomDatabase() {
    abstract fun ignoredContactDao(): IgnoredContactDao
    abstract fun whatsappInteractionDao(): WhatsAppInteractionDao
    abstract fun contactClassificationDao(): ContactClassificationDao

    companion object {
        @Volatile
        private var INSTANCE: IgnoreDatabase? = null

        fun getDatabase(context: Context): IgnoreDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IgnoreDatabase::class.java,
                    "ignore_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
