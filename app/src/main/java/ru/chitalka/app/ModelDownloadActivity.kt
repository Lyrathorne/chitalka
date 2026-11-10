package ru.chitalka.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.chitalka.app.data.ModelPhase
import ru.chitalka.app.ui.ChitalkaTheme

class ModelDownloadActivity : ru.chitalka.app.ui.ThemedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val downloads = (application as ChitalkaApp).modelDownloads
        if (savedInstanceState == null && intent.getBooleanExtra("start", false)) {
            intent.removeExtra("start"); downloads.start()
        } else downloads.refresh()
        setContent {
            ChitalkaTheme {
                val state by downloads.state.collectAsStateWithLifecycle()
                var confirmRemoval by remember { mutableStateOf(false) }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        TextButton(onClick = { finish() }) { Text("‹ Назад") }
                        Text("Переводчик", fontSize = 32.sp)
                        Text("Английский → русский", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(state.title, style = MaterialTheme.typography.titleLarge)
                        if (state.phase in listOf(ModelPhase.DOWNLOADING, ModelPhase.CHECKING, ModelPhase.DELETING)) {
                            val percent = state.percent
                            if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                        state.amount?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        if (state.phase == ModelPhase.DOWNLOADING) Text("Прошло ${state.elapsedSeconds / 60} мин ${state.elapsedSeconds % 60} с", style = MaterialTheme.typography.labelLarge)
                        Text(state.detail, style = MaterialTheme.typography.bodyLarge)
                        when (state.phase) {
                            ModelPhase.MISSING, ModelPhase.ERROR -> Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = downloads::start, modifier = Modifier.fillMaxWidth()) {
                                Text(if (state.phase == ModelPhase.ERROR) "Повторить загрузку" else "Загрузить модель")
                            }
                            ModelPhase.READY -> {
                                Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Готово · вернуться") }
                                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { confirmRemoval = true }, modifier = Modifier.fillMaxWidth()) { Text("Удалить модель") }
                            }
                            ModelPhase.DELETE_ERROR -> {
                                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = downloads::remove, modifier = Modifier.fillMaxWidth()) { Text("Повторить удаление") }
                            }
                            ModelPhase.DOWNLOADING -> {
                                OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) { Text("Настройки подключения") }
                                TextButton(onClick = { finish() }) { Text("Вернуться — загрузка продолжится") }
                            }
                            else -> Unit
                        }
                        if (state.phase !in listOf(ModelPhase.DOWNLOADING, ModelPhase.DELETING, ModelPhase.CHECKING)) TextButton(onClick = downloads::refresh) { Text("Проверить состояние") }
                        Text("Прогресс обновляется по данным Android. Если размер файла пока неизвестен, показываем ожидание без процентов. При закрытии экрана загрузка продолжается; после полного завершения приложения состояние проверяется заново.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (confirmRemoval) AlertDialog(onDismissRequest = { confirmRemoval = false }, title = { Text("Удалить языковую модель?") },
                    text = { Text("Будет удалён пакет перевода EN → RU и очищен временный кеш перевода. Книги, сохранённые слова, цитаты, заметки и история повторений останутся. Для повторной загрузки потребуется интернет.") },
                    confirmButton = { TextButton(onClick = { confirmRemoval = false; downloads.remove() }) { Text("Удалить модель") } },
                    dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Отмена") } })
            }
        }
    }
    override fun onResume() {
        super.onResume()
        (application as ChitalkaApp).modelDownloads.refresh()
    }
}
