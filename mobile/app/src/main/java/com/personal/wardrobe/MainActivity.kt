package com.personal.wardrobe

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.wardrobe.ui.WardrobeApp
import com.personal.wardrobe.ui.WardrobeTheme
import com.personal.wardrobe.ui.WardrobeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("wardrobe-ui", 0) }
            var appearance by remember { mutableStateOf(prefs.getString("appearance", "system") ?: "system") }
            val dark = when (appearance) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            WardrobeTheme(darkTheme = dark) {
                val vm: WardrobeViewModel = viewModel(
                    factory = WardrobeViewModel.Factory((application as WardrobeApplication).repository),
                )
                WardrobeApp(vm, appearance) { mode ->
                    appearance = mode
                    prefs.edit().putString("appearance", mode).apply()
                }
            }
        }
    }
}
