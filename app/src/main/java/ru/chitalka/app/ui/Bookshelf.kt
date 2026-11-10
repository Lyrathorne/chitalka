package ru.chitalka.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.chitalka.app.data.Book

@Composable fun Bookshelf(books: List<Book>, open: (Book) -> Unit, delete: (Book) -> Unit) {
    val night = LocalAppTheme.current == AppTheme.NIGHT
    val listState = rememberLazyListState()
    val order = books.map { it.id }
    LaunchedEffect(order, night) { listState.scrollToItem(0) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (night) books.forEach { book -> item(key = book.id) { BookRow(book, open, delete, false); HorizontalDivider(Modifier.padding(top = 18.dp)) } }
        else {
            books.firstOrNull()?.let { book -> item(key = book.id) { BookRow(book, open, delete, true); HorizontalDivider(Modifier.padding(top = 22.dp)) } }
            books.drop(1).chunked(2).forEach { row -> item(key = row.first().id) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { book -> Column(Modifier.weight(1f).clickable { open(book) }) {
                        BookCover(book, Modifier.fillMaxWidth().aspectRatio(.68f))
                        Row(verticalAlignment = Alignment.Top) {
                            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(top = 10.dp))
                            BookMenu(book, delete)
                        }
                        Text(book.author.ifBlank { "Автор не указан" }, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 14.sp)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            } }
        }
    }
}

@Composable private fun BookRow(book: Book, open: (Book) -> Unit, delete: (Book) -> Unit, featured: Boolean) {
    Row(Modifier.fillMaxWidth().clickable { open(book) }, verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.size(if (featured) 108.dp else 90.dp, if (featured) 162.dp else 135.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 22.sp, fontWeight = if (featured) FontWeight.SemiBold else FontWeight.Normal, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(book.author.ifBlank { "Автор не указан" }, fontFamily = FontFamily.Serif, fontSize = 16.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
            Spacer(Modifier.height(22.dp))
            if (book.lastRead != 0L) LinearProgressIndicator(progress = { book.progress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
            Text(if (book.lastRead == 0L) "Ещё не начата" else "Прочитано ${(book.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 7.dp))
        }
        BookMenu(book, delete)
    }
}

@Composable private fun BookMenu(book: Book, delete: (Book) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.MoreVert, "Действия с книгой ${book.title}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Удалить книгу") }, onClick = { expanded = false; delete(book) })
        }
    }
}

@Composable fun BookCover(book: Book, modifier: Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, book.cover) { value = withContext(Dispatchers.IO) { book.cover?.let { android.graphics.BitmapFactory.decodeFile(it) } } }
    val image = bitmap
    if (image != null) Image(image.asImageBitmap(), null, modifier.clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
    else Surface(modifier.clip(RoundedCornerShape(7.dp)), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            HorizontalDivider(color = MaterialTheme.colorScheme.primary)
            Text(book.title, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(book.author.ifBlank { "EPUB" }, fontFamily = FontFamily.Serif, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSecondaryContainer)
            HorizontalDivider(color = MaterialTheme.colorScheme.primary)
        }
    }
}
