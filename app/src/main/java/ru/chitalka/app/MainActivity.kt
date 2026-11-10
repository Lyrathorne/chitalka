package ru.chitalka.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import ru.chitalka.app.ui.*

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

class MainActivity : ThemedActivity() {
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
    var markBook by rememberSaveable { mutableStateOf<String?>(null) }
    var markFilterOpen by remember { mutableStateOf(false) }
    var editWord by remember { mutableStateOf<Word?>(null) }
    var deleteWord by remember { mutableStateOf<Word?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val downloads = (context.applicationContext as ChitalkaApp).modelDownloads
    val modelState by downloads.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(marks, markBook) { if (markBook != null && marks.none { it.bookHash == markBook }) markBook = null }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = {
            Text(when { tab == 0 && showMarks -> "Мои моменты"; tab == 0 -> "Мои книги"; tab == 1 -> "Мои слова"; else -> "Практика" },
                fontFamily = FontFamily.Serif, fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        }, actions = {
            if (tab == 0) {
                IconButton(onClick = { showMarks = !showMarks; query = "" }) {
                    Icon(if (showMarks) Icons.Outlined.MenuBook else Icons.Outlined.Bookmarks, if (showMarks) "Показать книги" else "Понравившиеся моменты")
                }
                if (!showMarks) IconButton(onClick = { picker.launch(arrayOf("application/epub+zip", "application/octet-stream")) }, enabled = !busy) {
                    Icon(Icons.Outlined.Add, "Добавить EPUB")
                }
            }
            Box {
                IconButton(onClick = { focusManager.clearFocus(); menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "Меню библиотеки") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (tab != 2) DropdownMenuItem(text = { Text(if (searchOpen) "Скрыть поиск" else "Поиск") }, onClick = { menuOpen = false; searchOpen = !searchOpen; if (!searchOpen) query = "" })
                    DropdownMenuItem(text = { Text("Оформление") }, onClick = { menuOpen = false; showTheme = true })
                    DropdownMenuItem(text = { Text("Переводчик") }, onClick = { menuOpen = false; context.startActivity(Intent(context, ModelDownloadActivity::class.java)) })
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
        bottomBar = { Column { HorizontalDivider(); NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            val labels = listOf("Книги", "Слова", "Практика")
            val icons = listOf(Icons.Outlined.MenuBook, Icons.Outlined.Style, Icons.Outlined.School)
            labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index,
                onClick = { focusManager.clearFocus(); tab = index; query = ""; searchOpen = false }, icon = { Icon(icons[index], null) }, label = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(label); Spacer(Modifier.height(5.dp));
                        Box(Modifier.width(32.dp).height(2.dp).background(if (tab == index) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)) }
                }, colors = NavigationBarItemDefaults.colors(indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface, unselectedTextColor = MaterialTheme.colorScheme.onSurface)) }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Добавляем книгу…", Modifier.padding(vertical = 8.dp)) }
            if (tab == 0 && modelState.phase != ModelPhase.READY && (!showMarks || modelState.phase == ModelPhase.DOWNLOADING)) {
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
            if (tab != 2 && searchOpen) OutlinedTextField(value = query, onValueChange = { query = it },
                placeholder = { Text("Поиск") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            when {
                tab == 0 && !showMarks -> {
                    if (books.isEmpty()) {
                        EmptyState("Здесь начнётся ваша история", "Добавьте EPUB на английском. Выделите слово и нажмите «Перевести». Понравившийся фрагмент сохраните кнопкой «Цитата», свои мысли — через «Заметка».")
                        OutlinedButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = {
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
                    run {
                        val filtered = books.filter { "${it.title} ${it.author}".contains(query, true) }
                        val sorted = when (sort) {
                            1 -> filtered.sortedBy { it.title.lowercase() }
                            2 -> filtered.sortedWith(compareBy<Book> { it.author.lowercase() }.thenBy { it.title.lowercase() })
                            else -> filtered
                        }
                        Bookshelf(sorted, open = { open(it, null) }, delete = { deleteBook = it })
                    }
                }
                tab == 0 -> {
                    if (marks.isEmpty()) EmptyState("Сохраните то, что тронуло", "Выделите фрагмент в книге и нажмите «Цитата» или «Заметка». Здесь останутся цитаты и ваши мысли.")
                    if (marks.isNotEmpty()) Box {
                        TextButton(onClick = { markFilterOpen = true }, modifier = Modifier.semantics { contentDescription = "Фильтр закладок" }) { Text(markBook?.let { hash -> marks.find { it.bookHash == hash }?.bookTitle } ?: "Все книги") }
                        DropdownMenu(expanded = markFilterOpen, onDismissRequest = { markFilterOpen = false }) {
                            DropdownMenuItem(text = { Text("Все книги") }, onClick = { markBook = null; markFilterOpen = false })
                            marks.distinctBy { it.bookHash }.sortedBy { it.bookTitle }.forEach { mark ->
                                DropdownMenuItem(text = { Text(mark.bookTitle) }, onClick = { markBook = mark.bookHash; markFilterOpen = false })
                            }
                        }
                    }
                    val filteredMarks = marks.filter { (markBook == null || it.bookHash == markBook) && "${it.quote} ${it.note} ${it.bookTitle}".contains(query, true) }
                    if (marks.isNotEmpty() && filteredMarks.isEmpty()) Text("Ничего не найдено. Измените поиск или выберите другую книгу.")
                    LazyColumn {
                        items(filteredMarks, key = { it.id }) { mark ->
                            Column(Modifier.fillMaxWidth().clickable {
                                val book = books.find { it.hash == mark.bookHash }
                                if (book != null) open(book, mark.locator) else scope.launch { snackbar.showSnackbar("Добавьте исходную книгу, чтобы перейти к цитате") }
                            }.padding(vertical = 14.dp)) {
                                Text(mark.quote, fontFamily = FontFamily.Serif, fontSize = 20.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                                Text(mark.bookTitle, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                                Text("${quoteChapter(mark)} · ${quoteDate(mark)}", style = MaterialTheme.typography.labelSmall)
                                if (mark.note.isNotBlank()) Text(mark.note, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = { editMark = mark }) { Text("Заметка и действия") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                tab == 1 -> {
                    if (words.isEmpty()) EmptyState("Слова из ваших книг", "Сохраните перевод во время чтения. Вместе со словом останется контекст.")
                    else Button(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), onClick = { tab = 2 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(bottom = 12.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Повторить слова · ${words.size}") }
                    val matchingWords = words.filter { "${it.original} ${it.translation}".contains(query, true) }
                    if (words.isNotEmpty() && matchingWords.isEmpty()) EmptyState("Слово не найдено", "Попробуйте английское слово или его перевод.")
                    LazyColumn {
                        items(matchingWords, key = { it.id }) { word ->
                            var actionsOpen by remember(word.id) { mutableStateOf(false) }
                            Row(Modifier.clickable { editWord = word }.padding(vertical = 14.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(word.original, fontSize = 24.sp, fontFamily = FontFamily.Serif, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    Text(word.translation, fontSize = 18.sp)
                                    val accent = MaterialTheme.colorScheme.primary
                                    val contextText = remember(word.context, word.original, accent) {
                                        androidx.compose.ui.text.buildAnnotatedString {
                                            append(word.context)
                                            if (word.original.isNotBlank()) Regex("(?<![A-Za-z])" + Regex.escape(word.original) + "(?![A-Za-z])", RegexOption.IGNORE_CASE)
                                                .findAll(word.context).take(20).forEach { match -> addStyle(androidx.compose.ui.text.SpanStyle(color = accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), match.range.first, match.range.last + 1) }
                                        }
                                    }
                                    Text(contextText, fontFamily = FontFamily.Serif, fontSize = 17.sp, fontStyle = if (LocalAppTheme.current == AppTheme.NIGHT) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                                    Text(word.bookTitle, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                                }
                                Box {
                                    IconButton(onClick = { actionsOpen = true }) { Icon(Icons.Outlined.MoreVert, "Действия со словом ${word.original}") }
                                    DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                                        DropdownMenuItem(text = { Text("Редактировать") }, onClick = { actionsOpen = false; editWord = word })
                                        DropdownMenuItem(text = { Text("К месту в книге") }, onClick = {
                                            actionsOpen = false
                                            val sourceBook = books.find { it.hash == word.bookHash }
                                            if (sourceBook != null) open(sourceBook, word.locator)
                                            else scope.launch { snackbar.showSnackbar("Добавьте исходную книгу, чтобы перейти к слову. Контекст сохранён.") }
                                        })
                                        DropdownMenuItem(text = { Text("Удалить слово") }, onClick = { actionsOpen = false; deleteWord = word })
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                else -> PracticeScreen(repo)
            }
        }
    }
    if (showTheme) AlertDialog(onDismissRequest = { showTheme = false }, title = { Text("Оформление") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val current = LocalAppTheme.current
            AppTheme.entries.forEach { choice ->
                val select = { scope.launch { try { (context.applicationContext as ChitalkaApp).themes.select(choice) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { snackbar.showSnackbar("Не удалось сохранить оформление") } }; Unit }
                Row(Modifier.fillMaxWidth().clickable(onClick = select).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current == choice, onClick = select)
                    Column { Text(choice.title, fontFamily = FontFamily.Serif, fontSize = 22.sp)
                        Text(if (choice == AppTheme.PAPER) "Кремовая бумага и оливковые акценты" else "Тёмный фон и тёплый светлый текст", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { showTheme = false }) { Text("Готово") } })
    deleteBook?.let { book -> AlertDialog(onDismissRequest = { deleteBook = null }, title = { Text("Удалить книгу?") },
        text = { Text("Цитаты, заметки и сохранённые слова останутся доступны.") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.remove(book) }; deleteBook = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteBook = null }) { Text("Отмена") } }) }
    deleteWord?.let { word -> AlertDialog(onDismissRequest = { deleteWord = null }, title = { Text("Удалить слово?") },
        text = { Text("${word.original} — ${word.translation}") },
        confirmButton = { TextButton(onClick = { scope.launch { repo.dao.delete(word) }; deleteWord = null }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteWord = null }) { Text("Отмена") } }) }
    editMark?.let { mark -> BookmarkDialog(mark, onDismiss = { editMark = null },
        onSave = { note -> scope.launch { repo.dao.update(mark.copy(note = note, updatedAt = System.currentTimeMillis())) }; editMark = null },
        onDelete = { scope.launch {
            repo.dao.delete(mark)
            if (snackbar.showSnackbar("Момент удалён", "Отменить", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) repo.dao.insert(mark)
        }; editMark = null }) }
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

@Composable fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, fontFamily = FontFamily.Serif, fontSize = 24.sp, modifier = Modifier.padding(top = 24.dp))
        Text(detail, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable fun BookmarkDialog(mark: Bookmark, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var note by rememberSaveable(mark.id) { mutableStateOf(mark.note) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Сохранённый момент") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(mark.quote, fontFamily = FontFamily.Serif)
            Text(listOf(mark.author, mark.bookTitle).filter { it.isNotBlank() }.joinToString(" · "), modifier = Modifier.padding(top = 12.dp))
            Row {
                TextButton(onClick = { copyQuote(context, mark) }) { Text("Копировать") }
                TextButton(onClick = { shareQuote(context, mark) }) { Text("Поделиться") }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Ваша заметка") }, minLines = 2, maxLines = 5, modifier = Modifier.padding(top = 8.dp)) }
    }, confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Сохранить") } },
        dismissButton = { Row { TextButton(onClick = { confirmDelete = true }) { Text("Удалить") }; TextButton(onClick = onDismiss) { Text("Закрыть") } } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Удалить момент?") },
        text = { Text("Цитата и заметка будут удалены. Действие можно сразу отменить.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } })
}
