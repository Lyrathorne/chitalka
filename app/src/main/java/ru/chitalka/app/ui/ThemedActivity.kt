package ru.chitalka.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import ru.chitalka.app.ChitalkaApp

open class ThemedActivity : AppCompatActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val theme = (application as ChitalkaApp).themes.current.value
        val colors = theme.palette
        window.statusBarColor = colors.background
        window.navigationBarColor = if (android.os.Build.VERSION.SDK_INT < 27 && theme == AppTheme.PAPER) colors.ink else colors.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = theme == AppTheme.PAPER
            isAppearanceLightNavigationBars = theme == AppTheme.PAPER
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }
}
