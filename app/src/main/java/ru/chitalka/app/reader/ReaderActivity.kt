@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package ru.chitalka.app.reader

import android.graphics.Color
import android.content.Intent
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
import ru.chitalka.app.ModelDownloadActivity
import ru.chitalka.app.copyQuote
import ru.chitalka.app.shareQuote
import ru.chitalka.app.quoteChapter
import ru.chitalka.app.quoteDate
import ru.chitalka.app.ui.AppTheme
import ru.chitalka.app.ui.palette
import ru.chitalka.app.data.*
import java.io.File
import java.util.UUID

private val android.content.Context.readerPreferences by preferencesDataStore("reader")

class ReaderActivity : ru.chitalka.app.ui.ThemedActivity() {
    private val repo get() = (application as ChitalkaApp).repository
    private var publication: Publication? = null
    private var navigator: EpubNavigatorFragment? = null
    private var book: Book? = null
    private var popup: PopupWindow? = null
    private var selectionJob: Job? = null
    private var translationJob: Job? = null
    private var selectionMode: ActionMode? = null
    private val translator get() = (application as ChitalkaApp).translator
    private val modelDownloads get() = (application as ChitalkaApp).modelDownloads
    private lateinit var root: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var progress: TextView
    private lateinit var footer: TextView
    private lateinit var container: FrameLayout
    private var fontSize = 110.0
    private var dark = false
    private val appearance get() = (application as ChitalkaApp).themes
    private val colors get() = appearance.current.value.palette
    private val paper get() = colors.background
    private val olive get() = colors.accent
    private var returnLocator: Locator? = null
    private var firstProgress = true
    private var bookmarkCache = emptyList<Bookmark>()
    private var momentSection = MomentSection.QUOTE

