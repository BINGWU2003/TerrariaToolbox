package com.example.terrariatoolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.terrariatoolbox.feature.chinese.ValidationViewModel
import com.example.terrariatoolbox.feature.chinese.ValidationScreen
import com.example.terrariatoolbox.ui.theme.TerrariaToolboxTheme

class MainActivity : ComponentActivity() {
    private val model: ValidationViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TerrariaToolboxTheme {
                ValidationScreen(model)
            }
        }
    }
    override fun onResume() { super.onResume(); model.onForeground() }
}

