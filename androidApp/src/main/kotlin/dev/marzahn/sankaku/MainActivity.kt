package dev.marzahn.sankaku

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    (application as SankakuApplication).startKoinOnce(intent)

    setContent {
      // Quit on the first-launch screen: the app closes and leaves Recents, as a swipe-away would.
      App(onQuit = ::finishAndRemoveTask)
    }
  }
}
