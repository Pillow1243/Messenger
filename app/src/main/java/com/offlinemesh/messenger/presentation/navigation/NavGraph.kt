package com.offlinemesh.messenger.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.screens.AccountScreen
import com.offlinemesh.messenger.presentation.screens.ArchivedChatsScreen
import com.offlinemesh.messenger.presentation.screens.SavedMessagesScreen
import com.offlinemesh.messenger.presentation.screens.CallOverlay
import com.offlinemesh.messenger.presentation.screens.ChannelsScreen
import com.offlinemesh.messenger.presentation.screens.ChatScreen
import com.offlinemesh.messenger.presentation.screens.ChatsScreen
import com.offlinemesh.messenger.presentation.screens.ContactDetailScreen
import com.offlinemesh.messenger.presentation.screens.ContactsScreen
import com.offlinemesh.messenger.presentation.screens.NearbyScreen
import com.offlinemesh.messenger.presentation.screens.OnboardingScreen
import com.offlinemesh.messenger.presentation.screens.SearchScreen
import com.offlinemesh.messenger.presentation.screens.SettingsScreen
import java.util.Base64

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val CHAT = "chat/{conversationId}"
    const val NEARBY = "nearby"
    const val CONTACTS = "contacts"
    const val CONTACT = "contact/{deviceId}"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val ARCHIVED = "archived"
    const val SAVED = "saved"
    const val CHANNELS = "channels"
    const val ACCOUNT = "account"
    const val ARG_CONVERSATION = "conversationId"
    const val ARG_DEVICE = "deviceId"

    /** Ids contain ':' for groups, so they are base64url encoded for the route path. */
    fun chat(conversationId: String): String = "chat/${encodeId(conversationId)}"

    fun contact(deviceId: String): String = "contact/${encodeId(deviceId)}"

    fun decodeId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching { String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8) }.getOrNull()
    }

    private fun encodeId(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
}

private data class BottomItem(val route: String, val labelRes: Int, val icon: ImageVector)

private val bottomItems = listOf(
    BottomItem(Routes.CHATS, R.string.nav_chats, Icons.Filled.ChatBubbleOutline),
    BottomItem(Routes.NEARBY, R.string.nav_discover, Icons.Filled.Wifi),
    BottomItem(Routes.CONTACTS, R.string.nav_contacts, Icons.Filled.Person),
    BottomItem(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings)
)

@Composable
fun NavGraph(
    vm: AppViewModel,
    startConversation: String? = null,
    onStartConsumed: () -> Unit = {}
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val snapshot = settings

    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // A notification tap carries the conversation id; open it once the graph is ready.
    LaunchedEffect(startConversation, snapshot.onboarded) {
        if (startConversation != null && snapshot.onboarded) {
            navController.navigate(Routes.chat(startConversation))
            onStartConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = bottomItems.any { it.route == currentRoute }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomItems.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            onClick = { navigateToTab(navController, item.route) },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (snapshot.onboarded) Routes.CHATS else Routes.ONBOARDING,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onStart = { name, language ->
                        vm.completeOnboarding(name, language)
                        navController.navigate(Routes.CHATS) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    }
                )
            }

            composable(Routes.CHATS) {
                ChatsScreen(
                    vm = vm,
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                    onOpenNearby = { navigateToTab(navController, Routes.NEARBY) },
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                    onOpenArchived = { navController.navigate(Routes.ARCHIVED) },
                    onOpenSaved = { navController.navigate(Routes.SAVED) }
                )
            }

            composable(Routes.ARCHIVED) {
                ArchivedChatsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
                )
            }

            composable(Routes.SAVED) {
                SavedMessagesScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
                )
            }

            composable(Routes.SEARCH) {
                SearchScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
                )
            }

            composable(Routes.CHANNELS) {
                ChannelsScreen(
                    vm = vm,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
                )
            }

            composable(Routes.ACCOUNT) {
                AccountScreen(vm = vm, onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.CHAT,
                arguments = listOf(navArgument(Routes.ARG_CONVERSATION) { type = NavType.StringType })
            ) { entry ->
                val id = Routes.decodeId(entry.arguments?.getString(Routes.ARG_CONVERSATION))
                if (id != null) {
                    ChatScreen(vm = vm, conversationId = id, onBack = { navController.popBackStack() })
                }
            }

            composable(Routes.NEARBY) {
                NearbyScreen(
                    vm = vm,
                    onOpenChat = { deviceId -> navController.navigate(Routes.contact(deviceId)) }
                )
            }

            composable(Routes.CONTACTS) {
                ContactsScreen(
                    vm = vm,
                    onOpenContact = { deviceId -> navController.navigate(Routes.contact(deviceId)) }
                )
            }

            composable(
                route = Routes.CONTACT,
                arguments = listOf(navArgument(Routes.ARG_DEVICE) { type = NavType.StringType })
            ) { entry ->
                val deviceId = Routes.decodeId(entry.arguments?.getString(Routes.ARG_DEVICE))
                if (deviceId != null) {
                    ContactDetailScreen(
                        vm = vm,
                        deviceId = deviceId,
                        onBack = { navController.popBackStack() },
                        onMessage = { peer ->
                            vm.openConversationWith(peer) { conversationId ->
                                navController.navigate(Routes.chat(conversationId))
                            }
                        }
                    )
                }
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) }
                )
            }
        }
    }
    CallOverlay(vm = vm)
    }
}

private fun navigateToTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
