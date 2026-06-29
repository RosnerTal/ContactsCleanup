package com.example.contactscleaner.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.contactscleaner.data.ContactsCleanerRepository
import com.example.contactscleaner.data.ScannedContact
import com.example.contactscleaner.data.IgnoredContact
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

import com.example.contactscleaner.data.Classification

data class MainUiState(
    val isLoading: Boolean = false,
    val contacts: List<ScannedContact> = emptyList(),
    val ignoredContacts: List<IgnoredContact> = emptyList(),
    val backups: List<File> = emptyList(),
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val selectedContact: ScannedContact? = null,
    val isPremium: Boolean = false,
    val selectedAccounts: Set<String> = emptySet(),
    val deviceAccounts: List<com.example.contactscleaner.data.ContactAccount> = emptyList(),
    val dailyClassificationsLeft: Int = 50,
    val dailyDeletionsLeft: Int = 20,
    val showPremiumDialog: Boolean = false,
    val showAccountsDialog: Boolean = false,
    val showSupportDialog: Boolean = false
)

class MainViewModel(private val repository: ContactsCleanerRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeIgnoreList()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val premium = repository.isPremium()
                val selected = repository.getSelectedAccounts()
                val scanned = repository.getScannedContacts(selected)
                val listBackups = repository.listBackups()
                val accounts = repository.getDeviceAccounts()
                val classLeft = repository.getDailyClassificationsLeft()
                val delLeft = repository.getDailyDeletionsLeft()
                
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        contacts = scanned,
                        backups = listBackups,
                        isPremium = premium,
                        selectedAccounts = selected,
                        deviceAccounts = accounts,
                        dailyClassificationsLeft = classLeft,
                        dailyDeletionsLeft = delLeft
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Scan failed: ${e.message}") }
            }
        }
    }

    private fun observeIgnoreList() {
        viewModelScope.launch {
            repository.getIgnoredContactsFlow().collect { ignored ->
                _uiState.update { it.copy(ignoredContacts = ignored) }
            }
        }
    }

    fun ignoreContact(contact: ScannedContact) {
        viewModelScope.launch {
            repository.addContactToIgnoreList(contact)
            loadData()
            _uiState.update { it.copy(successMessage = "${contact.displayName} added to Ignore List") }
        }
    }

    fun unignoreContact(lookupKey: String) {
        viewModelScope.launch {
            repository.removeContactFromIgnoreList(lookupKey)
            loadData()
            _uiState.update { it.copy(successMessage = "Contact removed from Ignore List") }
        }
    }

    fun deleteContact(contact: ScannedContact) {
        viewModelScope.launch {
            val success = repository.deleteContact(contact.id)
            if (success) {
                loadData()
                _uiState.update { it.copy(successMessage = "Successfully deleted ${contact.displayName}") }
            } else {
                _uiState.update { it.copy(errorMessage = "Could not delete ${contact.displayName}") }
            }
        }
    }

    fun createBackup() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val file = repository.createBackup()
                val listBackups = repository.listBackups()
                _uiState.update { 
                    it.copy(
                        isLoading = false,
                        backups = listBackups,
                        successMessage = "Backup created: ${file.name}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Backup failed: ${e.message}") }
            }
        }
    }

    fun restoreBackup(file: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val success = repository.restoreBackup(file)
            _uiState.update { it.copy(isLoading = false) }
            if (success) {
                loadData()
                _uiState.update { it.copy(successMessage = "Successfully restored contacts!") }
            } else {
                _uiState.update { it.copy(errorMessage = "Failed to restore backup") }
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun updateClassification(contactId: String, classification: Classification) {
        viewModelScope.launch {
            val contact = _uiState.value.contacts.find { it.id == contactId }
            if (contact != null && contact.classification == Classification.UNSORTED && classification != Classification.UNSORTED) {
                if (!repository.isPremium() && repository.getDailyClassificationsLeft() <= 0) {
                    _uiState.update { it.copy(showPremiumDialog = true) }
                    return@launch
                }
                repository.incrementClassificationCount()
            }
            repository.updateContactClassification(contactId, classification)
            loadData()
        }
    }

    fun executeBatchDeletions() {
        viewModelScope.launch {
            val toDelete = _uiState.value.contacts.filter { it.classification == Classification.DELETE }
            if (toDelete.isEmpty()) return@launch
            val count = toDelete.size
            if (!repository.isPremium() && count > repository.getDailyDeletionsLeft()) {
                _uiState.update { it.copy(showPremiumDialog = true) }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            val success = repository.deleteContactsBatch(toDelete.map { it.id })
            _uiState.update { it.copy(isLoading = false) }
            if (success) {
                repository.incrementDeletionCount(count)
                loadData()
                _uiState.update { it.copy(successMessage = "Successfully deleted selected contacts") }
            } else {
                _uiState.update { it.copy(errorMessage = "Batch deletion failed") }
            }
        }
    }

    fun resetAllClassifications() {
        viewModelScope.launch {
            repository.clearAllClassifications()
            loadData()
            _uiState.update { it.copy(successMessage = "All classifications reset to unsorted") }
        }
    }

    fun setSelectedContact(contact: ScannedContact?) {
        _uiState.update { it.copy(selectedContact = contact) }
    }

    fun exportContactsCsv() {
        viewModelScope.launch {
            try {
                val file = repository.exportToCsv(_uiState.value.contacts)
                _uiState.update { it.copy(successMessage = "CSV exported to: ${file.name}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Export to CSV failed: ${e.message}") }
            }
        }
    }

    fun emailContactsCsv() {
        viewModelScope.launch {
            try {
                val file = repository.exportToCsv(_uiState.value.contacts)
                repository.emailFile(file)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Emailing CSV failed: ${e.message}") }
            }
        }
    }

    fun emailAllContactsBackup() {
        viewModelScope.launch {
            try {
                val file = repository.exportToCsv(_uiState.value.contacts)
                repository.emailFile(file)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Emailing backup failed: ${e.message}") }
            }
        }
    }

    private var billingManager: com.example.contactscleaner.data.BillingManager? = null

    fun initializeBilling(context: android.content.Context) {
        if (billingManager == null) {
            billingManager = com.example.contactscleaner.data.BillingManager(
                context = context.applicationContext,
                repository = repository,
                onUpgradeSuccess = {
                    loadData()
                    _uiState.update { it.copy(showPremiumDialog = false, successMessage = "Lifetime Premium Activated!") }
                },
                onError = { error ->
                    _uiState.update { it.copy(errorMessage = error) }
                }
            )
        }
    }

    fun makePurchase(activity: android.app.Activity) {
        billingManager?.launchBillingFlow(activity) ?: run {
            _uiState.update { it.copy(errorMessage = "Billing system is not initialized.") }
        }
    }

    fun emailDeletingContactsBackup() {
        viewModelScope.launch {
            try {
                val toDelete = _uiState.value.contacts.filter { it.classification == Classification.DELETE }
                val file = repository.exportToCsv(toDelete)
                repository.emailFile(file)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Emailing backup failed: ${e.message}") }
            }
        }
    }

    fun saveAccountSelection(accounts: Set<String>) {
        viewModelScope.launch {
            repository.saveSelectedAccounts(accounts)
            loadData()
        }
    }

    fun setShowPremiumDialog(show: Boolean) {
        _uiState.update { it.copy(showPremiumDialog = show) }
    }

    fun setShowAccountsDialog(show: Boolean) {
        _uiState.update { it.copy(showAccountsDialog = show) }
    }

    fun setShowSupportDialog(show: Boolean) {
        _uiState.update { it.copy(showSupportDialog = show) }
    }
}
