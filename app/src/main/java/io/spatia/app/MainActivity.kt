package io.spatia.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.spatia.app.ui.MainViewModel
import io.spatia.app.ui.SpatiaRoot
import io.spatia.app.ui.SpatiaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SpatiaTheme { SpatiaRoot(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDevice()
    }
}
