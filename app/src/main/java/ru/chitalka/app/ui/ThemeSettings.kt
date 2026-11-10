package ru.chitalka.app.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.appearanceStore by preferencesDataStore("appearance")
enum class AppTheme(val title: String) { PAPER("Бумага"), NIGHT("Ночь") }
data class ReaderPalette(val background: Int, val ink: Int, val accent: Int, val onAccent: Int, val selection: Int, val highlight: Int)
val AppTheme.palette: ReaderPalette get() = when (this) {
    AppTheme.PAPER -> ReaderPalette(0xFFF6F1E7.toInt(), 0xFF292820.toInt(), 0xFF596A36.toInt(), 0xFFFFFFFF.toInt(), 0xFFD7DDB5.toInt(), 0xFFE2E5CB.toInt())
    AppTheme.NIGHT -> ReaderPalette(0xFF171C18.toInt(), 0xFFF2EEDF.toInt(), 0xFFB5C59B.toInt(), 0xFF182010.toInt(), 0xFF4C603D.toInt(), 0xFF34432D.toInt())
}
class ThemeSettings(context: Context) {
    private val store = context.applicationContext.appearanceStore
    private val key = stringPreferencesKey("theme")
    private val selected = MutableStateFlow(AppTheme.PAPER)
    val current = selected.asStateFlow()
    suspend fun load() {
        val value = store.data.first()[key]
        selected.value = AppTheme.entries.firstOrNull { it.name == value } ?: AppTheme.PAPER
    }
    fun applyNative() = AppCompatDelegate.setDefaultNightMode(if (selected.value == AppTheme.NIGHT) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    suspend fun select(theme: AppTheme) {
        store.edit { it[key] = theme.name }
        selected.value = theme
        applyNative()
    }
}
