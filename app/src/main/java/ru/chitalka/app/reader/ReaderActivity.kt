@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package ru.chitalka.app.reader

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.readium.r2.navigator.*
import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.*
import ru.chitalka.app.ChitalkaApp
import ru.chitalka.app.data.*
import java.io.File
import java.util.UUID

private val android.content.Context.readerPreferences by preferencesDataStore("reader")

class ReaderActivity : AppCompatActivity() {
    private val repo get() = (application as ChitalkaApp).repository
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var book: Book? = null
    private var popup: PopupWindow? = null
    private var selectionJob: Job? = null
    private var modelDownloadJob: Job? = null
    private var selectionMode: ActionMode? = null
    private val translator = TranslationRepository()
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var progress: TextView
    private lateinit var container: FrameLayout
    private var fontSize = 110.0
    private var dark = false
    private var returnLocator: Locator? = null
    private var firstProgress = true
    private var bookmarkCache = emptyList<Bookmark>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reopen the publication from disk before creating a navigator; don't restore a fragment
        // whose factory depends on a publication that no longer exists after process death.
        super.onCreate(null)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        toolbar.addView(button("‹", "В библиотеку") { finish() })
        toolbar.addView(button("☷", "Оглавление") { showContents() })
        progress = label("Открываем книгу…", 12f)
        toolbar.addView(progress, LinearLayout.LayoutParams(0, dp(56), 1f))
        toolbar.addView(button("♡", "Закладки книги") { showBookmarks() })
        toolbar.addView(button("Aa", "Настройки чтения") { showSettings() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        container = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch {
            try {
                val saved = readerPreferences.data.first()
                fontSize = saved[doublePreferencesKey("fontSize")] ?: 110.0
                dark = saved[booleanPreferencesKey("dark")] ?: false
                val loaded = repo.dao.book(intent.getStringExtra("bookId") ?: "") ?: error("Книга не найдена")
                book = loaded
                val pub = repo.open(File(loaded.path))
                publication = pub
                val initial = parseLocator(intent.getStringExtra("locator") ?: loaded.locator)
                val factory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = initial,
                    initialPreferences = preferences(),
                    configuration = EpubNavigatorFragment.Configuration {
                        selectionActionModeCallback = selectionCallback
                    })
                supportFragmentManager.fragmentFactory = factory
                supportFragmentManager.commitNow { add(container.id, EpubNavigatorFragment::class.java, null, "epub") }
                val nav = supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                navigator = nav
                nav.addInputListener(object : InputListener {
                    override fun onTap(event: TapEvent): Boolean {
                        popup?.dismiss()
                        val width = nav.view?.width ?: return false
                        when {
                            event.point.x < width * .22 -> lifecycleScope.launch { nav.goBackward() }
                            event.point.x > width * .78 -> lifecycleScope.launch { nav.goForward() }
                            else -> toolbar.visibility = if (toolbar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        }
                        return true
                    }
                })
                nav.addDecorationListener("bookmarks", object : DecorableNavigator.Listener {
                    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                        bookmarkCache.find { it.id == event.decoration.id }?.let(::editBookmark)
                        return true
                    }
                })
                launch {
                    nav.currentLocator.collect { locator ->
                        val total = locator.locations.totalProgression ?: loaded.progress
                        progress.text = "${(total * 100).toInt()}%"
                        // Do not overwrite a restored location with the navigator's transient start.
                        if (firstProgress) { firstProgress = false; delay(500) }
                        repo.dao.progress(loaded.id, locator.toJSON().toString(), total, System.currentTimeMillis())
                    }
                }
                launch {
                    repo.dao.bookmarks().collect { all ->
                        bookmarkCache = all.filter { it.bookHash == loaded.hash }
                        nav.applyDecorations(bookmarkCache.mapNotNull { mark -> parseLocator(mark.locator)?.let {
                            Decoration(mark.id, it, Decoration.Style.Highlight(Color.rgb(180, 194, 135)))
                        } }, "bookmarks")
                    }
                }
                if (saved[booleanPreferencesKey("hintShown")] != true) {
                    AlertDialog.Builder(this@ReaderActivity).setTitle("Читайте в своём темпе")
                        .setMessage("Удерживайте слово, чтобы увидеть перевод. Расширьте выделение и нажмите «В закладки», чтобы сохранить понравившийся момент. Коснитесь центра страницы, чтобы скрыть управление.")
                        .setPositiveButton("Понятно") { _, _ -> lifecycleScope.launch { readerPreferences.edit { it[booleanPreferencesKey("hintShown")] = true } } }.show()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AlertDialog.Builder(this@ReaderActivity).setTitle("Не удалось открыть книгу")
                    .setMessage(e.message).setPositiveButton("В библиотеку") { _, _ -> finish() }.setCancelable(false).show()
            }
        }
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            selectionMode = mode
            menu.clear()
            menu.add(0, 1, 0, "Перевести")
            menu.add(0, 2, 1, "В закладки")
            scheduleTranslation()
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { scheduleTranslation(); return false }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            lifecycleScope.launch {
                val selection = navigator?.currentSelection() ?: return@launch
                if (item.itemId == 2) {
                    selectionJob?.cancel(); popup?.dismiss()
                    saveBookmark(selection.locator)
                    mode.finish()
                    navigator?.clearSelection()
                } else showTranslation(selection)
            }
            return true
        }
        override fun onDestroyActionMode(mode: ActionMode) { selectionMode = null; selectionJob?.cancel(); popup?.dismiss() }
    }

