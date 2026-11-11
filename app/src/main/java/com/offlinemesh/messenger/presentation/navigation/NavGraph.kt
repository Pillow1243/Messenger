package com.offlinemesh.messenger.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.presentation.screens.ChatListScreen
import com.offlinemesh.messenger.presentation.screens.ChatScreen
import com.offlinemesh.messenger.presentation.screens.ContactsScreen
import com.offlinemesh.messenger.presentation.screens.DiscoveryScreen
import com.offlinemesh.messenger.presentation.screens.GroupCreateScreen
import com.offlinemesh.messenger.presentation.screens.OnboardingScreen
import com.offlinemesh.messenger.presentation.screens.QrShowScreen
import com.offlinemesh.messenger.presentation.screens.SettingsScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val CHAT = "chat/{conversationId}"
    const val DISCOVER = "discover"
    const val CONTACTS = "contacts"
    const val SETTINGS = "settings"
    const val GROUP_CREATE = "group_create"
    const val QR_SHOW = "qr_show"

    fun chat(conversationId: String): String = "chat/$conversationId"
}

@Composable
fun NavGraph(app: MessengerApp, navController: NavHostController = rememberNavController()) {
    val onboarded by app.settingsRepository.onboarded.collectAsState(initial = false)
    val startDestination = if (onboarded) Routes.CHATS else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = { navController.navigate(Routes.CHATS) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.CHATS) {
            ChatListScreen(
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenDiscover = { navController.navigate(Routes.DISCOVER) },
                onOpenContacts = { navController.navigate(Routes.CONTACTS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenGroupCreate = { navController.navigate(Routes.GROUP_CREATE) }
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
        ) {
            ChatScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DISCOVER) {
            DiscoveryScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenQrShow = { navController.navigate(Routes.QR_SHOW) }
            )
        }
        composable(Routes.CONTACTS) {
            ContactsScreen(
                onBack = { navController.popBackStack() },
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.GROUP_CREATE) {
            GroupCreateScreen(
                onBack = { navController.popBackStack() },
                onCreated = { id -> navController.navigate(Routes.chat(id)) }
            )
        }
        composable(Routes.QR_SHOW) {
            QrShowScreen(onBack = { navController.popBackStack() })
        }
    }
}
