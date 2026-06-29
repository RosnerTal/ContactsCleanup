package com.rosnertal.unusedcontactscleaner.data

import android.content.Context
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Classification {
    KEEP, DELETE, UNSORTED
}

data class ScannedContact(
    val id: String,
    val lookupKey: String,
    val displayName: String,
    val phoneNumbers: List<String>,
    val emails: List<String> = emptyList(),
    val lastContactedTimestamp: Long?, // Null means never contacted or no log found
    val isIgnored: Boolean = false,
    val lastContactType: ContactType? = null,
    val classification: Classification = Classification.UNSORTED,
    val photoThumbnailUri: String? = null,
    val interactionCount: Int = 0
)

enum class ContactType {
    CALL, SMS, WHATSAPP
}

data class ContactAccount(
    val name: String,
    val type: String,
    val count: Int
)

class ContactScanner(private val context: Context) {

    fun queryDeviceAccounts(): List<ContactAccount> {
        val resolver = context.contentResolver
        val accountsMap = mutableMapOf<Pair<String, String>, Int>()
        val uri = ContactsContract.RawContacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.RawContacts.ACCOUNT_NAME,
            ContactsContract.RawContacts.ACCOUNT_TYPE
        )
        try {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                val typeIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: "Device"
                    val type = cursor.getString(typeIdx) ?: "local"
                    val key = Pair(name, type)
                    accountsMap[key] = (accountsMap[key] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            Log.e("ContactScanner", "Failed to query accounts", e)
        }
        return accountsMap.map { ContactAccount(it.key.first, it.key.second, it.value) }
    }

    suspend fun scanContacts(
        ignoredKeys: Set<String>,
        classifications: Map<String, Classification>,
        selectedAccounts: Set<String> = emptySet()
    ): List<ScannedContact> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val contactsList = mutableListOf<ScannedContact>()
        
        // 1. If filtering by accounts, find allowed Contact IDs
        val allowedContactIds = mutableSetOf<String>()
        val useFiltering = selectedAccounts.isNotEmpty()
        if (useFiltering) {
            val rawUri = ContactsContract.RawContacts.CONTENT_URI
            val rawProjection = arrayOf(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.RawContacts.CONTACT_ID
            )
            try {
                resolver.query(rawUri, rawProjection, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    val contactIdIdx = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: "Device"
                        val type = cursor.getString(typeIdx) ?: "local"
                        val contactId = cursor.getString(contactIdIdx)
                        if (contactId != null && selectedAccounts.contains("$name|$type")) {
                            allowedContactIds.add(contactId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ContactScanner", "Failed to query raw contacts for filtering", e)
            }
        }
        
        // 2. Fetch all contacts with phone numbers
        val contactsUri = ContactsContract.Contacts.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
        )
        
        val cursor = resolver.query(contactsUri, projection, null, null, null)
        val rawContactsMap = mutableMapOf<String, ScannedContact>() // ID -> ScannedContact
        val phoneToContactId = mutableMapOf<String, String>() // Phone number normalized -> Contact ID

        cursor?.use { c ->
            val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
            val lookupIndex = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
            val hasPhoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val photoIndex = c.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)

            while (c.moveToNext()) {
                val id = c.getString(idIndex)
                val lookup = c.getString(lookupIndex) ?: ""
                val name = c.getString(nameIndex) ?: "Unknown"
                val hasPhone = c.getInt(hasPhoneIndex) > 0
                val photoUri = c.getString(photoIndex)

                if (hasPhone) {
                    if (useFiltering && !allowedContactIds.contains(id)) {
                        continue
                    }
                    rawContactsMap[id] = ScannedContact(
                        id = id,
                        lookupKey = lookup,
                        displayName = name,
                        phoneNumbers = emptyList(),
                        lastContactedTimestamp = null,
                        isIgnored = ignoredKeys.contains(lookup),
                        photoThumbnailUri = photoUri
                    )
                }
            }
        }

        // 2. Fetch phone numbers for contacts
        val phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val phoneProjection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        
        val phoneCursor = resolver.query(phoneUri, phoneProjection, null, null, null)
        phoneCursor?.use { pc ->
            val contactIdIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val numberIndex = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (pc.moveToNext()) {
                val contactId = pc.getString(contactIdIndex)
                val rawNumber = pc.getString(numberIndex) ?: continue
                val normalizedNumber = normalizePhoneNumber(rawNumber)

                val contact = rawContactsMap[contactId]
                if (contact != null) {
                    rawContactsMap[contactId] = contact.copy(
                        phoneNumbers = contact.phoneNumbers + rawNumber
                    )
                    if (normalizedNumber.isNotEmpty()) {
                        phoneToContactId[normalizedNumber] = contactId
                    }
                }
            }
        }

        // 3. Scan Call Log for last interaction (supporting WhatsApp system logs)
        val lastCallMap = mutableMapOf<String, Long>() // ContactId -> Timestamp
        val lastWhatsAppCallMap = mutableMapOf<String, Long>() // ContactId -> Timestamp
        val callCountMap = mutableMapOf<String, Int>() // ContactId -> Count
        try {
            val callLogUri = CallLog.Calls.CONTENT_URI
            val callProjection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, "phone_account_component_name")
            val callCursor = try {
                resolver.query(callLogUri, callProjection, null, null, "${CallLog.Calls.DATE} DESC")
            } catch (e: Exception) {
                resolver.query(callLogUri, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE), null, null, "${CallLog.Calls.DATE} DESC")
            }

