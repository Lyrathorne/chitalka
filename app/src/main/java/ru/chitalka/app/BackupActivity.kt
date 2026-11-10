package ru.chitalka.app

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ru.chitalka.app.data.BackupData
import ru.chitalka.app.ui.ChitalkaTheme
import java.time.LocalDate

class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val backup = (application as ChitalkaApp).repository.backups
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<BackupData?>(null)
    private fun work(action: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try { message.value = action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Не удалось выполнить операцию" }
            finally { busy.value = false }
        }
    }
    fun exportJson(uri: Uri) = work { backup.writeJson(uri); "Резервная копия сохранена" }
    fun exportCsv(uri: Uri) = work { backup.writeCsv(uri); "Слова сохранены в CSV" }
    fun exportSafety(uri: Uri) = work { if (backup.writeSafetyCopy(uri)) "Копия перед последним импортом сохранена" else "Предыдущих импортов пока не было" }
    fun inspect(uri: Uri) = work { preview.value = backup.read(uri); "Копия проверена. Подтвердите объединение" }
    fun restore() {
        val data = preview.value ?: return
        preview.value = null
        work {
            val result = backup.restore(data)
            "Добавлено слов: ${result.wordsAdded}, закладок: ${result.bookmarksAdded}, ответов: ${result.reviewsAdded}. " +
                if (result.missingBooks > 0) "Книг без файла: ${result.missingBooks}. Добавьте исходные EPUB; прогресс сохранён." else "Данные объединены без удаления существующих записей."
        }
    }
}

class BackupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChitalkaTheme {
                val model: BackupViewModel = viewModel()
                val busy by model.busy.collectAsStateWithLifecycle()
                val message by model.message.collectAsStateWithLifecycle()
                val preview by model.preview.collectAsStateWithLifecycle()
                val snackbar = remember { SnackbarHostState() }
                val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportJson) }
                val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { it?.let(model::exportCsv) }
                val safety = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(model::exportSafety) }
                val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(model::inspect) }
                LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.message.value = null } }
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = { finish() }) { Text("‹ Назад") }
                        Text("Копии и перенос", fontSize = 30.sp)
                        Text("Сохраните слова, любимые цитаты, заметки, прогресс и историю повторений.", style = MaterialTheme.typography.bodyLarge)
                        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Обрабатываем данные…") }
                        Button(onClick = { json.launch("chitalka-backup-${LocalDate.now()}.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Сохранить резервную копию") }
                        OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Восстановить из JSON") }
                        Text("Файлы EPUB и языковые модели в копию не входят. Добавьте те же EPUB на другом устройстве — места чтения и цитаты свяжутся с ними автоматически.", style = MaterialTheme.typography.bodyMedium)
                        HorizontalDivider()
                        OutlinedButton(onClick = { csv.launch("chitalka-words-${LocalDate.now()}.csv") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Экспорт слов в CSV") }
                        Text("CSV подходит для переноса слов в другие программы. Для полного восстановления используйте JSON.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { safety.launch("chitalka-before-import-${LocalDate.now()}.json") }, enabled = !busy) { Text("Копия перед последним импортом") }
                        Text("Перед объединением сохраняется защитная копия. Хранятся три последние копии. JSON и CSV содержат ваши тексты и заметки — выбирайте место сохранения самостоятельно.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                preview?.let { data -> AlertDialog(onDismissRequest = { model.preview.value = null }, title = { Text("Объединить данные?") },
                    text = { Text("Книги: ${data.books.size} · Слова: ${data.words.size}\nЗакладки: ${data.bookmarks.size} · Ответы: ${data.reviews.size}\n\nСуществующие записи не удаляются. Дубликаты объединяются, более новые заметки и прогресс сохраняются. Файлы книг нужно добавить отдельно.") },
                    confirmButton = { TextButton(onClick = model::restore) { Text("Объединить") } },
                    dismissButton = { TextButton(onClick = { model.preview.value = null }) { Text("Отмена") } }) }
            }
        }
    }
}
