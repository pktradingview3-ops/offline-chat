package com.offlinechat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.offlinechat.chat.ChatEngine
import com.offlinechat.ui.ChatScreen
import com.offlinechat.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val engine: ChatEngine by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatEngine(applicationContext) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    AppRoot(engine)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(engine: ChatEngine) {
    // rememberSaveable: with a plain `remember`, rotating the phone threw away `started`
    // and dumped the user back on the setup screen while the engine kept running in the
    // (surviving) ViewModel — so the chat vanished but the connection stayed alive.
    var started by rememberSaveable { mutableStateOf(false) }

    if (!started) {
        SetupScreen(
            onConnect = { url ->
                started = true
                engine.connectManually(url)
            },
            onAuto = {
                started = true
                engine.start()
            }
        )
    } else {
        ChatScreen(engine)
    }
}
