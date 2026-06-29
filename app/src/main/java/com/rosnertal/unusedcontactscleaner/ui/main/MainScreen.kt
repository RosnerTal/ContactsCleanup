package com.rosnertal.unusedcontactscleaner.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.app.NotificationManagerCompat
import com.rosnertal.unusedcontactscleaner.R
import com.rosnertal.unusedcontactscleaner.data.ScannedContact
import com.rosnertal.unusedcontactscleaner.data.ContactType
import com.rosnertal.unusedcontactscleaner.data.Classification
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onNavigateToIgnoreList: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // SharedPreferences for first launch welcome/tutorial screen
    val prefs = remember { context.getSharedPreferences("unused_contacts_prefs", android.content.Context.MODE_PRIVATE) }
    var isFirstUse by remember { mutableStateOf(prefs.getBoolean("is_first_use", true)) }

    LaunchedEffect(Unit) {
        viewModel.initializeBilling(context)
    }

    if (isFirstUse) {
        OnboardingScreen(onComplete = {
            prefs.edit().putBoolean("is_first_use", false).apply()
            isFirstUse = false
        })
        return
    }

    // First time backup suggestion dialog
    var showFirstTimeBackupSuggest by remember {
        mutableStateOf(prefs.getBoolean("suggested_backup_on_first_use", true))
    }
    
    var isNotificationListenerEnabled by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isNotificationListenerEnabled = NotificationManagerCompat
                    .getEnabledListenerPackages(context)
                    .contains(context.packageName)
                viewModel.loadData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("ALL") } // ALL, KEEP, DELETE, UNSORTED
    var sortBy by remember { mutableStateOf("Name") } // Name, Inactivity, Most Contacted, Least Contacted
    
    var showSettingsMenu by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    
    var showDeletePrompt by remember { mutableStateOf(false) }
    var showFinalDeleteConfirmation by remember { mutableStateOf(false) }

    // File picker launcher for backup imports
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val tempFile = File(context.cacheDir, "imported_backup_${System.currentTimeMillis()}.json")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                viewModel.restoreBackup(tempFile)
            } catch (e: Exception) {
                // error feedback handled via repository state if fails
            }
        }
    }

    // Calculate tab counts
    val totalCount = state.contacts.size
    val keepCount = state.contacts.count { it.classification == Classification.KEEP }
    val deleteCount = state.contacts.count { it.classification == Classification.DELETE }
    val unsortedCount = state.contacts.count { it.classification == Classification.UNSORTED }

    // Filter and Sort contacts
    val filteredContacts = remember(state.contacts, searchQuery, selectedTab, sortBy) {
        var list = state.contacts

        // 1. Filter by Tab
        if (selectedTab != "ALL") {
            list = list.filter { contact ->
                when (selectedTab) {
                    "KEEP" -> contact.classification == Classification.KEEP
                    "DELETE" -> contact.classification == Classification.DELETE
                    "UNSORTED" -> contact.classification == Classification.UNSORTED
                    else -> true
                }
            }
        }

        // 2. Filter by Search Query
        if (searchQuery.isNotBlank()) {
            list = list.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                contact.phoneNumbers.any { num -> num.contains(searchQuery) }
            }
        }

        // 3. Sort
        when (sortBy) {
            "Inactivity" -> list.sortedWith(
                compareBy<ScannedContact> { it.lastContactedTimestamp == null }
                    .thenBy { it.lastContactedTimestamp ?: 0L }
                    .thenBy { it.displayName }
            )
            "Most Contacted" -> list.sortedWith(
                compareByDescending<ScannedContact> { it.interactionCount }
                    .thenBy { it.displayName }
            )
            "Least Contacted" -> list.sortedWith(
                compareBy<ScannedContact> { it.interactionCount }
                    .thenBy { it.displayName }
            )
            else -> list.sortedBy { it.displayName.lowercase() }
        }
    }

    val selectedDeleteCount = remember(state.contacts) {
        state.contacts.count { it.classification == Classification.DELETE }
    }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(state.successMessage) {
        state.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF0F172A), // Slate 900
            Color(0xFF1E293B)  // Slate 800
        )
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Unused Contacts Cleaner",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 20.sp
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSettingsMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Settings",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showSettingsMenu,
                            onDismissRequest = { showSettingsMenu = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            DropdownMenuItem(
                                text = { Text("Filter Accounts", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showSettingsMenu = false
                                    viewModel.setShowAccountsDialog(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Ignore List", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showSettingsMenu = false
                                    onNavigateToIgnoreList()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to Unsorted", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showSettingsMenu = false
                                    viewModel.resetAllClassifications()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Backup & Restore...", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Backup, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showSettingsMenu = false
                                    showBackupDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Upgrade to Premium", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFDE047)) },
                                onClick = {
                                    showSettingsMenu = false
                                    viewModel.setShowPremiumDialog(true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Contact Us", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.ContactSupport, contentDescription = null, tint = Color.White) },
                                onClick = {
                                    showSettingsMenu = false
                                    viewModel.setShowSupportDialog(true)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        floatingActionButton = {},
        modifier = modifier.background(gradientBg)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(gradientBg)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Custom Tab Row matching the mockup with contact counts added
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White.copy(alpha = 0.08f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                        .padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf(
                            Triple("ALL", "All", totalCount),
                            Triple("KEEP", "Keep", keepCount),
                            Triple("DELETE", "Delete", deleteCount),
                            Triple("UNSORTED", "Unsorted", unsortedCount)
                        ).forEach { (tabKey, label, count) ->
                            val isSelected = selectedTab == tabKey
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (isSelected) Color(0xFFFDE047) else Color.Transparent)
                                    .clickable { selectedTab = tabKey }
                            ) {
                                Text(
                                    text = "$label ($count)",
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.8f),
                                    fontSize = 11.sp, // Adjusted to fit counts comfortably
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sort and Search query layout row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sort By: ", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        Box {
                            Button(
                                onClick = { sortMenuExpanded = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFFDE047).copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(sortBy, color = Color.White, fontSize = 13.sp)
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = sortMenuExpanded,
                                onDismissRequest = { sortMenuExpanded = false },
                                modifier = Modifier.background(Color(0xFF1E293B))
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Name", color = Color.White) },
                                    onClick = {
                                        sortBy = "Name"
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Inactivity", color = Color.White) },
                                    onClick = {
                                        sortBy = "Inactivity"
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Most Contacted", color = Color.White) },
                                    onClick = {
                                        sortBy = "Most Contacted"
                                        sortMenuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Least Contacted", color = Color.White) },
                                    onClick = {
                                        sortBy = "Least Contacted"
                                        sortMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Display free limitations left if not premium
                    if (!state.isPremium) {
                        Card(
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFDE047).copy(alpha = 0.1f)
                            ),
                            modifier = Modifier
                                .border(0.5.dp, Color(0xFFFDE047).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "Free Limit: ${state.dailyClassificationsLeft} left",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE047)
                            )
                        }
                    }

                    // Search Input Button
                    var searchExpanded by remember { mutableStateOf(false) }
                    if (searchExpanded) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search...", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFDE047),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .width(140.dp)
                                .height(46.dp),
                            trailingIcon = {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    searchExpanded = false
                                }) {
                                    Icon(Icons.Default.Clear, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    } else {
                        IconButton(onClick = { searchExpanded = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Batch Deletion execute banner if contacts are selected for deletion
                if (selectedDeleteCount > 0) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFEF4444).copy(alpha = 0.15f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Selected for deletion: $selectedDeleteCount contacts",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = { showDeletePrompt = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEF4444)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Delete All", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (filteredContacts.isEmpty() && isNotificationListenerEnabled && state.backups.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isEmpty()) "No contacts in this category" else "No matching contacts found",
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // WhatsApp banner inside the LazyColumn so it scrolls out of view naturally
                        if (!isNotificationListenerEnabled) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color(0xFF1E3A8A).copy(alpha = 0.8f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "WhatsApp Scanning Disabled",
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                fontSize = 14.sp
                                            )
                                            Text(
                                                text = "Grant notification access to track your WhatsApp chats.",
                                                color = Color.White.copy(alpha = 0.8f),
                                                fontSize = 12.sp
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                try {
                                                    context.startActivity(
                                                        android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF10B981)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("Enable", color = Color.White, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }

                        // Backup First card inside the LazyColumn so it scrolls away
                        item {
                            BackupStatusCard(
                                backups = state.backups,
                                onRestore = { viewModel.restoreBackup(it) },
                                onScan = { viewModel.loadData() }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        items(filteredContacts, key = { it.id }) { contact ->
                            ContactItemCard(
                                contact = contact,
                                onSelect = { viewModel.setSelectedContact(contact) },
                                onClassificationChange = { classification ->
                                    viewModel.updateClassification(contact.id, classification)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Expanded details dialog view
    state.selectedContact?.let { contact ->
        ContactDetailsDialog(
            contact = contact,
            onDismiss = { viewModel.setSelectedContact(null) }
        )
    }

    // Backup category option grouping dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            title = { Text("Backup & Restore Options", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select a backup or export option below:", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = {
                            showBackupDialog = false
                            viewModel.createBackup()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDE047)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create JSON Backup File", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            showBackupDialog = false
                            viewModel.exportContactsCsv()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Contacts to CSV", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            showBackupDialog = false
                            viewModel.emailContactsCsv()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Email CSV Contact List", color = Color.White, fontWeight = FontWeight.Bold)
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), modifier = Modifier.padding(vertical = 4.dp))

                    Button(
                        onClick = {
                            showBackupDialog = false
                            importLauncher.launch("application/json")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import JSON Backup File", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Close", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Suggested Backup on First launch dialog
    if (showFirstTimeBackupSuggest) {
        AlertDialog(
            onDismissRequest = { 
                prefs.edit().putBoolean("suggested_backup_on_first_use", false).apply()
                showFirstTimeBackupSuggest = false 
            },
            title = { Text("Backup Recommended", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Welcome to Unused Contacts Cleaner! We highly recommend creating and emailing a backup file of all your contacts now so you can restore them at any time.", color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.edit().putBoolean("suggested_backup_on_first_use", false).apply()
                        showFirstTimeBackupSuggest = false
                        viewModel.emailAllContactsBackup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDE047)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Create & Share Backup", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putBoolean("suggested_backup_on_first_use", false).apply()
                        showFirstTimeBackupSuggest = false
                    }
                ) {
                    Text("Maybe Later", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Pre-deletion backup chooser dialog (emails CSV directly now)
    if (showDeletePrompt) {
        AlertDialog(
            onDismissRequest = { showDeletePrompt = false },
            title = { Text("Backup before deletion?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Would you like to email a CSV backup of your contacts before proceeding with the deletion?", color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Button(
                        onClick = {
                            showDeletePrompt = false
                            viewModel.emailAllContactsBackup()
                            showFinalDeleteConfirmation = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDE047)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Email All Contacts CSV", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showDeletePrompt = false
                            viewModel.emailDeletingContactsBackup()
                            showFinalDeleteConfirmation = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Email Deleting Only CSV", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            showDeletePrompt = false
                            showFinalDeleteConfirmation = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Do Not Backup", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeletePrompt = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Final deletion confirmation dialog
    if (showFinalDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showFinalDeleteConfirmation = false },
            title = { Text("Confirm Permanent Deletion", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete these $selectedDeleteCount contacts? This action will remove them from your device contacts book and cannot be undone.", color = Color.White.copy(alpha = 0.8f)) },
            confirmButton = {
                Button(
                    onClick = {
                        showFinalDeleteConfirmation = false
                        viewModel.executeBatchDeletions()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Delete Permanently", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showFinalDeleteConfirmation = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Simulated Billing Dialog for upgrade
    if (state.showPremiumDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowPremiumDialog(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFDE047), modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Unlock Premium Version", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Lifetime License: $3.99",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981),
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "You are currently on the Free Basic version which limits classifications to 50/day and deletions to 20/day.\n\nUpgrade to unlock full features:\n\n• Unlimited classifications & deletions\n• Select specific contact accounts\n• Premium dashboard analytics\n• Dedicated support access",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val activity = context as? android.app.Activity
                        if (activity != null) {
                            viewModel.makePurchase(activity)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Unlock Lifetime Premium ($3.99)", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowPremiumDialog(false) }) {
                    Text("Close", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Account selection dialog
    if (state.showAccountsDialog) {
        val selectedAccounts = remember { mutableStateOf(state.selectedAccounts.toMutableSet()) }

        AlertDialog(
            onDismissRequest = { viewModel.setShowAccountsDialog(false) },
            title = { Text("Filter Accounts", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Select which account contacts to display:", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (state.deviceAccounts.isEmpty()) {
                        Text("No accounts found", color = Color.White.copy(alpha = 0.8f))
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 240.dp)) {
                            items(state.deviceAccounts) { account ->
                                val key = "${account.name}|${account.type}"
                                val isChecked = selectedAccounts.value.contains(key)

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val newSet = selectedAccounts.value.toMutableSet()
                                            if (isChecked) {
                                                newSet.remove(key)
                                            } else {
                                                newSet.add(key)
                                            }
                                            selectedAccounts.value = newSet
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            val newSet = selectedAccounts.value.toMutableSet()
                                            if (checked == true) {
                                                newSet.add(key)
                                            } else {
                                                newSet.remove(key)
                                            }
                                            selectedAccounts.value = newSet
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFDE047), checkmarkColor = Color.Black)
                                    )

                                    // Icon indicating source
                                    val icon = when {
                                        account.type.contains("google", ignoreCase = true) -> Icons.Default.Cloud
                                        account.type.contains("whatsapp", ignoreCase = true) -> Icons.Default.Chat
                                        else -> Icons.Default.PhoneAndroid
                                    }
                                    Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                                    
                                    Spacer(modifier = Modifier.width(8.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(account.name, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(account.type.substringAfterLast("."), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                                    }

                                    Text("(${account.count})", color = Color(0xFFFDE047), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveAccountSelection(selectedAccounts.value)
                        viewModel.setShowAccountsDialog(false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDE047)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Apply Filter", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowAccountsDialog(false) }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Support Dialog Contact Us
    if (state.showSupportDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowSupportDialog(false) },
            title = { Text("Contact Support", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Have questions, issues, or suggestions? Reach out directly!", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Support Email:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                    Text("support@unusedcontacts.com", color = Color(0xFFFDE047), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Response rate: usually within 24 hours.", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.setShowSupportDialog(false)
                        val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                            data = android.net.Uri.parse("mailto:support@unusedcontacts.com")
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Unused Contacts Cleaner Support Request")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // ignore or let system handle
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Compose Support Email", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setShowSupportDialog(false) }) {
                    Text("Close", color = Color.White.copy(alpha = 0.6f))
                }
            },
            containerColor = Color(0xFF1E293B),
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun ContactItemCard(
    contact: ScannedContact,
    onSelect: () -> Unit,
    onClassificationChange: (Classification) -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(contact.photoThumbnailUri) { mutableStateOf<android.graphics.Bitmap?>(null) }

    LaunchedEffect(contact.photoThumbnailUri) {
        if (contact.photoThumbnailUri != null) {
            try {
                val uri = android.net.Uri.parse(contact.photoThumbnailUri)
                context.contentResolver.openInputStream(uri).use { stream ->
                    bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                }
            } catch (e: Exception) {
                bitmap = null
            }
        } else {
            bitmap = null
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Photo Avatar or Circular initials avatar
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = contact.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White.copy(alpha = 0.1f), CircleShape)
                    ) {
                        val initials = contact.displayName.split(" ")
                            .take(2)
                            .mapNotNull { it.firstOrNull()?.uppercase() }
                            .joinToString("")
                        Text(
                            text = if (initials.isNotEmpty()) initials else "?",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = contact.displayName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = contact.phoneNumbers.firstOrNull() ?: "No number",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    if (contact.lastContactedTimestamp != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = null,
                                tint = Color(0xFFFDE047),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val days = ((System.currentTimeMillis() - contact.lastContactedTimestamp) / (1000 * 60 * 60 * 24)).toInt()
                            val text = when {
                                days == 0 -> "today (${contact.lastContactType?.name ?: "LOG"})"
                                days == 1 -> "1 day ago (${contact.lastContactType?.name ?: "LOG"})"
                                days > 365 -> "${days / 365} years ago (${contact.lastContactType?.name ?: "LOG"})"
                                else -> "$days days ago (${contact.lastContactType?.name ?: "LOG"})"
                            }
                            Text(
                                text = text,
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Classification state buttons matching the mockup (Trash, Check, Help)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Delete Button (Red outline/glow)
                ClassificationButton(
                    isSelected = contact.classification == Classification.DELETE,
                    activeColor = Color(0xFFEF4444),
                    icon = Icons.Default.Delete,
                    onClick = { onClassificationChange(Classification.DELETE) }
                )
                // Keep Button (Green outline/glow)
                ClassificationButton(
                    isSelected = contact.classification == Classification.KEEP,
                    activeColor = Color(0xFF10B981),
                    icon = Icons.Default.Check,
                    onClick = { onClassificationChange(Classification.KEEP) }
                )
                // Unsorted Button (Blue outline/glow)
                ClassificationButton(
                    isSelected = contact.classification == Classification.UNSORTED,
                    activeColor = Color(0xFF3B82F6),
                    icon = Icons.Default.Help,
                    onClick = { onClassificationChange(Classification.UNSORTED) }
                )
            }
        }
    }
}

@Composable
fun ClassificationButton(
    isSelected: Boolean,
    activeColor: Color,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .border(
                width = 1.dp,
                color = if (isSelected) activeColor else activeColor.copy(alpha = 0.25f),
                shape = RoundedCornerShape(8.dp)
            )
            .background(
                color = if (isSelected) activeColor.copy(alpha = 0.2f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) activeColor else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun ContactDetailsDialog(
    contact: ScannedContact,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0xFFFDE047).copy(alpha = 0.15f), CircleShape)
                ) {
                    Text(
                        contact.displayName.firstOrNull()?.uppercase()?.toString() ?: "?",
                        color = Color(0xFFFDE047),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(contact.displayName, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 18.sp)
                    Text("Classification: ${contact.classification.name}", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text("Phone Numbers:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                if (contact.phoneNumbers.isEmpty()) {
                    Text("None", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    contact.phoneNumbers.forEach { num ->
                        Text(num, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Emails:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                if (contact.emails.isEmpty()) {
                    Text("None", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                } else {
                    contact.emails.forEach { email ->
                        Text(email, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text("Interaction History:", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                val dateStr = if (contact.lastContactedTimestamp != null) formatDate(contact.lastContactedTimestamp) else "None"
                Text(
                    text = "Last interacted via ${contact.lastContactType?.name ?: "Log"} on $dateStr",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
                Text(
                    text = "Total interactions on device: ${contact.interactionCount} logs",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFFFDE047))
            }
        },
        containerColor = Color(0xFF1E293B),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun BackupStatusCard(
    backups: List<File>,
    onRestore: (File) -> Unit,
    onScan: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.08f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        "Backup First",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Text(
                        if (backups.isEmpty()) "No backups created yet" else "${backups.size} Backups Available",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan", tint = Color.White)
                    }
                    if (backups.isNotEmpty()) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Show Backups",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(8.dp))
                    backups.take(5).forEach { backupFile ->
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                formatDate(backupFile.lastModified()),
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = { onRestore(backupFile) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Restore", fontSize = 12.sp, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    var currentPage by remember { mutableStateOf(0) }
    val slides = listOf(
        OnboardingSlide(
            title = "Unused Contacts Cleaner",
            description = "Welcome! Take control of your address book. Easily find, filter, and clean up contacts you haven't interacted with in years.",
            icon = Icons.Default.CleaningServices
        ),
        OnboardingSlide(
            title = "Passive Interaction Tracking",
            description = "The app scans your device's Call Logs, SMS logs, and WhatsApp notification interceptions to calculate exactly when and how often you last spoke.",
            icon = Icons.Default.History
        ),
        OnboardingSlide(
            title = "Keep, Delete, or Unsorted",
            description = "Classify each contact with simple action toggles:\n\n• Checkmark: Keep contact permanently\n• Trash: Mark for batch deletion\n• Help: Keep unsorted",
            icon = Icons.Default.Class
        ),
        OnboardingSlide(
            title = "Safe Batch Deletion",
            description = "Delete all marked contacts in a single batch. Choose to email a full or partial CSV/JSON backup beforehand to ensure your data is always safe.",
            icon = Icons.Default.Security
        )
    )

    val currentSlide = slides[currentPage]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Cool App Logo image rendered inside onboarding
                Image(
                    painter = painterResource(id = R.drawable.app_logo_cleaner),
                    contentDescription = "Unused Contacts Cleaner Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(24.dp))
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = currentSlide.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = currentSlide.description,
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Indicator and Buttons
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Page Indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 32.dp)
                ) {
                    slides.forEachIndexed { index, _ ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    color = if (index == currentPage) Color(0xFFFDE047) else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentPage > 0) {
                        TextButton(onClick = { currentPage-- }) {
                            Text("Back", color = Color.White.copy(alpha = 0.6f))
                        }
                    } else {
                        Spacer(modifier = Modifier.width(80.dp))
                    }

                    if (currentPage < slides.size - 1) {
                        Button(
                            onClick = { currentPage++ },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFDE047)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Next", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = onComplete,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Get Started!", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

data class OnboardingSlide(
    val title: String,
    val description: String,
    val icon: ImageVector
)

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
