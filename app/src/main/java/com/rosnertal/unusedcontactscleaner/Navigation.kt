package com.rosnertal.unusedcontactscleaner

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.rosnertal.unusedcontactscleaner.data.ContactsCleanerRepository
import com.rosnertal.unusedcontactscleaner.ui.main.MainScreen
import com.rosnertal.unusedcontactscleaner.ui.main.MainViewModel
import com.rosnertal.unusedcontactscleaner.ui.ignore.IgnoreListScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  val context = LocalContext.current.applicationContext
  val repository = remember { ContactsCleanerRepository(context) }
  val mainViewModel: MainViewModel = viewModel { MainViewModel(repository) }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          MainScreen(
            onNavigateToIgnoreList = { backStack.add(IgnoreList) },
            viewModel = mainViewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
        entry<IgnoreList> {
          IgnoreListScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            viewModel = mainViewModel,
            modifier = Modifier.fillMaxSize()
          )
        }
      },
  )
}

