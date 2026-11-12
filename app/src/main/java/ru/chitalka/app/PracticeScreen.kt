package ru.chitalka.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import ru.chitalka.app.data.*

class PracticeViewModel(private val repo: LibraryRepository) : ViewModel() {
    val due = combine(repo.dao.words(), repo.dao.reviewEvents(), flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }) { words, reviews, now ->
        ReviewSchedule.dueWords(words, reviews, now).size
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val state = combine(repo.practice.sessions, repo.dao.words()) { session, words ->
        words to practiceProgress(session, words)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun act(block: suspend PracticeRepository.() -> Unit) {
        if (busy.value) return
        busy.value = true; error.value = null
        viewModelScope.launch {
            try { repo.practice.block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = "Не удалось сохранить ответ. Попробуйте ещё раз." }
            finally { busy.value = false }
        }
    }
}

@Composable fun PracticeScreen(repo: LibraryRepository) {
    val factory = remember(repo) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = PracticeViewModel(repo) as T
    } }
    val vm: PracticeViewModel = viewModel(factory = factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val due by vm.due.collectAsStateWithLifecycle()
    val preferences = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("practice_options", 0)
    var reverse by rememberSaveable { mutableStateOf(preferences.getBoolean("reverse", false)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
        val loaded = state
        if (loaded == null) { Text("Открываем практику…"); return@Column }
        val (words, progress) = loaded
        if (progress.session == null || progress.finished) {
            Row {
                Checkbox(reverse, onCheckedChange = { reverse = it; preferences.edit().putBoolean("reverse", it).apply() })
                Text("Обратные карточки · русский → английский", modifier = Modifier.weight(1f).padding(top = 12.dp))
            }
            if (words.isNotEmpty()) OutlinedButton(onClick = { vm.act { start(dueOnly = true) } }, enabled = !busy && due > 0, modifier = Modifier.fillMaxWidth()) {
                Text("По плану · $due слов")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
        when {
            progress.session == null -> {
                if (words.isEmpty()) EmptyState("Пока нечего повторять", "Сохраните слово во время чтения — оно появится здесь.")
                else {
                    EmptyState("Немного практики", "Вспомните перевод слов из ваших книг. Ответ появится, когда будете готовы.")
                    Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { vm.act { start() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Начать повторение") }
                }
            }
            progress.finished -> {
                EmptyState("Повторение завершено", "Ответов: ${progress.answered} из ${progress.total}.")
                listOf("Не помню", "Трудно", "Помню").forEachIndexed { rating, label ->
                    Text("$label: ${progress.counts[rating]}", modifier = Modifier.padding(bottom = 8.dp))
                }
                if (progress.missing > 0) Text("Пропущено удалённых слов: ${progress.missing}", modifier = Modifier.padding(vertical = 8.dp))
                Text("Можно вернуться к словам позже.", modifier = Modifier.padding(vertical = 16.dp))
                if (words.isNotEmpty()) Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { vm.act { start() } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Повторить ещё раз") }
            }
            else -> {
                val word = requireNotNull(progress.current)
                var contextVisible by rememberSaveable(word.id) { mutableStateOf(false) }
                Text("Осталось ${progress.total - progress.answered - progress.missing} · Отвечено ${progress.answered}")
                LinearProgressIndicator(progress = { (progress.answered + progress.missing).toFloat() / progress.total }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(if (reverse) word.translation else word.original, fontFamily = FontFamily.Serif, fontSize = 38.sp, modifier = Modifier.padding(top = 40.dp, bottom = 20.dp))
                if (word.context.isNotBlank()) {
                    TextButton(onClick = { contextVisible = !contextVisible }) { Text(if (contextVisible) "Скрыть контекст" else "Показать контекст") }
                    if (contextVisible) {
                        val example = ru.chitalka.app.data.practiceContext(word.context, word.original)
                        val accent = MaterialTheme.colorScheme.primary
                        val highlighted = androidx.compose.ui.text.buildAnnotatedString {
                            append(example)
                            Regex("(?<![\\p{L}])" + Regex.escape(word.original) + "(?![\\p{L}])", RegexOption.IGNORE_CASE).findAll(example).forEach { match ->
                                addStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), match.range.first, match.range.last + 1)
                            }
                        }
                        Text(highlighted, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 16.dp))
                    }
                }
                if (!progress.revealed) Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { vm.act { reveal(word.id) } }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Показать ответ") }
                else {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text(if (reverse) word.original else word.translation, fontSize = 26.sp)
                    Text(word.source, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
                    if (word.source.contains("ML Kit")) ru.chitalka.app.ui.GoogleAttribution()
                    listOf("Не помню", "Трудно", "Помню").forEachIndexed { rating, label ->
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { vm.act { rate(word.id, rating) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        }
    }
}
