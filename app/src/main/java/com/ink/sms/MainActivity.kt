package com.ink.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.LaunchedEffect
import com.ink.sms.data.ContactResolver
import com.ink.sms.data.SmsRepository
import com.ink.sms.notification.NotificationHelper
import com.ink.sms.feature.conversation.SendSmsScreen
import com.ink.sms.feature.recyclebin.RecycleBinScreen
import com.ink.sms.feature.conversation.ConversationDetailScreen
import com.ink.sms.feature.conversation.ConversationListScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var smsRepository: SmsRepository
    @Inject lateinit var contactResolver: ContactResolver

    // 联系人权限为可选增强：授权后可显示通讯录名称，不授权也能正常使用短信功能。
    private val contactsPermission = arrayOf(Manifest.permission.READ_CONTACTS)
    private val contactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        contactResolver.reloadContacts()
        smsRepository.reload()
    }

    private val smsPermissions = buildList {
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private var hasPermission by mutableStateOf(false)
    private var isDefaultSms by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshStates()
        if (hasPermission) smsRepository.reload()
    }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshStates()
        if (hasPermission) smsRepository.reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshStates()

        if (!hasPermission) permissionLauncher.launch(smsPermissions)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (!hasPermission) {
                        SetupCard(
                            title = "需要短信权限才能读取会话",
                            action = "授予权限"
                        ) { permissionLauncher.launch(smsPermissions) }
                    } else if (!isDefaultSms) {
                        SetupCard(
                            title = "设为默认短信应用后才能收发短信",
                            action = "设为默认"
                        ) { requestDefaultSms() }
                    } else {
                        RequestContactsPermission()
                        val navController = rememberNavController()
                        NavHost(
                            navController = navController,
                            startDestination = "conversationList",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("conversationList") {
                                ConversationListScreen(
                                    onItemClick = { threadId ->
                                        navController.navigate("conversationDetail/$threadId")
                                    },
                                    onNewConversation = { navController.navigate("compose") },
                                    onOpenRecycleBin = { navController.navigate("recycleBin") }
                                )
                            }
                            composable(
                                route = "conversationDetail/{threadId}",
                                arguments = listOf(
                                    navArgument("threadId") { type = NavType.LongType }
                                )
                            ) { backStackEntry ->
                                val threadId = backStackEntry.arguments?.getLong("threadId") ?: 0L
                                ConversationDetailScreen(
                                    threadId = threadId,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("compose") {
                                SendSmsScreen(
                                    onBack = { navController.popBackStack() },
                                    onSent = { tid ->
                                        navController.navigate("conversationDetail/$tid") {
                                            popUpTo("compose") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("recycleBin") {
                                RecycleBinScreen(onBack = { navController.popBackStack() })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 打开/回到 App 时清掉已弹出的短信通知（保留保活前台服务通知，否则后台收信会失效）。
        NotificationHelper.cancelSmsNotifications(this)
    }

    private fun refreshStates() {
        hasPermission = smsPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        isDefaultSms = isDefaultSmsApp()
    }

    private fun isDefaultSmsApp(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            rm.isRoleAvailable(RoleManager.ROLE_SMS) && rm.isRoleHeld(RoleManager.ROLE_SMS)
        } else {
            android.provider.Telephony.Sms.getDefaultSmsPackage(this) == packageName
        }
    }

    private fun requestDefaultSms() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getSystemService(RoleManager::class.java)
                .createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
        }
        roleLauncher.launch(intent)
    }

    @Composable
    private fun RequestContactsPermission() {
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                contactsLauncher.launch(contactsPermission)
            }
        }
    }

    @Composable
    private fun SetupCard(title: String, action: String, onClick: () -> Unit) {
        Surface(
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onClick, modifier = Modifier.align(Alignment.End)) {
                    Text(action)
                }
            }
        }
    }
}
