package com.cczb.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.cczb.app.ui.theme.PumpmutiappTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[MasterControlViewModel::class.java]
        setContent { PumpmutiappTheme { PumpApp(model) } }
    }
}
