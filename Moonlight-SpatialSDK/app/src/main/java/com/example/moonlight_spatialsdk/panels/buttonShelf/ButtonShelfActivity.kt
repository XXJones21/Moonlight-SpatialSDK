package com.example.moonlight_spatialsdk.panels.buttonShelf

import android.os.Bundle
import android.os.Message
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.util.Log

class ButtonShelfActivity : ComponentActivity() {
  companion object {
    private const val TAG = "ButtonShelfActivity"
    const val ACTION_SHOW_OPTIONS = 1
    
    @Volatile
    var onSettingsClickCallback: (() -> Unit)? = null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    setContent {
      ButtonShelfCompose(
          onSettingsClick = {
            onSettingsClickCallback?.invoke()
          }
      )
    }
  }
}
