package com.example.contactscleaner.data

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ContactsCleanerRepository(private val context: Context) {
    private val db = IgnoreDatabase.getDatabase(context)
    private val dao = db.ignoredContactDao()
    private val scanner = ContactScanner(context)
    private val backupManager = BackupManager(context)
    private val prefs = context.getSharedPreferences("unused_contacts_prefs", Context.MODE_PRIVATE)

    fun getIgnoredContactsFlow(): Flow<List<IgnoredContact>> = dao.getAllIgnoredFlow()

    suspend fun getScannedContacts(selectedAccounts: Set<String> = emptySet()): List<ScannedContact> {
        val ignored = dao.getAllIgnored()
        val ignoredKeys = ignored.map { it.lookupKey }.toSet()
        val classifications = db.contactClassificationDao().getAllClassifications()
            .associate { it.contactId to Classification.valueOf(it.classification) }
        return scanner.scanContacts(ignoredKeys, classifications, selectedAccounts)
    }

    fun getDeviceAccounts(): List<ContactAccount> {
        return scanner.queryDeviceAccounts()
    }

    suspend fun updateContactClassification(contactId: String, classification: Classification) {
        db.contactClassificationDao().insert(
            ContactClassification(contactId = contactId, classification = classification.name)
        )
    }

    suspend fun clearAllClassifications() {
        db.contactClassificationDao().clearAllClassifications()
    }

    suspend fun deleteContactsBatch(contactIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var anyDeleted = false
        for (id in contactIds) {
            val deletedRows = resolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(id)
            )
            if (deletedRows > 0) {
                anyDeleted = true
                db.contactClassificationDao().deleteByContactId(id)
            }
        }
        anyDeleted
    }

    suspend fun addContactToIgnoreList(scannedContact: ScannedContact) {
        dao.insert(
            IgnoredContact(
                lookupKey = scannedContact.lookupKey,
                displayName = scannedContact.displayName,
                phoneNumber = scannedContact.phoneNumbers.firstOrNull() ?: ""
            )
        )
    }

    suspend fun removeContactFromIgnoreList(lookupKey: String) {
        dao.deleteByLookupKey(lookupKey)
    }

    suspend fun deleteContact(lookupKey: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val uri = ContactsContract.Contacts.CONTENT_LOOKUP_URI
        val contactUri = ContactsContract.Contacts.getLookupUri(resolver, uri)
        if (contactUri != null) {
            val deletedRows = resolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(lookupKey)
            )
            deletedRows > 0
        } else {
            // Alternative deletion via name/number matching if raw contact ID is needed
            val contentUri = ContactsContract.Contacts.CONTENT_URI
            val cursor = resolver.query(contentUri, arrayOf(ContactsContract.Contacts._ID), "${ContactsContract.Contacts.LOOKUP_KEY} = ?", arrayOf(lookupKey), null)
            var success = false
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getString(0)
                    val deletedRows = resolver.delete(ContactsContract.RawContacts.CONTENT_URI, "${ContactsContract.RawContacts.CONTACT_ID} = ?", arrayOf(id))
                    success = deletedRows > 0
                }
            }
            success
        }
    }

    suspend fun createBackup(): File {
        return backupManager.createBackup()
    }

    suspend fun listBackups(): List<File> {
        return backupManager.listBackups()
    }

    suspend fun restoreBackup(file: File): Boolean {
        return backupManager.restoreBackup(file)
    }

    suspend fun createPartialBackup(contacts: List<ScannedContact>): File {
        return backupManager.createPartialBackup(contacts)
    }

    suspend fun exportToCsv(contacts: List<ScannedContact>): File {
        return backupManager.exportToCsv(contacts)
    }

    fun emailFile(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = if (file.extension == "csv") "text/csv" else "application/json"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Contacts Backup - Unused Contacts Cleaner")
            putExtra(android.content.Intent.EXTRA_TEXT, "Attached is the backup file: ${file.name}")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = android.content.Intent.createChooser(intent, "Email Backup File").apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    fun isPremium(): Boolean {
        return prefs.getBoolean("premium_status", false)
    }

    fun setPremium(premium: Boolean) {
        prefs.edit().putBoolean("premium_status", premium).apply()
    }

    fun getSelectedAccounts(): Set<String> {
        return prefs.getStringSet("selected_accounts", emptySet()) ?: emptySet()
    }

    fun saveSelectedAccounts(accounts: Set<String>) {
        prefs.edit().putStringSet("selected_accounts", accounts).apply()
    }

    private fun checkAndResetDailyLimits() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val lastReset = prefs.getString("last_limit_reset_date", "")
        if (today != lastReset) {
            prefs.edit()
                .putString("last_limit_reset_date", today)
                .putInt("classification_count", 0)
                .putInt("deletion_count", 0)
                .apply()
        }
    }

    fun getDailyClassificationsLeft(): Int {
        if (isPremium()) return Int.MAX_VALUE
        checkAndResetDailyLimits()
        val current = prefs.getInt("classification_count", 0)
        return maxOf(0, 50 - current)
    }

    fun incrementClassificationCount() {
        if (isPremium()) return
        checkAndResetDailyLimits()
        val current = prefs.getInt("classification_count", 0)
        prefs.edit().putInt("classification_count", current + 1).apply()
    }

    fun getDailyDeletionsLeft(): Int {
        if (isPremium()) return Int.MAX_VALUE
        checkAndResetDailyLimits()
        val current = prefs.getInt("deletion_count", 0)
        return maxOf(0, 20 - current)
    }

    fun incrementDeletionCount(count: Int) {
        if (isPremium()) return
        checkAndResetDailyLimits()
        val current = prefs.getInt("deletion_count", 0)
        prefs.edit().putInt("deletion_count", current + count).apply()
    }
}