    private fun scheduleTranslation() {
        selectionJob?.cancel()
        popup?.dismiss()
        selectionJob = lifecycleScope.launch {
            delay(300)
            navigator?.currentSelection()?.let { selection ->
                if ((selection.locator.text.highlight?.length ?: 0) <= 100) showTranslation(selection)
            }
        }
    }

    private suspend fun showTranslation(selection: Selection) {
        val text = selection.locator.text.highlight?.trim().orEmpty()
        if (text.isBlank()) return
        // Our popup owns these actions for short selections. Hide Android's floating toolbar
        // so it cannot cover the translation or intercept the save button.
        selectionMode?.hide(1_000_000)
        popup?.dismiss()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { setColor(paper); cornerRadius = dp(12).toFloat(); setStroke(dp(1), olive) }
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(text.take(100), 20f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        val save = button("＋", "Сохранить слово") { }
        save.isEnabled = false
        save.alpha = .35f
        heading.addView(save)
        body.addView(heading)
        val output = label("Переводим…", 17f)
        body.addView(output)
        body.addView(label("Машинный перевод • Google Translate", 10f))
        val mark = Button(this).apply {
            this.text = "В закладки"; contentDescription = "В закладки"; isAllCaps = false
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { lifecycleScope.launch { saveBookmark(selection.locator); selectionMode?.finish(); navigator?.clearSelection() } }
        }
        body.addView(mark, LinearLayout.LayoutParams(-1, dp(48)))
        val download = Button(this).apply {
            this.text = "Загрузить переводчик"; isAllCaps = false; textSize = 14f; visibility = View.GONE
            backgroundTintList = android.content.res.ColorStateList.valueOf(olive); setTextColor(Color.WHITE)
        }
        body.addView(download, LinearLayout.LayoutParams(-1, dp(48)))
        val width = minOf(dp(300), root.width - dp(32)).coerceAtLeast(dp(180))
        val window = PopupWindow(body, width, -2, false).apply {
            elevation = dp(6).toFloat(); isOutsideTouchable = false
            // Selection coordinates include the status-bar inset: use screen coordinates too.
            if (android.os.Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
        }
        popup = window
        val position = IntArray(2)
        navigator?.view?.getLocationOnScreen(position)
        val rect = selection.rect
        body.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val height = body.measuredHeight
        val top = (position[1] + (rect?.top ?: 160f)).toInt()
        val bottom = (position[1] + (rect?.bottom ?: 200f)).toInt()
        val rootPos = IntArray(2); root.getLocationOnScreen(rootPos)
        fun popupY(popupHeight: Int): Int {
            val preferred = if (top - popupHeight - dp(12) > rootPos[1]) top - popupHeight - dp(12) else bottom + dp(12)
            val screenY = preferred.coerceIn(rootPos[1], maxOf(rootPos[1], rootPos[1] + root.height - popupHeight))
            return if (android.os.Build.VERSION.SDK_INT >= 29) screenY else screenY - rootPos[1]
        }
        val x = (position[0] + (rect?.centerX() ?: (root.width / 2f)) - width / 2).toInt().coerceIn(dp(12), maxOf(dp(12), root.width - width - dp(12)))
        body.addOnLayoutChangeListener { _, _, viewTop, _, viewBottom, _, _, _, _ ->
            if (window.isShowing) window.update(x, popupY(viewBottom - viewTop), -1, -1)
        }
        window.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, popupY(height))
        suspend fun translate() {
            try {
                val result = translator.translate(text)
                if (popup !== window || !window.isShowing) return
                output.text = result; download.visibility = View.GONE; save.isEnabled = true; save.alpha = 1f
                save.setOnClickListener {
                    lifecycleScope.launch {
                        val b = book ?: return@launch
                        repo.dao.insert(Word(UUID.randomUUID().toString(), text, normalizeWord(text), result,
                            listOfNotNull(selection.locator.text.before?.takeLast(160), text, selection.locator.text.after?.take(160)).joinToString(""),
                            b.hash, b.title, selection.locator.toJSON().toString()))
                        save.text = "✓"; save.isEnabled = false
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (popup === window) { output.text = "Нужна языковая модель"; download.visibility = View.VISIBLE }
            }
        }
        download.setOnClickListener {
            if (modelDownloadJob?.isActive == true) return@setOnClickListener
            modelDownloadJob = lifecycleScope.launch {
                download.isEnabled = false; output.text = "Загружаем модель… Нужен интернет"
                try { translator.prepare(); if (popup === window && window.isShowing) translate() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { output.text = "Загрузка не удалась. Проверьте интернет" }
                finally { download.isEnabled = true }
            }
        }
        translate()
    }

    private suspend fun saveBookmark(locator: Locator) {
        val b = book ?: return
        val quote = locator.text.highlight?.trim().orEmpty()
        if (quote.isEmpty()) { toast("Сначала выделите понравившийся фрагмент"); return }
        val mark = Bookmark(UUID.randomUUID().toString(), b.hash, b.title, b.author, locator.toJSON().toString(), quote)
        val inserted = repo.dao.insert(mark)
        if (inserted == -1L) toast("Этот момент уже сохранён")
        else com.google.android.material.snackbar.Snackbar.make(root, "Момент сохранён", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("Отменить") { lifecycleScope.launch { repo.dao.delete(mark) } }.show()
    }

    private fun showBookmarks() {
        val labels = bookmarkCache.map { it.quote.take(100) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Понравившиеся моменты")
            .setItems(labels) { _, which ->
                returnLocator = navigator?.currentLocator?.value
                parseLocator(bookmarkCache[which].locator)?.let { target -> lifecycleScope.launch { navigator?.go(target) } }
            }.setMessage(if (labels.isEmpty()) "Выделите фрагмент и нажмите «В закладки»" else null)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton(if (returnLocator != null) "Назад к чтению" else "") { _, _ ->
                returnLocator?.let { target -> lifecycleScope.launch { navigator?.go(target) } }; returnLocator = null
            }.show()
    }

    private fun editBookmark(mark: Bookmark) {
        val input = EditText(this).apply { setText(mark.note); hint = "Ваша заметка"; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        AlertDialog.Builder(this).setTitle("Сохранённый момент").setMessage(mark.quote.take(500)).setView(input)
            .setPositiveButton("Сохранить") { _, _ -> lifecycleScope.launch { repo.dao.update(mark.copy(note = input.text.toString())) } }
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Удалить") { _, _ -> lifecycleScope.launch { repo.dao.delete(mark) } }.show()
    }

    private fun showContents() {
        val pub = publication ?: return
        fun flatten(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flatten(it.children) }
        val links = flatten(pub.tableOfContents).ifEmpty { pub.readingOrder }
        AlertDialog.Builder(this).setTitle("Оглавление")
            .setItems(links.mapIndexed { i, link -> link.title ?: "Раздел ${i + 1}" }.toTypedArray()) { _, index -> lifecycleScope.launch { navigator?.go(links[index]) } }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(16)) }
        val size = label("Размер текста: ${fontSize.toInt()}%", 18f)
        layout.addView(size)
        val slider = SeekBar(this).apply { max = 120; progress = fontSize.toInt() - 80 }
        layout.addView(slider)
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar?, value: Int, user: Boolean) { fontSize = (80 + value).toDouble(); size.text = "Размер текста: ${fontSize.toInt()}%" }
            override fun onStartTrackingTouch(seek: SeekBar?) {}
            override fun onStopTrackingTouch(seek: SeekBar?) { applyPreferences() }
        })
        layout.addView(Switch(this).apply { text = "Тёмная страница"; isChecked = dark; setOnCheckedChangeListener { _, checked -> dark = checked; applyPreferences() } })
        layout.addView(label("Перевод выполняется на устройстве после загрузки модели Google ML Kit. Это машинный перевод, а не полный словарь значений.", 13f))
        AlertDialog.Builder(this).setTitle("Настройки чтения").setView(layout).setPositiveButton("Готово", null).show()
    }

    private fun preferences() = EpubPreferences(fontSize = fontSize, lineHeight = 1.6,
        pageMargins = 1.2, publisherStyles = false, theme = if (dark) Theme.DARK else Theme.LIGHT,
        backgroundColor = org.readium.r2.navigator.preferences.Color(if (dark) Color.rgb(29,33,31) else paper),
        textColor = org.readium.r2.navigator.preferences.Color(if (dark) Color.rgb(231,228,216) else Color.rgb(41,40,32)))

    private fun applyPreferences() {
        selectionJob?.cancel(); popup?.dismiss(); navigator?.clearSelection()
        navigator?.submitPreferences(preferences())
        lifecycleScope.launch { readerPreferences.edit { it[doublePreferencesKey("fontSize")] = fontSize; it[booleanPreferencesKey("dark")] = dark } }
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.rgb(41,40,32)); gravity = Gravity.CENTER_VERTICAL }
    private fun button(text: String, description: String, action: () -> Unit) = Button(this).apply {
        this.text = text; contentDescription = description; minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); setTextColor(olive); setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        selectionJob?.cancel(); modelDownloadJob?.cancel(); popup?.dismiss(); translator.close()
        super.onDestroy()
        publication?.close()
    }
    companion object {
        private val paper = Color.rgb(246,241,231)
        private val olive = Color.rgb(104,115,77)
        fun parseLocator(value: String?): Locator? = value?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    }
}
