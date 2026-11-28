@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.sxvuj.streaksaver.ui.theme.StreakTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.onLoginResult(result.resultCode == RESULT_OK)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            StreakTheme(dynamicColor = model.dynamicColor) {
                AppRoot(
                    model = model,
                    onLogin = { loginLauncher.launch(Intent(this, LoginWebViewActivity::class.java)) },
                    onOpenUrl = { openUrl(this, it) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refresh()
    }
}

private class NavTab(val title: String, val selectedIcon: ImageVector, val icon: ImageVector)

// Rounded icons when selected, outlined when not, like Google's own apps.
private val TABS = listOf(
    NavTab("Today", Icons.Rounded.Home, Icons.Outlined.Home),
    NavTab("Friends", Icons.Rounded.Person, Icons.Outlined.Person),
    NavTab("History", Icons.Rounded.DateRange, Icons.Outlined.DateRange),
    NavTab("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

/** Destination id for the message editor, shown over the tabs. */
private const val EDITOR = -1

@Composable
private fun AppRoot(model: AppModel, onLogin: () -> Unit, onOpenUrl: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editingMessages by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }

    LaunchedEffect(Unit) {
        model.messages.collect { text ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    // The message editor handles its own back press (it asks before dropping unsaved edits).
    BackHandler(enabled = tab != 0 && !editingMessages) { tab = 0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // The bar slides away while the editor is open and springs back afterwards.
            AnimatedVisibility(
                visible = !editingMessages,
                enter = slideInVertically(Motion.offsetSpec) { it } + fadeIn(),
                exit = slideOutVertically(Motion.offsetSpec) { it } + fadeOut(),
            ) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TABS.forEachIndexed { index, item ->
                        val selected = tab == index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = index },
                            icon = { NavIcon(item, selected) },
                            label = {
                                Text(
                                    item.title,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // On Android 15 the window no longer resizes for the keyboard, so every screen has to make room
        // for it itself. consumeWindowInsets stops the bottom bar height from being counted twice.
        val content = Modifier
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
        val destination = if (editingMessages) EDITOR else tab
        AnimatedContent(
            targetState = destination,
            modifier = content,
            transitionSpec = {
                when {
                    targetState == EDITOR -> sharedAxisX(forward = true)
                    initialState == EDITOR -> sharedAxisX(forward = false)
                    else -> fadeThrough()
                }
            },
            label = "screen",
        ) { screen ->
            val fill = Modifier.fillMaxSize()
            when (screen) {
                EDITOR -> MessagesScreen(model, onClose = { editingMessages = false }, modifier = fill)
                0 -> HomeScreen(model, now, onLogin, onOpenUrl, onEditMessages = { editingMessages = true }, modifier = fill)
                1 -> FriendsScreen(model, now, fill)
                2 -> HistoryScreen(model, now, fill)
                else -> SettingsScreen(
                    model, now, onLogin, onOpenUrl,
                    onEditMessages = { editingMessages = true },
                    modifier = fill,
                )
            }
        }
    }
}

/** Swaps outlined for rounded when selected, with a quick squash-and-bounce pop. */
@Composable
private fun NavIcon(item: NavTab, selected: Boolean) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.72f)
            pop.animateTo(1f, Motion.bouncy())
        }
    }
    Crossfade(
        targetState = selected,
        animationSpec = tween(160),
        modifier = Modifier.graphicsLayer {
            scaleX = pop.value
            scaleY = pop.value
        },
        label = "navIcon",
    ) { on ->
        Icon(if (on) item.selectedIcon else item.icon, contentDescription = null)
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        // No browser installed.
    }
}
