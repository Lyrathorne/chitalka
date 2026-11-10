package ru.chitalka.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.chitalka.app.data.*
import ru.chitalka.app.reader.ReaderActivity
import ru.chitalka.app.ui.ChitalkaTheme

class LibraryViewModel(val repo: LibraryRepository) : ViewModel() {
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val duplicate = MutableStateFlow<Book?>(null)
    fun import(uri: android.net.Uri) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val result = repo.importWithResult(uri)
                if (result.duplicate) duplicate.value = result.book else message.value = "Книга добавлена"
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "Не удалось добавить книгу" }
            finally { busy.value = false }
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as ChitalkaApp).repository
        setContent {
            ChitalkaTheme {
                val vm: LibraryViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(repo) as T
                })
                LibraryScreen(vm) { book, locator ->
                    startActivity(Intent(this, ReaderActivity::class.java)
                        .putExtra("bookId", book.id).putExtra("locator", locator))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, open: (Book, String?) -> Unit) {
    val repo = vm.repo
    val books by remember { repo.dao.books() }.collectAsStateWithLifecycle(emptyList())
    val words by remember { repo.dao.words() }.collectAsStateWithLifecycle(emptyList())
    val marks by remember { repo.dao.bookmarks() }.collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val duplicate by vm.duplicate.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showMarks by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var deleteBook by remember { mutableStateOf<Book?>(null) }
    var editMark by remember { mutableStateOf<Bookmark?>(null) }
    var editWord by remember { mutableStateOf<Word?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val downloads = (context.applicationContext as ChitalkaApp).modelDownloads
    val modelState by downloads.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = {
            Text(when { tab == 0 && showMarks -> "Мои моменты"; tab == 0 -> "Мои книги"; tab == 1 -> "Мои слова"; else -> "Практика" },
                fontFamily = FontFamily.Serif, fontSize = 30.sp)
        }, actions = {
            if (tab == 0) {
                IconButton(onClick = { showMarks = !showMarks; query = "" }) {
                    Icon(if (showMarks) Icons.Outlined.MenuBook else Icons.Outlined.Bookmarks, if (showMarks) "Показать книги" else "Понравившиеся моменты")
                }
                IconButton(onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy) {
                    Icon(Icons.Outlined.Add, "Добавить EPUB")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Меню библиотеки") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Копии и перенос") }, onClick = {
                        menuOpen = false; context.startActivity(Intent(context, BackupActivity::class.java))
                    })
                    if (tab == 0 && !showMarks) {
                        HorizontalDivider()
                        listOf("Недавно читали", "По названию", "По автору").forEachIndexed { index, label ->
                            DropdownMenuItem(text = { Text(if (sort == index) "✓ $label" else label) }, onClick = { sort = index; menuOpen = false })
                        }
                    }
                }
            }
        }) },
        bottomBar = { NavigationBar {
            val labels = listOf("Книги", "Слова", "Практика")
            val icons = listOf(Icons.Outlined.MenuBook, Icons.Outlined.Style, Icons.Outlined.School)
            labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index,
                onClick = { tab = index; query = "" }, icon = { Icon(icons[index], null) }, label = { Text(label) }) }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Добавляем книгу…", Modifier.padding(vertical = 8.dp)) }
            if (tab == 0) {
                TextButton(onClick = { context.startActivity(Intent(context, ModelDownloadActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (modelState.phase == ModelPhase.READY) "EN → RU · переводчик готов" else modelState.title)
                }
                if (modelState.phase == ModelPhase.DOWNLOADING) {
                    val percent = modelState.percent
                    if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(modelState.amount ?: modelState.detail, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            if (tab != 2) OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Поиск") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            when {
                tab == 0 && !showMarks -> {
                    if (books.isEmpty()) {
                        EmptyState("Здесь начнётся ваша история", "Добавьте EPUB на английском. Удерживайте слово для перевода, а любимые фрагменты сохраняйте в закладки.")
                        OutlinedButton(onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    java.io.File(context.cacheDir, "sample.epub").also { file ->
                                        context.assets.open("sample.epub").use { input -> file.outputStream().use { input.copyTo(it) } }
                                    }
                                }
                                vm.import(android.net.Uri.fromFile(file))
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Попробовать на короткой истории") }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        val filtered = books.filter { "${it.title} ${it.author}".contains(query, true) }
                        val sorted = when (sort) {
                            1 -> filtered.sortedBy { it.title.lowercase() }
                            2 -> filtered.sortedWith(compareBy<Book> { it.author.lowercase() }.thenBy { it.title.lowercase() })
                            else -> filtered
                        }
                        items(sorted, key = { it.id }) { book ->
                            Row(Modifier.fillMaxWidth().clickable { open(book, null) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Cover(book)
                                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                    Text(book.title, fontFamily = FontFamily.Serif, fontSize = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    Text(book.author.ifBlank { "Автор не указан" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                                    LinearProgressIndicator(progress = { book.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                                    Text(if (book.lastRead == 0L) "Ещё не начата" else "Прочитано ${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                                }
                                IconButton(onClick = { deleteBook = book }) { Icon(Icons.Outlined.DeleteOutline, "Удалить книгу") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                tab == 0 -> {
                    if (marks.isEmpty()) EmptyState("Сохраните то, что тронуло", "Выделите фрагмент в книге и нажмите «В закладки». Здесь останутся цитаты и ваши заметки.")
                    LazyColumn {
                        items(marks.filter { "${it.quote} ${it.note} ${it.bookTitle}".contains(query, true) }, key = { it.id }) { mark ->
                            Column(Modifier.fillMaxWidth().clickable {
                                val book = books.find { it.hash == mark.bookHash }
                                if (book != null) open(book, mark.locator) else scope.launch { snackbar.showSnackbar("Добавьте исходную книгу, чтобы перейти к цитате") }
                            }.padding(vertical = 14.dp)) {
                                Text(mark.quote, fontFamily = FontFamily.Serif, fontSize = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                Text(mark.bookTitle, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                                if (mark.note.isNotBlank()) Text(mark.note, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { editMark = mark }) { Text("Заметка и действия") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                tab == 1 -> {
                    if (words.isEmpty()) EmptyState("Слова из ваших книг", "Сохраните перевод во время чтения. Вместе со словом останется контекст.")
                    else FilledTonalButton(onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("Повторить слова (${words.size})") }
                    LazyColumn {
                        items(words.filter { "${it.original} ${it.translation}".contains(query, true) }, key = { it.id }) { word ->
                            Row(Modifier.clickable { editWord = word }.padding(vertical = 14.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(word.original, fontSize = 24.sp, fontFamily = FontFamily.Serif)
                                    Text(word.translation, fontSize = 18.sp)
                                    Text(word.context, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    Text(word.source, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                                }
                                IconButton(onClick = { scope.launch { repo.dao.delete(word) } }) { Icon(Icons.Outlined.DeleteOutline, "Удалить слово") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                else -> Practice(words) { word, rating -> scope.launch { repo.dao.insert(ReviewEvent(wordId = word.id, rating = rating)) } }
            }
        }
    }
    deleteBook?.let { book -> AlertDialog(onDismissRequest = { deleteBook = null }, title = { Text("Удалить книгу?") },
        text = { Text("Цитаты, заметки и сохранённые слова останутся доступны.") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.remove(book) }; deleteBook = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteBook = null }) { Text("Отмена") } }) }
    editMark?.let { mark -> BookmarkDialog(mark, onDismiss = { editMark = null },
        onSave = { note -> scope.launch { repo.dao.update(mark.copy(note = note, updatedAt = System.currentTimeMillis())) }; editMark = null },
        onDelete = { scope.launch { repo.dao.delete(mark) }; editMark = null }) }
    duplicate?.let { book -> AlertDialog(onDismissRequest = { vm.duplicate.value = null }, title = { Text("Книга уже добавлена") },
        text = { Text(book.title) }, confirmButton = { TextButton(onClick = { vm.duplicate.value = null; open(book, null) }) { Text("Открыть книгу") } },
        dismissButton = { TextButton(onClick = { vm.duplicate.value = null }) { Text("Закрыть") } }) }
    editWord?.let { word -> WordEditDialog(word, onDismiss = { editWord = null }, onSave = { translation, example ->
        scope.launch {
            try {
                repo.dao.update(word.copy(translation = translation, context = example))
                editWord = null
                snackbar.showSnackbar("Перевод сохранён")
            } catch (e: CancellationException) { throw e }
            catch (_: android.database.sqlite.SQLiteConstraintException) { snackbar.showSnackbar("Такой перевод этого слова уже сохранён") }
            catch (_: Exception) { snackbar.showSnackbar("Не удалось сохранить перевод. Попробуйте ещё раз") }
        }
    }) }
}

@Composable private fun WordEditDialog(word: Word, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var translation by remember(word.id) { mutableStateOf(word.translation) }
    var example by remember(word.id) { mutableStateOf(word.context) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(word.original) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(translation, { if (it.length <= 20000) translation = it }, label = { Text("Перевод") }, isError = translation.isBlank())
            OutlinedTextField(example, { if (it.length <= 20000) example = it }, label = { Text("Пример из книги") }, minLines = 2, maxLines = 4)
        }
    }, confirmButton = { TextButton(onClick = { onSave(translation.trim(), example.trim()) }, enabled = translation.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable private fun Cover(book: Book) {
    val image by produceState<android.graphics.Bitmap?>(null, book.cover) {
        value = withContext(Dispatchers.IO) { book.cover?.let { BitmapFactory.decodeFile(it) } }
    }
    val bitmap = image
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(84.dp, 126.dp))
    else Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(84.dp, 126.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.MenuBook, null, Modifier.size(36.dp)) }
    }
}

@Composable fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp))
        Text(detail, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable fun BookmarkDialog(mark: Bookmark, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var note by remember(mark.id) { mutableStateOf(mark.note) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Сохранённый момент") }, text = {
        Column { Text(mark.quote.take(300), fontFamily = FontFamily.Serif)
            OutlinedTextField(note, { note = it }, label = { Text("Ваша заметка") }, modifier = Modifier.padding(top = 16.dp)) }
    }, confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Сохранить") } },
        dismissButton = { Row { TextButton(onClick = onDelete) { Text("Удалить") }; TextButton(onClick = onDismiss) { Text("Закрыть") } } })
}

@Composable private fun Practice(words: List<Word>, rate: (Word, Int) -> Unit) {
    var sessionIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    val session = sessionIds.mapNotNull { id -> words.find { it.id == id } }
    if (words.isEmpty()) { EmptyState("Пока нечего повторять", "Добавляйте слова во время чтения — они появятся здесь."); return }
    if (sessionIds.isEmpty()) {
        EmptyState("Немного практики", "Повторите слова, которые встретились в книгах.")
        Button(onClick = { sessionIds = ArrayList(words.map { it.id }); index = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Начать повторение") }
        return
    }
    if (index >= session.size) {
        EmptyState("На сегодня всё", "Вы повторили ${session.size} слов.")
        TextButton(onClick = { sessionIds = arrayListOf(); index = 0 }) { Text("Начать заново") }
        return
    }
    val word = session[index]
    Text("${index + 1} из ${session.size}", Modifier.padding(vertical = 16.dp))
    Text(word.original, fontFamily = FontFamily.Serif, fontSize = 38.sp, modifier = Modifier.padding(vertical = 24.dp))
    Text(word.context, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    if (!revealed) Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("Показать ответ") }
    else {
        Text(word.translation, fontSize = 26.sp, modifier = Modifier.padding(bottom = 24.dp))
        listOf("Не помню", "Трудно", "Помню").forEachIndexed { value, label ->
            OutlinedButton(onClick = { rate(word, value); index++; revealed = false }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        }
    }
}
