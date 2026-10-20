package com.daymark.app

import android.os.Bundle
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.daymark.app.ui.*
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: DaymarkViewModel by viewModels()
    override fun attachBaseContext(newBase: Context) {
        val configuration = Configuration(newBase.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DaymarkTheme { DaymarkApp(model, intent.getBooleanExtra("open_events", false)) } }
    }
    override fun onResume() { super.onResume(); model.refresh(true) }
}
