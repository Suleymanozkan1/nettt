package com.bookmind.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookmind.app.notifications.Notifier
import com.bookmind.app.ui.navigation.BookMindApp
import com.bookmind.app.ui.theme.BookMindTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private var destination by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        destination = intent?.getStringExtra(Notifier.EXTRA_DESTINATION)
        setContent {
            val vm: MainViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            BookMindTheme(state.theme) {
                BookMindApp(state = state, deepLink = destination, onDeepLinkHandled = { destination = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        destination = intent.getStringExtra(Notifier.EXTRA_DESTINATION)
    }
}
