package com.rosnertal.unusedcontactscleaner.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ContactBackupItem(
    val displayName: String,
    val phoneNumbers: List<String>,
    val emails: List<String> = emptyList()
)

@Serializable
data class ContactBackup(
    val timestamp: Long,
    val items: List<ContactBackupItem>
)

class BackupManager(private val context: Context) {

    private val backupDir = File(context.filesDir, "backups")

    init {
        if (!backupDir.exists()) {
            backupDir.mkdirs()
        }
    }

    suspend fun listBackups(): List<File> = withContext(Dispatchers.IO) {
        backupDir.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    suspend fun createBackup(): File = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val backupItems = mutableListOf<ContactBackupItem>()

        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )

        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)",
            arrayOf(
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
            ),
            null
        )

        class TempContact {
            var displayName: String = ""
            val phoneNumbers = mutableSetOf<String>()
            val emails = mutableSetOf<String>()
        }

        val contactMap = mutableMapOf<String, TempContact>()

        cursor?.use { c ->
            val contactIdIdx = c.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val mimetypeIdx = c.getColumnIndex(ContactsContract.Data.MIMETYPE)
            val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)
            val numberIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val emailIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)

            while (c.moveToNext()) {
                val contactId = c.getString(contactIdIdx) ?: continue
                val mimetype = c.getString(mimetypeIdx) ?: continue
                val temp = contactMap.getOrPut(contactId) { TempContact() }

                when (mimetype) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                        val name = c.getString(nameIdx)
                        if (name != null) {
                            temp.displayName = name
                        }
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                        val number = c.getString(numberIdx)
                        if (number != null) {
                            temp.phoneNumbers.add(number)
                        }
                    }
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                        val email = c.getString(emailIdx)
                        if (email != null) {
                            temp.emails.add(email)
                        }
                    }
                }
            }
        }

        contactMap.values.forEach { temp ->
            if (temp.displayName.isNotBlank()) {
                backupItems.add(
                    ContactBackupItem(
                        displayName = temp.displayName,
                        phoneNumbers = temp.phoneNumbers.toList(),
                        emails = temp.emails.toList()
                    )
                )
            }
        }

        val backup = ContactBackup(
            timestamp = System.currentTimeMillis(),
            items = backupItems
        )

        val jsonString = Json.encodeToString(backup)
        val file = File(backupDir, "backup_${System.currentTimeMillis()}.json")
        file.writeText(jsonString)
        file
    }

    suspend fun createPartialBackup(contacts: List<ScannedContact>): File = withContext(Dispatchers.IO) {
        val backupItems = contacts.map { contact ->
            ContactBackupItem(
                displayName = contact.displayName,
                phoneNumbers = contact.phoneNumbers,
                emails = contact.emails
            )
        }
        val backup = ContactBackup(
            timestamp = System.currentTimeMillis(),
            items = backupItems
        )
        val jsonString = Json.encodeToString(backup)
        val file = File(backupDir, "deleting_contacts_${System.currentTimeMillis()}.json")
        file.writeText(jsonString)
        file
    }

    suspend fun exportToCsv(contacts: List<ScannedContact>): File = withContext(Dispatchers.IO) {
        val csvHeader = "Name,Phone Numbers,Emails,Classification,Last Interaction Date,Total Interactions\n"
        val csvBody = StringBuilder()
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        
        for (contact in contacts) {
            val name = escapeCsv(contact.displayName)
            val phones = escapeCsv(contact.phoneNumbers.joinToString(";"))
            val emails = escapeCsv(contact.emails.joinToString(";"))
            val classification = contact.classification.name
            val lastDate = if (contact.lastContactedTimestamp != null) sdf.format(Date(contact.lastContactedTimestamp)) else "Never"
            val totalInteractions = contact.interactionCount
            
            csvBody.append("$name,$phones,$emails,$classification,$lastDate,$totalInteractions\n")
        }
        
        val file = File(backupDir, "contacts_export_${System.currentTimeMillis()}.csv")
        file.writeText(csvHeader + csvBody.toString())
        file
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }

    suspend fun restoreBackup(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val jsonString = file.readText()
            val backup = Json.decodeFromString<ContactBackup>(jsonString)
            
            // Loop and batch insert using Operations
            val operations = ArrayList<ContentProviderOperation>()

            for (item in backup.items) {
                val rawContactIndex = operations.size
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )

                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, item.displayName)
                        .build()
                )

                for (number in item.phoneNumbers) {
                    operations.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                            .build()
                    )
                }

                for (email in item.emails) {
                    operations.add(
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                            .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                            .build()
                    )
                }

                // Batch execute to prevent memory/performance issues
                if (operations.size > 200) {
                    context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                    operations.clear()
                }
            }

            if (operations.isNotEmpty()) {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            }
            true
        } catch (e: Exception) {
            Log.e("BackupManager", "Error restoring contacts", e)
            false
        }
    }
}