    override fun onCreate(savedInstanceState: Bundle?) {
        // Reopen the publication from disk before creating a navigator; don't restore a fragment
        // whose factory depends on a publication that no longer exists after process death.
        super.onCreate(null)
        dark = appearance.current.value == AppTheme.NIGHT
        returnLocator = parseLocator(savedInstanceState?.getString("returnLocator"))
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }
        toolbar.addView(iconButton(ru.chitalka.app.R.drawable.ic_back, "В библиотеку") { finish() })
        toolbar.addView(iconButton(ru.chitalka.app.R.drawable.ic_contents, "Оглавление") { showContents() })
        progress = label("Открываем книгу…", 16f).apply { typeface = Typeface.create("serif", Typeface.NORMAL); gravity = Gravity.CENTER; maxLines = 2 }
        toolbar.addView(progress, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(iconButton(ru.chitalka.app.R.drawable.ic_bookmark, "Закладки книги") { showBookmarks() })
        toolbar.addView(button("Aa", "Настройки чтения") { showSettings() })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        container = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = label("", 12f).apply { setPadding(dp(24), 0, dp(24), 0) }
        footer.minimumHeight = dp(28)
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionMode != null || popup != null) clearSelection() else finish()
            }
        })
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch {
            try {
                val saved = readerPreferences.data.first()
                fontSize = saved[doublePreferencesKey("fontSize")] ?: 110.0
                val loaded = repo.dao.book(intent.getStringExtra("bookId") ?: "") ?: error("Книга не найдена")
                book = loaded
                val pub = repo.open(File(loaded.path))
                publication = pub
                val initial = parseLocator(savedInstanceState?.getString("readerLocator") ?: intent.getStringExtra("locator") ?: loaded.locator)
                val factory = EpubNavigatorFactory(pub).createFragmentFactory(
                    initialLocator = initial,
                    initialPreferences = preferences(),
                    configuration = EpubNavigatorFragment.Configuration {
                        selectionActionModeCallback = selectionCallback
                        // Scale even EPUBs whose paragraphs use fixed px sizes.
                        @OptIn(org.readium.r2.shared.DelicateReadiumApi::class)
                        useReadiumCssFontSize = false
                        decorationTemplates = org.readium.r2.navigator.html.HtmlDecorationTemplates.defaultTemplates(alpha = 1.0).apply {
                            val highlight = requireNotNull(get(Decoration.Style.Highlight::class))
                            set(Decoration.Style.Highlight::class, highlight.copy(element = { decoration ->
                                highlight.element(decoration).replace("style=\"", "style=\"mix-blend-mode: ${if (dark) "screen" else "multiply"}; ")
                            }))
                            val underline = requireNotNull(get(Decoration.Style.Underline::class))
                            // Publisher resets can otherwise zero out the annotation's border.
                            set(Decoration.Style.Underline::class, underline.copy(stylesheet = underline.stylesheet
                                ?.replace("border: 0 solid var(--underline-color);", "border: 0 solid var(--underline-color) !important;")
                                ?.replace("border-bottom-width: 2px;", "border-bottom-width: 2px !important;")
                                ?.replace("border-left-width: 2px;", "border-left-width: 2px !important;")
                                ?.replace("border-right-width: 2px;", "border-right-width: 2px !important;")))
                        }
                        readiumCssRsProperties = org.readium.r2.navigator.epub.css.RsProperties(
                            selectionBackgroundColor = org.readium.r2.navigator.epub.css.Color.Int(colors.selection),
                            selectionTextColor = org.readium.r2.navigator.epub.css.Color.Int(colors.ink))
                    })
                supportFragmentManager.fragmentFactory = factory
                supportFragmentManager.commitNow { add(container.id, EpubNavigatorFragment::class.java, null, "epub") }
                val nav = supportFragmentManager.findFragmentByTag("epub") as EpubNavigatorFragment
                navigator = nav
                nav.addInputListener(object : InputListener {
                    override fun onTap(event: TapEvent): Boolean {
                        if (selectionMode != null || popup != null) { clearSelection(); return true }
                        val width = nav.view?.width ?: return false
                        when {
                            event.point.x < width * .22 -> lifecycleScope.launch { nav.goBackward() }
                            event.point.x > width * .78 -> lifecycleScope.launch { nav.goForward() }
                            else -> {
                                // Preserve the navigator viewport and pagination when hiding controls.
                                val visibility = if (toolbar.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                                toolbar.visibility = visibility; footer.visibility = visibility
                            }
                        }
                        return true
                    }
                })
                nav.addDecorationListener("bookmarks", object : DecorableNavigator.Listener {
                    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                        val mark = bookmarkCache.find { it.id == event.decoration.id } ?: return false
                        runOnUiThread { clearSelection(); editBookmark(mark) }
                        return true
                    }
                })
                launch {
                    nav.currentLocator.collect { locator ->
                        val total = locator.locations.totalProgression ?: loaded.progress
                        progress.text = locator.title ?: pub.readingOrder.find { it.url() == locator.href }?.title ?: loaded.title
                        footer.text = "${(total * 100).toInt()}%"
                        // Do not overwrite a restored location with the navigator's transient start.
                        if (firstProgress) { firstProgress = false; delay(500) }
                        repo.dao.progress(loaded.id, locator.toJSON().toString(), total, System.currentTimeMillis())
                    }
                }
                launch {
                    repo.dao.bookmarks().collect { all ->
                        bookmarkCache = all.filter { it.bookHash == loaded.hash }
                        nav.applyDecorations(bookmarkCache.filter { it.section != MomentSection.BOOKMARK }.mapNotNull { mark -> parseLocator(mark.locator)?.let {
                            Decoration(mark.id, it, if (mark.section == MomentSection.NOTE) Decoration.Style.Underline(olive) else Decoration.Style.Highlight(colors.highlight))
                        } }, "bookmarks")
                    }
                }
                if (saved[booleanPreferencesKey("hintShown")] != true) {
                    AlertDialog.Builder(this@ReaderActivity).setTitle("Читайте в своём темпе")
                        .setMessage("Удерживайте слово и нажмите «Перевести». Выделите фрагмент и нажмите «Цитата», чтобы сохранить его, или «Заметка», чтобы добавить свою мысль. Коснитесь центра страницы, чтобы скрыть управление.")
                        .setPositiveButton("Понятно") { _, _ -> lifecycleScope.launch { readerPreferences.edit { it[booleanPreferencesKey("hintShown")] = true } } }.showThemed()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                AlertDialog.Builder(this@ReaderActivity).setTitle("Не удалось открыть книгу")
                    .setMessage(e.message).setPositiveButton("В библиотеку") { _, _ -> finish() }.setCancelable(false).showThemed()
            }
        }
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            selectionMode = mode
            menu.clear()
            // An empty ActionMode retains Android's selection handles without a second toolbar.
            mode.hide(60_000)
            observeSelection()
            return true
        }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear(); mode.hide(60_000); return true
        }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
        override fun onDestroyActionMode(mode: ActionMode) {
            // WebView may recreate/end ActionMode while a changed DOM range remains selected.
            // The range observer owns the card's lifetime, rather than the native toolbar.
            if (selectionMode === mode) { selectionMode = null; translationJob?.cancel() }
        }
    }

    private fun clearSelection() {
        selectionJob?.cancel(); translationJob?.cancel()
        popup?.dismiss(); popup = null
        selectionMode?.finish(); selectionMode = null
        navigator?.clearSelection()
    }

    private fun observeSelection(initialKey: String? = null) {
        selectionJob?.cancel()
        selectionJob = lifecycleScope.launch {
            var previous: String? = initialKey
            var emptyReads = 0
            while (isActive) {
                val captured = currentSelection()
                val selection = captured?.selection
                emptyReads = if (selection == null) emptyReads + 1 else 0
                val key = captured?.let { it.selection.locator.toJSON().toString() + it.original }
                if (key != previous) {
                    previous = key
                    translationJob?.cancel(); popup?.dismiss(); popup = null
                    if (selection != null && !selection.locator.text.highlight.isNullOrBlank()) showSelection(selection, captured.original)
                }
                if (emptyReads >= 5) { clearSelection(); break }
                delay(140)
            }
        }
    }

    private data class CapturedSelection(val selection: Selection, val original: String)
    private suspend fun currentSelection(): CapturedSelection? {
        val nav = navigator ?: return null
        val selected = nav.currentSelection() ?: return null
        val fallback = selected.locator.text.highlight.orEmpty()
        val rect = selected.rect ?: return CapturedSelection(selected, fallback)
        // Readium 3.1.2 offsets rect.top by the page inset but leaves bottom unchanged.
        // Preserve the DOM range's actual height when anchoring below a selection.
        val raw = nav.evaluateJavascript("(function(){const s=readium.getCurrentSelection();if(s)s.originalText=window.getSelection().toString();return s;})()")
        val parsed = runCatching { JSONObject(raw ?: "null") }.getOrNull()
        val original = parsed?.optString("originalText")?.trim()?.takeIf { it.isNotBlank() } ?: fallback
        val height = runCatching { parsed!!.getJSONObject("rect").getDouble("height").toFloat() }.getOrNull()
            ?: return CapturedSelection(selected, original)
        return CapturedSelection(selected.copy(rect = android.graphics.RectF(rect.left, rect.top, rect.right, rect.top + height)), original)
    }

    private fun showSelection(selection: Selection, original: String) {
        val text = selection.locator.text.highlight?.trim().orEmpty()
        if (text.isBlank()) return
        val bubble = ru.chitalka.app.ui.ReadingBubble(if (dark) Color.rgb(40, 47, 39) else paper, if (dark) Color.rgb(121, 128, 111) else olive, resources.displayMetrics.density)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(text, 20f).apply { contentDescription = "Выделенный фрагмент"; typeface = Typeface.create("serif", Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        val save = button("＋", "Сохранить слово") { }
        save.background = GradientDrawable().apply { setColor(olive); cornerRadius = dp(8).toFloat() }
        save.setTextColor(colors.onAccent)
        save.visibility = View.GONE
        heading.addView(save)
        body.addView(heading)
        val output = label("", 17f).apply { minimumHeight = dp(48) }
        val resultScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(160), View.MeasureSpec.AT_MOST))
            }
        }.apply { visibility = View.GONE; addView(output) }
        body.addView(resultScroll)
        val attribution = label("Машинный перевод • Google Translate", 10f).apply { visibility = View.GONE }
        body.addView(attribution)
        val googleBadge = FrameLayout(this).apply {
            visibility = View.GONE; minimumHeight = dp(48)
            addView(ImageView(this@ReaderActivity).apply {
                setImageResource(if (dark) ru.chitalka.app.R.drawable.google_translate_white else ru.chitalka.app.R.drawable.google_translate_color)
                contentDescription = "Powered by Google Translate"
            }, FrameLayout.LayoutParams(dp(176), dp(16), Gravity.CENTER_VERTICAL or Gravity.START))
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://translate.google.com/"))) }
        }
        body.addView(googleBadge)
        var meanings = emptyList<DictionaryMeaning>()
        val meaningButton = Button(this).apply {
            this.text = "Выбрать значение"; contentDescription = "Словарные значения"; isAllCaps = false; textSize = 13f
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT); minimumHeight = dp(48); visibility = View.GONE
        }
        body.addView(meaningButton, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val translateButton = Button(this).apply {
            this.text = "Перевести"; isAllCaps = false; contentDescription = "Перевести выделенное"
            textSize = 13f; minWidth = dp(48); minimumWidth = dp(48); setPadding(dp(4), 0, dp(4), 0)
            backgroundTintList = android.content.res.ColorStateList.valueOf(olive); setTextColor(colors.onAccent)
        }
        val note = Button(this).apply {
            this.text = "Заметка"; contentDescription = "Добавить заметку"; isAllCaps = false; textSize = 13f
            minWidth = dp(48); minimumWidth = dp(48); setPadding(dp(4), 0, dp(4), 0)
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val draft = momentDraft(selection.locator, original) ?: return@setOnClickListener
                clearSelection()
                lifecycleScope.launch {
                    val existing = repo.dao.bookmarkByLocation(draft.bookHash, draft.locator)
                    editBookmark(existing ?: draft, selectionNote = true, newMoment = existing == null)
                }
            }
        }
        val mark = Button(this).apply {
            this.text = "Цитата"; contentDescription = "Сохранить цитату"; isAllCaps = false; textSize = 13f
            minWidth = dp(48); minimumWidth = dp(48); setPadding(dp(4), 0, dp(4), 0)
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch {
                    try { saveBookmark(selection.locator, original); clearSelection() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { isEnabled = true; toast("Не удалось сохранить цитату. Попробуйте ещё раз") }
                }
            }
        }
        val actionButtons = listOf(translateButton, note, mark)
        val actionWidths = actionButtons.map { action ->
            action.setSingleLine(true)
            action.minimumHeight = dp(48)
            // Allocate space for the actual font instead of squeezing three equal columns.
            maxOf(dp(48), kotlin.math.ceil(action.paint.measureText(action.text.toString())).toInt() + action.compoundPaddingLeft + action.compoundPaddingRight + dp(16))
        }
        val width = minOf(maxOf(dp(260), actionWidths.sum() + dp(32)), root.width - dp(32))
        val available = width - dp(32)
        fun addRow(indices: List<Int>, parent: LinearLayout) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            if (indices.sumOf { actionWidths[it] } <= available) {
                indices.forEach { i -> row.addView(actionButtons[i], LinearLayout.LayoutParams(actionWidths[i], -2, 1f)) }
                parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            } else indices.forEach { i -> parent.addView(actionButtons[i], LinearLayout.LayoutParams(-1, -2)) }
        }
        actions.orientation = LinearLayout.VERTICAL
        if (actionWidths.sum() <= available) addRow(listOf(0, 1, 2), actions)
        else { addRow(listOf(0), actions); addRow(listOf(1, 2), actions) }
        body.addView(actions)
        val online = Button(this).apply {
            this.text = "Онлайн · MyMemory"; contentDescription = "Онлайн-перевод"; isAllCaps = false; textSize = 13f
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT); minimumHeight = dp(48)
        }
        body.addView(online, LinearLayout.LayoutParams(-1, -2))
        val download = Button(this).apply {
            this.text = "Загрузить переводчик"; isAllCaps = false; textSize = 14f; visibility = View.GONE
            backgroundTintList = android.content.res.ColorStateList.valueOf(olive); setTextColor(colors.onAccent)
        }
        body.addView(download, LinearLayout.LayoutParams(-1, dp(48)))
        val card = FrameLayout(this).apply {
            background = bubble
            addView(object : ScrollView(this@ReaderActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxOf(dp(96), container.height - dp(16)), View.MeasureSpec.AT_MOST))
                }
            }.apply { addView(body); isFillViewport = false })
        }
        val window = PopupWindow(card, width, -2, false).apply {
            elevation = dp(4).toFloat(); isOutsideTouchable = false
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // Selection coordinates include the status-bar inset: use screen coordinates too.
            if (android.os.Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true)
        }
        popup = window
        val position = IntArray(2)
        navigator?.view?.getLocationOnScreen(position)
        val rect = selection.rect
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(container.height, View.MeasureSpec.AT_MOST))
        val height = card.measuredHeight
        val top = (position[1] + (rect?.top ?: 160f)).toInt()
        val bottom = (position[1] + (rect?.bottom ?: 200f)).toInt()
        // Keep the card inside the reading viewport, clear of the reader toolbar and system bars.
        val rootPos = IntArray(2); container.getLocationOnScreen(rootPos)
        fun popupY(popupHeight: Int): Int {
            val above = top - popupHeight - dp(12) > rootPos[1]
            bubble.pointsDown = above
            val preferred = if (above) top - popupHeight - dp(12) else bottom + dp(28)
            val screenY = preferred.coerceIn(rootPos[1], maxOf(rootPos[1], rootPos[1] + container.height - popupHeight))
            val decorPos = IntArray(2); this@ReaderActivity.window.decorView.getLocationOnScreen(decorPos)
            return if (android.os.Build.VERSION.SDK_INT >= 29) screenY else screenY - decorPos[1]
        }
        val x = (position[0] + (rect?.centerX() ?: (root.width / 2f)) - width / 2).toInt().coerceIn(dp(12), maxOf(dp(12), root.width - width - dp(12)))
        bubble.pointX = position[0] + (rect?.centerX() ?: root.width / 2f) - x
        card.addOnLayoutChangeListener { _, _, viewTop, _, viewBottom, _, _, _, _ ->
            if (window.isShowing) window.update(x, popupY(viewBottom - viewTop), -1, -1)
        }
        window.showAtLocation(root, Gravity.TOP or Gravity.LEFT, x, popupY(height))
        var currentTranslation = ""
        var corrected = false
        var translationSource = translator.source
        fun resumeObservation() = lifecycleScope.launch {
            if (popup === window && window.isShowing) currentSelection()?.let { captured ->
                observeSelection(captured.selection.locator.toJSON().toString() + captured.original)
            }
        }
        output.setOnClickListener {
            if (currentTranslation.isBlank()) return@setOnClickListener
            selectionJob?.cancel()
            val editor = EditText(this).apply { setText(currentTranslation); hint = "Ваш перевод"; minLines = 2 }
            val dialog = AlertDialog.Builder(this).setTitle("Исправить перевод").setView(editor)
                .setPositiveButton("Применить", null).setNegativeButton("Отмена", null).showThemed()
            dialog.setOnDismissListener { resumeObservation() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (editor.text.isNotBlank()) {
                    currentTranslation = editor.text.toString().trim(); corrected = true; output.text = currentTranslation
                    attribution.text = "Ваш вариант • $translationSource"; dialog.dismiss()
                } else editor.error = "Введите перевод"
            }
        }
        output.isClickable = false
        var requestMachineTranslation: () -> Unit = {}
        meaningButton.setOnClickListener {
            if (meanings.isEmpty()) return@setOnClickListener
            selectionJob?.cancel()
            AlertDialog.Builder(this).setTitle("Доступные значения")
                .setItems(meanings.map { meaning ->
                    listOf(meaning.headword + meaning.partOfSpeech.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                        meaning.translation, meaning.definition.take(350)).filter { it.isNotBlank() }.joinToString("\n")
                }.plus("Перевести с Google").toTypedArray()) { _, index ->
                    if (index == meanings.size) { requestMachineTranslation(); return@setItems }
                    val chosen = meanings[index]
                    currentTranslation = chosen.translation; corrected = false; output.text = currentTranslation
                    googleBadge.visibility = View.GONE
                    output.contentDescription = "Изменить перевод"; output.isClickable = true; save.visibility = View.VISIBLE
                    translationSource = "${(application as ChitalkaApp).dictionary.source} • ${chosen.headword}"
                    attribution.text = "$translationSource\nСловарный вариант · нажмите перевод, чтобы исправить."
                    save.isEnabled = true; save.text = "＋"
                }.setNegativeButton("Закрыть", null)
                .showThemed().setOnDismissListener { resumeObservation() }
        }
        suspend fun translate(provider: TranslationProvider = translator, useDictionary: Boolean = true) {
            val previous = currentTranslation
            val previousAttribution = attribution.text
            val previousBadge = googleBadge.visibility
            googleBadge.visibility = View.GONE
            online.isEnabled = false
            meaningButton.isEnabled = false
            output.isEnabled = false
            save.visibility = View.GONE
            translateButton.visibility = View.GONE
            resultScroll.visibility = View.VISIBLE; output.text = "Переводим…"
            attribution.visibility = View.VISIBLE
            try {
                require(original.length <= 2000) { "Выделите фрагмент короче 2000 символов" }
                val local = if (!provider.isOnline && useDictionary) try { (application as ChitalkaApp).dictionary.lookup(original.trim()) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { toast("Словарь недоступен. Пробуем машинный перевод."); emptyList() }
                    else emptyList()
                val result = if (local.isNotEmpty()) local.first().translation else provider.translate(original.trim())
                if (popup !== window || !window.isShowing) return
                currentTranslation = result
                output.contentDescription = "Изменить перевод"; output.isClickable = true
                meanings = local
                googleBadge.visibility = if (local.isEmpty() && !provider.isOnline) View.VISIBLE else View.GONE
                translationSource = if (local.isNotEmpty()) "${(application as ChitalkaApp).dictionary.source} • ${local.first().headword}" else provider.source
                corrected = false
                output.text = result; download.visibility = View.GONE; save.visibility = View.VISIBLE
                save.isEnabled = true; save.text = "＋"
                meaningButton.visibility = if (local.size > 1) View.VISIBLE else View.GONE
                attribution.text = if (local.isNotEmpty()) "$translationSource\nСловарный вариант 1 из ${local.size}. Нажмите на перевод, чтобы исправить."
                    else provider.attribution + "\nНажмите на перевод, чтобы исправить."
                if (local.size > 1) {
                    currentTranslation = ""; output.text = "Доступно вариантов: ${local.size}. Выберите подходящий по смыслу."
                    output.contentDescription = "Выбор словарного значения"; output.isClickable = false; save.visibility = View.GONE
                    attribution.text = "${(application as ChitalkaApp).dictionary.source}\nАвтоматический выбор по контексту не выполняется."
                }
                save.setOnClickListener {
                    save.isEnabled = false
                    lifecycleScope.launch {
                        val b = book ?: return@launch
                        val word = Word(UUID.randomUUID().toString(), text, normalizeWord(text), currentTranslation,
                            readingContext(selection.locator.text.before.orEmpty(), text, selection.locator.text.after.orEmpty()),
                            b.hash, b.title, selection.locator.toJSON().toString(), source = if (corrected) "Ваш перевод • $translationSource" else translationSource)
                        try {
                            val inserted = repo.dao.insert(word)
                            save.text = "✓"
                            if (inserted == -1L) toast("Это значение уже в словаре")
                            else com.google.android.material.snackbar.Snackbar.make(root, "Слово сохранено", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                                .setAction("Отменить") { lifecycleScope.launch {
                                    if (repo.dao.wordById(word.id) == word && repo.dao.allReviews().none { it.wordId == word.id }) {
                                        repo.dao.delete(word); if (popup === window) { save.text = "＋"; save.isEnabled = true }
                                    }
                                } }.show()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { save.isEnabled = true; toast("Не удалось сохранить слово. Освободите место и попробуйте ещё раз.") }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (failure: Exception) {
                if (popup === window && window.isShowing) {
                    if (provider.isOnline) {
                        if (previous.isNotBlank()) {
                            googleBadge.visibility = previousBadge
                            output.text = previous; attribution.text = "$previousAttribution\nОнлайн-перевод недоступен. Показан прежний вариант."
                            save.visibility = View.VISIBLE
                        }
                        else { output.text = "Онлайн-перевод недоступен. Проверьте сеть или попробуйте офлайн."; attribution.text = provider.source }
                        toast(failure.message?.takeIf { failure is IllegalStateException || failure is IllegalArgumentException } ?: "Не удалось подключиться к MyMemory. Проверьте интернет.")
                        translateButton.visibility = View.VISIBLE
                        return
                    }
                    if (original.length > 2000) { output.text = "Для перевода выделите фрагмент короче 2000 символов"; return }
                    output.text = if (modelDownloads.state.value.phase == ModelPhase.DOWNLOADING) "Языковая модель скачивается" else "Нужна языковая модель"
                    download.text = if (modelDownloads.state.value.phase == ModelPhase.DOWNLOADING) "Показать загрузку" else "Загрузить переводчик"
                    download.visibility = View.VISIBLE
                    if (previous.isNotBlank()) {
                        googleBadge.visibility = previousBadge
                        output.text = previous; attribution.text = previousAttribution; save.visibility = View.VISIBLE
                        toast("Для офлайн-перевода загрузите языковую модель")
                    }
                }
            } finally { if (popup === window) { online.isEnabled = true; output.isEnabled = true; meaningButton.isEnabled = true } }
        }
        requestMachineTranslation = {
            if (popup === window && window.isShowing) {
                translationJob?.cancel(); translationJob = lifecycleScope.launch { translate(useDictionary = false) }
            }
        }
        online.setOnClickListener {
            try { MyMemoryResponse.validate(original.trim()) }
            catch (e: IllegalArgumentException) { toast(e.message ?: "Выделите короткий абзац"); return@setOnClickListener }
            selectionJob?.cancel()
            AlertDialog.Builder(this).setTitle("Перевести онлайн?")
                .setMessage("Выделенный фрагмент будет отправлен MyMemory. Сервис хранит переданный текст и может передавать его партнёрам. Заметки, словарь и книга целиком не отправляются. Качество может отличаться от офлайн-перевода; есть дневной лимит.")
                .setPositiveButton("Отправить и перевести") { _, _ ->
                    if (popup === window && window.isShowing) {
                        translationJob?.cancel()
                        translationJob = lifecycleScope.launch { translate((application as ChitalkaApp).onlineTranslator) }
                    }
                }.setNegativeButton("Отмена", null).setNeutralButton("Условия") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://mymemory.translated.net/terms-and-conditions")))
                }.showThemed().setOnDismissListener { resumeObservation() }
        }
        download.setOnClickListener {
            clearSelection()
            startActivity(Intent(this, ModelDownloadActivity::class.java).putExtra("start", true))
        }
        translateButton.setOnClickListener {
            if (popup === window && window.isShowing) {
                translationJob?.cancel()
                translationJob = lifecycleScope.launch { translate() }
            }
        }
    }

    private fun momentDraft(locator: Locator, original: String): Bookmark? {
        val b = book ?: return null
        if (original.isBlank()) return null
        return Bookmark(UUID.randomUUID().toString(), b.hash, b.title, b.author, locator.toJSON().toString(), original.trim())
    }

    private suspend fun saveBookmark(locator: Locator, original: String) {
        val draft = momentDraft(locator, original) ?: return
        val saved = repo.moments.quote(draft)
        if (!saved.created) toast("Эта цитата уже в «Моих моментах»")
        else com.google.android.material.snackbar.Snackbar.make(root, "Цитата сохранена", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction("Отменить") { lifecycleScope.launch {
                if (!repo.moments.undoQuote(saved.bookmark)) toast("Цитата уже изменена. Проверьте «Мои моменты»")
            } }.show()
    }

    private fun showBookmarks() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val tabs = LinearLayout(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tabs)
        body.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(280)))
        val dialog = AlertDialog.Builder(this).setTitle("Понравившиеся моменты").setView(body)
            .setPositiveButton("Закладка здесь") { _, _ -> lifecycleScope.launch {
                val b = book ?: return@launch
                val locator = navigator?.currentLocator?.value ?: return@launch
                val draft = Bookmark(UUID.randomUUID().toString(), b.hash, b.title, b.author, locator.toJSON().apply { remove("text") }.toString(), (locator.title ?: progress.text.toString()).ifBlank { b.title }, kind = "BOOKMARK")
                try { repo.moments.quote(draft); toast("Закладка сохранена") }
                catch (_: Exception) { toast("Не удалось сохранить закладку") }
            } }.setNegativeButton("Закрыть", null).apply {
                if (returnLocator != null) setNeutralButton("Назад к чтению") { _, _ -> returnToReading() }
            }.create()
        fun render() {
            tabs.removeAllViews(); list.removeAllViews()
            MomentSection.entries.forEach { section ->
                tabs.addView(Button(this).apply {
                    text = section.title; isAllCaps = false; textSize = 13f; setPadding(0, 0, 0, 0)
                    setTextColor(if (momentSection == section) colors.onAccent else olive)
                    setBackgroundColor(if (momentSection == section) olive else Color.TRANSPARENT)
                    setOnClickListener { momentSection = section; render() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            val marks = bookmarkCache.filter { it.section == momentSection }
            if (marks.isEmpty()) list.addView(label(if (momentSection == MomentSection.BOOKMARK) "Сохраните место кнопкой «Закладка здесь»." else "Выделите текст и выберите «${if (momentSection == MomentSection.NOTE) "Заметка" else "Цитата"}».", 16f))
            marks.forEach { mark -> list.addView(TextView(this).apply {
                text = "${mark.quote.take(100)}\n${quoteChapter(mark)} · ${quoteDate(mark)}" + if (mark.note.isBlank()) "" else "\n${mark.note.take(100)}"
                textSize = 17f; setTextColor(colors.ink); setPadding(dp(8), dp(14), dp(8), dp(14))
                setOnClickListener { dialog.dismiss(); editBookmark(mark) }
            }) }
        }
        render(); dialog.showThemed()
    }

    private fun goToMoment(mark: Bookmark) = lifecycleScope.launch {
        val target = parseLocator(mark.locator)
        val before = navigator?.currentLocator?.value
        if (target != null && navigator?.go(target) == true) {
            if (returnLocator == null) returnLocator = before
            com.google.android.material.snackbar.Snackbar.make(root, "Открыт сохранённый момент", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("К чтению") { returnToReading() }.show()
        } else toast("Не удалось найти место в книге")
    }

    private fun returnToReading() {
        val target = returnLocator ?: return
        lifecycleScope.launch {
            if (navigator?.go(target) == true) returnLocator = null
            else toast("Не удалось вернуться к прежнему месту")
        }
    }

    private fun editBookmark(mark: Bookmark, selectionNote: Boolean = false, newMoment: Boolean = false) {
        var editor: AlertDialog? = null
        fun utility(title: String, action: () -> Unit) = Button(this).apply {
            text = title; isAllCaps = false; textSize = 14f; minimumHeight = dp(48)
            minWidth = dp(48); minimumWidth = dp(48); setSingleLine(true); setPadding(dp(8), 0, dp(8), 0)
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { action() }
        }
        val saveButton = utility("Сохранить") {}.apply {
            contentDescription = "Сохранить момент"
            background = GradientDrawable().apply { setColor(olive); cornerRadius = dp(8).toFloat() }
            setTextColor(colors.onAccent)
        }
        val quoteInput = EditText(this).apply {
            setText(mark.quote); hint = if (mark.section == MomentSection.BOOKMARK) "Название закладки" else "Текст цитаты"; contentDescription = hint
            textSize = 18f; typeface = Typeface.create("serif", Typeface.NORMAL); minLines = 2; maxLines = 5
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val input = EditText(this).apply {
            setText(mark.note); hint = "Ваша заметка"; contentDescription = "Ваша заметка"; textSize = 16f; minLines = 2; maxLines = 5
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(16))
            addView(label(quoteInput.hint.toString(), 13f).apply { setTextColor(olive) })
            addView(quoteInput)
            addView(label("Исходный текст книги не меняется.", 12f))
            addView(label("Ваша заметка", 13f).apply { setPadding(0, dp(12), 0, 0); setTextColor(olive) })
            addView(input)
            if (!newMoment) {
                addView(utility("К месту в книге") { editor?.dismiss(); goToMoment(mark) }, LinearLayout.LayoutParams(-1, -2))
                addView(ru.chitalka.app.ui.ActionFlow(this@ReaderActivity).apply {
                    addView(utility("Копировать") { copyQuote(this@ReaderActivity, mark) })
                    addView(utility("Поделиться") { shareQuote(this@ReaderActivity, mark) })
                })
            }
            addView(ru.chitalka.app.ui.ActionFlow(this@ReaderActivity).apply {
                setPadding(0, dp(16), 0, 0)
                addView(saveButton)
                addView(utility(if (newMoment) "Отмена" else "Закрыть") { editor?.dismiss() })
                if (!newMoment) addView(utility("Удалить") { editor?.dismiss(); deleteBookmark(mark) })
            })
        }
        val builder = AlertDialog.Builder(this).setTitle(if (selectionNote) "Заметка к фрагменту" else "Сохранённый момент")
            .setView(ScrollView(this).apply { addView(content) })
        val dialog = builder.showThemed()
        editor = dialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun valid() = quoteInput.text.isNotBlank() && (!newMoment || input.text.isNotBlank())
        fun updateSave() { saveButton.isEnabled = valid(); saveButton.alpha = if (saveButton.isEnabled) 1f else .45f }
        updateSave()
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateSave() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        input.addTextChangedListener(watcher); quoteInput.addTextChangedListener(watcher)
        saveButton.setOnClickListener {
            saveButton.isEnabled = false
            saveButton.alpha = .45f
            lifecycleScope.launch {
                try {
                    if (newMoment) repo.moments.note(mark.copy(quote = quoteInput.text.toString()), input.text.toString())
                    else repo.moments.edit(mark.id, quoteInput.text.toString(), input.text.toString())
                    dialog.dismiss(); toast("Изменения сохранены")
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { updateSave(); toast("Не удалось сохранить заметку. Попробуйте ещё раз") }
            }
        }
    }

    private fun deleteBookmark(mark: Bookmark) {
        AlertDialog.Builder(this).setTitle("Удалить момент?").setMessage("Цитата и заметка будут удалены. Действие можно сразу отменить.")
            .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ -> lifecycleScope.launch {
                repo.dao.delete(mark)
                com.google.android.material.snackbar.Snackbar.make(root, "Момент удалён", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .setAction("Отменить") { lifecycleScope.launch { repo.dao.insert(mark) } }.show()
            } }.showThemed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("returnLocator", returnLocator?.toJSON()?.toString())
        outState.putString("readerLocator", navigator?.currentLocator?.value?.toJSON()?.toString() ?: intent.getStringExtra("locator") ?: book?.locator)
        super.onSaveInstanceState(outState)
    }

    private fun showContents() {
        val pub = publication ?: return
        fun flatten(links: List<Link>): List<Link> = links.flatMap { listOf(it) + flatten(it.children) }
        val links = flatten(pub.tableOfContents).ifEmpty { pub.readingOrder }
        AlertDialog.Builder(this).setTitle("Оглавление")
            .setItems(links.mapIndexed { i, link -> link.title ?: "Раздел ${i + 1}" }.toTypedArray()) { _, index -> lifecycleScope.launch { navigator?.go(links[index]) } }
            .setNegativeButton("Закрыть", null).showThemed()
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
        layout.addView(Button(this).apply {
            text = "Оформление · ${appearance.current.value.title}"; isAllCaps = false
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                AlertDialog.Builder(this@ReaderActivity).setTitle("Оформление")
                    .setSingleChoiceItems(AppTheme.entries.map { it.title }.toTypedArray(), AppTheme.entries.indexOf(appearance.current.value)) { dialog, which ->
                        dialog.dismiss()
                        lifecycleScope.launch { appearance.select(AppTheme.entries[which]) }
                    }.setNegativeButton("Закрыть", null).showThemed()
            }
        })
        layout.addView(label("Перевод выполняется на устройстве после загрузки модели Google ML Kit. Это машинный перевод, а не полный словарь значений.", 13f))
        layout.addView(Button(this).apply {
            text = "Языковая модель · состояние загрузки"; isAllCaps = false
            setTextColor(olive); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@ReaderActivity, ModelDownloadActivity::class.java)) }
        })
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        layout.addView(label("Читалка · $version", 13f).apply { setPadding(0, dp(12), 0, 0) })
        AlertDialog.Builder(this).setTitle("Настройки чтения").setView(layout).setPositiveButton("Готово", null).showThemed()
    }

    private fun AlertDialog.Builder.showThemed(): AlertDialog {
        return create().showThemed()
    }
    private fun AlertDialog.showThemed(): AlertDialog {
        val dialog = this
        dialog.show()
        val surface = GradientDrawable().apply { setColor(if (dark) Color.rgb(37, 45, 36) else paper); cornerRadius = dp(14).toFloat() }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.findViewById<View>(androidx.appcompat.R.id.parentPanel)?.apply { background = surface; clipToOutline = true }
        dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply { setTextColor(colors.ink); typeface = Typeface.create("serif", Typeface.NORMAL) }
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
            dialog.getButton(which)?.apply { setTextColor(olive); isAllCaps = false }
        }
        return dialog
    }

    private fun preferences() = EpubPreferences(fontSize = fontSize / 100.0, lineHeight = 1.6,
        pageMargins = 1.2, publisherStyles = false, theme = if (dark) Theme.DARK else Theme.LIGHT,
        backgroundColor = org.readium.r2.navigator.preferences.Color(paper),
        textColor = org.readium.r2.navigator.preferences.Color(colors.ink))

    private fun applyPreferences() {
        clearSelection()
        navigator?.submitPreferences(preferences())
        lifecycleScope.launch { readerPreferences.edit { it[doublePreferencesKey("fontSize")] = fontSize } }
    }
    private fun label(text: String, size: Float) = TextView(this).apply { this.text = text; textSize = size; setTextColor(colors.ink); gravity = Gravity.CENTER_VERTICAL }
    private fun button(text: String, description: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; contentDescription = description; minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)); setTextColor(olive); setBackgroundColor(Color.TRANSPARENT); setPadding(0,0,0,0); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun iconButton(drawable: Int, description: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(olive)
        contentDescription = description; setBackgroundColor(Color.TRANSPARENT); setPadding(dp(13), dp(13), dp(13), dp(13))
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(52)); setOnClickListener { action() }
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    override fun onDestroy() {
        selectionJob?.cancel(); translationJob?.cancel(); popup?.dismiss()
        super.onDestroy()
        publication?.close()
    }
    override fun onStop() {
        clearSelection()
        super.onStop()
    }
    companion object {
        fun parseLocator(value: String?): Locator? = value?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
    }
}