            callCursor?.use { cc ->
                val numberIndex = cc.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIndex = cc.getColumnIndex(CallLog.Calls.DATE)
                val componentIndex = cc.getColumnIndex("phone_account_component_name")

                while (cc.moveToNext()) {
                    val number = cc.getString(numberIndex) ?: continue
                    val date = cc.getLong(dateIndex)
                    val normalized = normalizePhoneNumber(number)
                    val contactId = phoneToContactId[normalized]
                    if (contactId != null) {
                        val isWhatsApp = if (componentIndex != -1) {
                            cc.getString(componentIndex)?.contains("com.whatsapp", ignoreCase = true) == true
                        } else {
                            false
                        }

                        if (isWhatsApp) {
                            if (!lastWhatsAppCallMap.containsKey(contactId)) {
                                lastWhatsAppCallMap[contactId] = date
                            }
                        } else {
                            if (!lastCallMap.containsKey(contactId)) {
                                lastCallMap[contactId] = date
                            }
                        }
                        callCountMap[contactId] = (callCountMap[contactId] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactScanner", "Failed to scan call logs", e)
        }

        // 4. Scan SMS for last interaction
        val lastSmsMap = mutableMapOf<String, Long>() // ContactId -> Timestamp
        val smsCountMap = mutableMapOf<String, Int>() // ContactId -> Count
        try {
            val smsUri = Telephony.Sms.CONTENT_URI
            val smsProjection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE)
            val smsCursor = resolver.query(smsUri, smsProjection, null, null, "${Telephony.Sms.DATE} DESC")
            smsCursor?.use { sc ->
                val addressIndex = sc.getColumnIndex(Telephony.Sms.ADDRESS)
                val dateIndex = sc.getColumnIndex(Telephony.Sms.DATE)

                while (sc.moveToNext()) {
                    val address = sc.getString(addressIndex) ?: continue
                    val date = sc.getLong(dateIndex)
                    val normalized = normalizePhoneNumber(address)
                    val contactId = phoneToContactId[normalized]
                    if (contactId != null) {
                        if (!lastSmsMap.containsKey(contactId)) {
                            lastSmsMap[contactId] = date
                        }
                        smsCountMap[contactId] = (smsCountMap[contactId] ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ContactScanner", "Failed to scan SMS logs", e)
        }

        // 5. Scan WhatsApp interactions from database (collected passively via notification listener)
        val whatsappInteractionsMap = try {
            val db = IgnoreDatabase.getDatabase(context)
            db.whatsappInteractionDao().getAllInteractions().associate { it.contactName to it.timestamp }
        } catch (e: Exception) {
            Log.e("ContactScanner", "Failed to fetch WhatsApp notification interactions", e)
            emptyMap()
        }

        // 6. Scan for emails
        val emailsMap = mutableMapOf<String, MutableList<String>>()
        try {
            val emailUri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
            val emailProjection = arrayOf(
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            val emailCursor = resolver.query(emailUri, emailProjection, null, null, null)
            emailCursor?.use { ec ->
                val contactIdIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val addressIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                while (ec.moveToNext()) {
                    val contactId = ec.getString(contactIdIdx) ?: continue
                    val address = ec.getString(addressIdx) ?: continue
                    emailsMap.getOrPut(contactId) { mutableListOf() }.add(address)
                }
            }
        } catch (e: Exception) {
            Log.e("ContactScanner", "Failed to scan emails", e)
        }

        // Combine logs
        rawContactsMap.values.map { contact ->
            val lastCall = lastCallMap[contact.id]
            val lastSms = lastSmsMap[contact.id]
            
            val lastWhatsAppCall = lastWhatsAppCallMap[contact.id]
            val lastWhatsAppNotif = whatsappInteractionsMap[contact.displayName]
            val lastWhatsApp = when {
                lastWhatsAppCall != null && lastWhatsAppNotif != null -> maxOf(lastWhatsAppCall, lastWhatsAppNotif)
                lastWhatsAppCall != null -> lastWhatsAppCall
                lastWhatsAppNotif != null -> lastWhatsAppNotif
                else -> null
            }
            
            val lastTimestamp = listOfNotNull(lastCall, lastSms, lastWhatsApp).maxOrNull()
            
            val type = when (lastTimestamp) {
                null -> null
                lastWhatsApp -> ContactType.WHATSAPP
                lastCall -> ContactType.CALL
                else -> ContactType.SMS
            }

            val contactClassification = classifications[contact.id] ?: Classification.UNSORTED
            val totalInteractions = (callCountMap[contact.id] ?: 0) + (smsCountMap[contact.id] ?: 0) + (if (lastWhatsApp != null) 1 else 0)

            contact.copy(
                lastContactedTimestamp = lastTimestamp,
                lastContactType = type,
                emails = emailsMap[contact.id] ?: emptyList(),
                classification = contactClassification,
                interactionCount = totalInteractions
            )
        }.sortedBy { it.displayName }
    }

    private fun normalizePhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "")
            .replace(Regex("^\\+972"), "0") // local normalization for Israel if applicable, or general cleanup
            .replace(Regex("^00"), "+")
    }
}
