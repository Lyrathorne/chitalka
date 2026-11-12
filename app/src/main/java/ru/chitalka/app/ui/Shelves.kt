package ru.chitalka.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import ru.chitalka.app.data.Book

/** Shelf membership uses original hashes, so deleting and reimporting a book preserves its shelf. */
@Composable fun ShelfBookshelf(books: List<Book>, open: (Book) -> Unit, delete: (Book) -> Unit) {
    val prefs = LocalContext.current.getSharedPreferences("bookshelves", 0)
    var revision by remember { mutableIntStateOf(0) }
    val data = remember(revision) { runCatching { JSONObject(prefs.getString("data", "{}")!!) }.getOrDefault(JSONObject()) }
    val shelves = data.keys().asSequence().toList().sorted()
    var selected by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<Book?>(null) }
    fun save() { prefs.edit().putString("data", data.toString()).apply(); revision++ }
    Row(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { menu = true }) { Text(if (selected.isEmpty()) "Все книги" else selected, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Все книги") }, onClick = { selected = ""; menu = false })
                shelves.forEach { shelf -> DropdownMenuItem(text = { Text(shelf) }, onClick = { selected = shelf; menu = false }) }
            }
        }
        TextButton(onClick = { creating = true; name = "" }) { Text("Новая полка") }
    }
    val filtered = if (selected.isEmpty()) books else books.filter { book -> data.optJSONArray(selected)?.let { a -> (0 until a.length()).any { a.optString(it) == book.hash } } == true }
    if (filtered.isEmpty() && selected.isNotEmpty()) Text("Полка пуста. В меню книги выберите «На полку».")
    Bookshelf(filtered, open, delete, assign = { target = it })
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("Новая полка") }, text = {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text("Название") }, singleLine = true)
    }, confirmButton = { TextButton(enabled = name.trim().isNotEmpty() && !data.has(name.trim()), onClick = {
        data.put(name.trim(), org.json.JSONArray()); save(); selected = name.trim(); creating = false
    }) { Text("Создать") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Отмена") } })
    target?.let { book -> AlertDialog(onDismissRequest = { target = null }, title = { Text("На полку") }, text = {
        Column {
            if (shelves.isEmpty()) Text("Сначала создайте полку в библиотеке.")
            shelves.forEach { shelf ->
                val array = data.optJSONArray(shelf) ?: org.json.JSONArray()
                val hashes = (0 until array.length()).map { array.getString(it) }
                Row {
                    Checkbox(book.hash in hashes, onCheckedChange = { checked ->
                        data.put(shelf, org.json.JSONArray(if (checked) hashes + book.hash else hashes.filterNot { it == book.hash })); save()
                    })
                    Text(shelf)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { target = null }) { Text("Готово") } }) }
}
