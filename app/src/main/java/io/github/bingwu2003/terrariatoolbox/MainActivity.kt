package io.github.bingwu2003.terrariatoolbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.bingwu2003.terrariatoolbox.feature.chinese.ValidationViewModel
import io.github.bingwu2003.terrariatoolbox.feature.chinese.ValidationScreen
import io.github.bingwu2003.terrariatoolbox.ui.theme.TerrariaToolboxTheme

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

