package com.personal.wardrobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.wardrobe.ui.WardrobeApp
import com.personal.wardrobe.ui.WardrobeTheme
import com.personal.wardrobe.ui.WardrobeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WardrobeTheme {
                val vm: WardrobeViewModel = viewModel(
                    factory = WardrobeViewModel.Factory((application as WardrobeApplication).repository),
                )
                WardrobeApp(vm)
            }
        }
    }
}
