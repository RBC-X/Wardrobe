package com.personal.wardrobe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.wardrobe.ui.*

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);enableEdgeToEdge();setContent{
    val colors=if(isSystemInDarkTheme())darkColorScheme(primary=Color(0xffafd6bc),secondary=Color(0xffd0be9c),background=Color(0xff15221d),surface=Color(0xff1b2923),surfaceVariant=Color(0xff2b3b31))else lightColorScheme(primary=Color(0xff244c40),secondary=Color(0xff8a6644),background=Color(0xfff5f2ea),surface=Color(0xfffffdf7),surfaceVariant=Color(0xffe6eddf),onBackground=Color(0xff22372d),onSurface=Color(0xff22372d))
    val typography=Typography(headlineLarge=TextStyle(fontFamily=FontFamily.Serif,fontSize=34.sp),headlineMedium=TextStyle(fontFamily=FontFamily.Serif,fontSize=28.sp),headlineSmall=TextStyle(fontFamily=FontFamily.Serif,fontSize=24.sp))
    MaterialTheme(colorScheme=colors,typography=typography){val vm:WardrobeViewModel=viewModel(factory=WardrobeViewModel.Factory((application as WardrobeApplication).repository));WardrobeApp(vm)}
}}}
