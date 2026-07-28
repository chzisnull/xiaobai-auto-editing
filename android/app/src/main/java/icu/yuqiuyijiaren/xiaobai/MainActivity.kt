package icu.yuqiuyijiaren.xiaobai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import icu.yuqiuyijiaren.xiaobai.ui.screens.EditorScreen
import icu.yuqiuyijiaren.xiaobai.ui.theme.Canvas
import icu.yuqiuyijiaren.xiaobai.ui.theme.XiaobaiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return EditorViewModel(application) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiaobaiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Canvas) {
                    EditorScreen(viewModel = viewModel)
                }
            }
        }
    }
}
