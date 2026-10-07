package com.strata.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.strata.app.Session
import com.strata.app.share.ShareInbox
import com.strata.app.ui.chat.ChatListScreen
import com.strata.app.ui.chat.ChatListViewModel
import com.strata.app.ui.chat.ConversationScreen
import com.strata.app.ui.chat.ConversationViewModel
import com.strata.app.ui.dashboard.DashboardScreen
import com.strata.app.ui.dashboard.DashboardViewModel
import com.strata.app.ui.explorer.ExplorerScreen
import com.strata.app.ui.explorer.ExplorerViewModel
import com.strata.app.ui.explorer.ProductDetailScreen
import com.strata.app.ui.explorer.SourceDetailScreen
import com.strata.app.ui.setup.SetupScreen
import com.strata.app.ui.setup.SetupViewModel
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    DASHBOARD("dashboard", "Dashboard", Icons.Rounded.Insights),
    CHAT("chats", "Chat", Icons.Rounded.Forum),
    DATA("data", "Data", Icons.Rounded.TableRows),
    SETUP("setup", "Setup", Icons.Rounded.Tune),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StrataNavigationBar(current: String?, onSelect: (TopDestination) -> Unit) {
    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TopDestination.entries.forEach { d ->
            ShortNavigationBarItem(
                selected = current == d.route,
                onClick = { onSelect(d) },
                icon = { Icon(d.icon, null) },
                label = { Text(d.label) },
                colors = ShortNavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColorTopIconPosition = MaterialTheme.colorScheme.primary,
                    selectedTextColorStartIconPosition = MaterialTheme.colorScheme.onPrimary,
                    selectedIndicatorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
fun AppRoot(session: Session, shareInbox: ShareInbox) {
    val nav = rememberNavController()
    val shared by shareInbox.pending.collectAsStateWithLifecycle()
    // A document shared from another app starts a new conversation with it attached.
    LaunchedEffect(shared) {
        val bundle = shareInbox.take() ?: return@LaunchedEffect
        val chatId = session.chats.newChat()
        session.drafts[chatId] = bundle
        nav.navigate("chat/$chatId") { launchSingleTop = true }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    val topLevel = TopDestination.entries.any { it.route == route }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (topLevel) StrataNavigationBar(route) { d ->
                nav.navigate(d.route) {
                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = TopDestination.DASHBOARD.route) {
            composable(TopDestination.DASHBOARD.route) {
                val vm = viewModel { DashboardViewModel(session) }
                val state by vm.state.collectAsStateWithLifecycle()
                DashboardScreen(state, vm::setRange, onOpenChat = { nav.navigate(TopDestination.CHAT.route) }, contentPadding = padding)
            }
            composable(TopDestination.CHAT.route) {
                val vm = viewModel { ChatListViewModel(session) }
                val chats by vm.chats.collectAsStateWithLifecycle()
                val runs by vm.runs.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                ChatListScreen(
                    chats, runs,
                    onOpen = { nav.navigate("chat/$it") },
                    onNew = { scope.launch { nav.navigate("chat/${vm.newChat()}") } },
                    onDelete = { vm.delete(it) },
                    contentPadding = padding,
                )
            }
            composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val chatId = entry.arguments!!.getLong("id")
                ConversationRoute(session, chatId, onBack = { nav.popBackStack() }, onOpenSetup = { nav.navigate(TopDestination.SETUP.route) })
            }
            composable(TopDestination.DATA.route) {
                val vm = viewModel { ExplorerViewModel(session) }
                val data by vm.data.collectAsStateWithLifecycle()
                LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
                ExplorerScreen(
                    data,
                    onOpenProduct = { nav.navigate("product/$it") },
                    onOpenSource = { nav.navigate("source/$it") },
                    onUndoImport = vm::undoImport,
                    onSaveProduct = { vm.saveProduct(it) },
                    onSaveTransaction = { vm.saveTransaction(it) },
                    onDeleteTransaction = { vm.deleteTransaction(it) },
                    contentPadding = padding,
                )
            }
            composable("source/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val vm = viewModel { ExplorerViewModel(session) }
                val data by vm.data.collectAsStateWithLifecycle()
                SourceDetailScreen(
                    entry.arguments!!.getLong("id"), data,
                    onBack = { nav.popBackStack() },
                    onOpenProduct = { nav.navigate("product/$it") },
                    onSaveProduct = { vm.saveProduct(it) },
                    onSaveTransaction = { vm.saveTransaction(it) },
                    onDeleteTransaction = { vm.deleteTransaction(it) },
                )
            }
            composable("product/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                val vm = viewModel { ExplorerViewModel(session) }
                val data by vm.data.collectAsStateWithLifecycle()
                ProductDetailScreen(
                    entry.arguments!!.getLong("id"), data,
                    onBack = { nav.popBackStack() },
                    onSaveProduct = { vm.saveProduct(it) },
                    onDeleteProduct = { vm.deleteProduct(it) },
                    onSaveSnapshot = { vm.saveSnapshot(it) },
                    onDeleteSnapshot = { vm.deleteSnapshot(it) },
                    onSaveTransaction = { vm.saveTransaction(it) },
                    onDeleteTransaction = { vm.deleteTransaction(it) },
                )
            }
            composable(TopDestination.SETUP.route) {
                SetupRoute(session, snackbar, padding)
            }
        }
    }
}

@Composable
private fun ConversationRoute(session: Session, chatId: Long, onBack: () -> Unit, onOpenSetup: () -> Unit) {
    val vm = viewModel(key = "chat-$chatId") { ConversationViewModel(session, chatId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { uri ->
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "document"
            vm.addFile(uri, name)
        }
    }
    ConversationScreen(
        ui = ui,
        pendingFiles = pending.map { it.name },
        onSend = vm::send,
        onAttach = { picker.launch(arrayOf("application/pdf", "text/*", "image/*")) },
        onRemoveFile = vm::removeFile,
        onApply = vm::apply,
        onDiscard = vm::discard,
        onUndo = vm::undo,
        onDismissError = vm::dismissError,
        onOpenSetup = onOpenSetup,
        onBack = onBack,
        onStop = vm::stop,
        initialDraft = vm.draftText,
    )
}

@Composable
private fun SetupRoute(session: Session, snackbar: SnackbarHostState, padding: PaddingValues) {
    val vm = viewModel { SetupViewModel(session) }
    val lists by vm.lists.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var passphrase by remember { mutableStateOf<CharArray?>(null) }

    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pass = passphrase
        if (uri != null && pass != null) vm.exportBackup(uri, pass)
        passphrase = null
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pass = passphrase
        if (uri != null && pass != null) vm.restoreBackup(uri, pass)
        passphrase = null
    }

    SetupScreen(
        lists, ai,
        onSaveSource = { vm.saveSource(it) },
        onDeleteSource = { vm.deleteSource(it) },
        onSaveAssetClass = { vm.saveAssetClass(it) },
        onDeleteAssetClass = { vm.deleteAssetClass(it) },
        onSaveCategory = { vm.saveCategory(it) },
        onDeleteCategory = { vm.deleteCategory(it) },
        onAddSuggestedClasses = { vm.addSuggestedClasses() },
        onAddSuggestedCategories = { vm.addSuggestedCategories() },
        onSaveKey = { vm.saveApiKey(it) },
        onPickModel = { vm.setModel(it) },
        onLoadModels = { vm.loadModels() },
        onPrivateOnly = { vm.setPrivateOnly(it) },
        onTest = { vm.testConnection() },
        onSaveNote = { vm.saveNote(it) },
        onExport = { passphrase = it; exportLauncher.launch("strata-backup-${LocalDate.now()}.strata") },
        onRestore = { passphrase = it; restoreLauncher.launch(arrayOf("*/*")) },
        contentPadding = padding,
    )
}

