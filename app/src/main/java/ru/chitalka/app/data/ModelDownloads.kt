package ru.chitalka.app.data

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.Locale

enum class ModelPhase { CHECKING, MISSING, DOWNLOADING, READY, ERROR }

data class ModelDownloadState(
    val phase: ModelPhase = ModelPhase.CHECKING,
    val bytes: Long = 0,
    val total: Long = -1,
    val elapsedSeconds: Long = 0,
    val detail: String = "Проверяем языковую модель…",
) {
    // Archive progress and model readiness are separate: installing can follow 100% download.
    val percent: Int? get() = if (total > 0) (bytes.coerceIn(0, total) * 100 / total).toInt() else null
    val title: String get() = when (phase) {
        ModelPhase.CHECKING -> "Проверка переводчика"
        ModelPhase.MISSING -> "Переводчик не загружен"
        ModelPhase.DOWNLOADING -> if (percent == 100) "Установка модели…" else if (percent != null) "Загрузка модели · $percent%" else "Загрузка модели…"
        ModelPhase.READY -> "Переводчик готов"
        ModelPhase.ERROR -> "Не удалось загрузить модель"
    }
    val amount: String? get() = if (total > 0) String.format(Locale.forLanguageTag("ru"), "%.1f / %.1f МБ", bytes / 1048576.0, total / 1048576.0) else null
}

/** Application-owned state: changing a word or leaving the reader cannot cancel a download. */
class ModelDownloads(private val context: Context, private val translator: TranslationRepository,
    private val timeoutMillis: Long = 15 * 60 * 1000L) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ModelDownloadState())
    val state = mutable.asStateFlow()
    private var task: Job? = null
    private val manager = RemoteModelManager.getInstance()
    private val russian = TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build()

    init { refresh() }

    fun refresh() {
        if (task?.isActive == true) return
        task = scope.launch {
            try {
                if (manager.isModelDownloaded(russian).await()) mutable.value = ready()
                else {
                    mutable.value = ModelDownloadState(ModelPhase.MISSING, detail = "Загрузите модель один раз. После этого перевод работает без интернета.")
                    val pending = withContext(Dispatchers.IO) { readDownload(System.currentTimeMillis()) }
                    if (pending?.status in listOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED)) {
                        task = null
                        start()
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = ModelDownloadState(ModelPhase.MISSING, detail = "Проверить модель не удалось. Попробуйте загрузить её снова.")
            }
        }
    }

    fun start() {
        if (mutable.value.phase == ModelPhase.DOWNLOADING || mutable.value.phase == ModelPhase.READY) return
        task?.cancel()
        mutable.value = ModelDownloadState(ModelPhase.DOWNLOADING, detail = "Подключаемся к серверу модели…")
        task = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val startedWall = System.currentTimeMillis()
            var lastBytes = -1L
            var lastChange = started
            val monitor = launch {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val snapshot = withContext(Dispatchers.IO) { readDownload(startedWall) }
                    if (snapshot != null && snapshot.bytes != lastBytes) { lastBytes = snapshot.bytes; lastChange = now }
                    val detail = when {
                        !online() -> "Нет подключения. Android ожидает интернет; загрузка продолжится после подключения."
                        snapshot?.status == DownloadManager.STATUS_PAUSED -> when (snapshot.reason) {
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI, DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Ожидаем сеть или Wi-Fi. Можно вернуться к чтению."
                            else -> "Android приостановил загрузку и повторит подключение."
                        }
                        snapshot?.status == DownloadManager.STATUS_FAILED -> "Android сообщил об ошибке загрузки. Ожидаем ответ переводчика…"
                        snapshot != null && snapshot.total > 0 && snapshot.bytes >= snapshot.total -> "Файл получен. Проверяем и устанавливаем модель…"
                        snapshot != null && snapshot.bytes > 0 && now - lastChange > 15000 -> "Данные временно не поступают. Ждём ответ сервера; загрузка не завершена."
                        snapshot != null && snapshot.bytes > 0 -> "Скачиваем языковую модель. Можно продолжать читать."
                        now - started > 15000 -> "Сервер пока не передаёт данные. Проверьте интернет; при необходимости включите VPN и повторите попытку."
                        else -> "Подключаемся к серверу модели…"
                    }
                    mutable.value = ModelDownloadState(ModelPhase.DOWNLOADING, snapshot?.bytes ?: 0,
                        snapshot?.total ?: -1, (now - started) / 1000, detail)
                    delay(1000)
                }
            }
            try {
                withTimeout(timeoutMillis) { translator.prepare() }
                monitor.cancelAndJoin()
                mutable.value = ready()
            } catch (_: TimeoutCancellationException) {
                monitor.cancelAndJoin()
                mutable.value = mutable.value.copy(phase = ModelPhase.ERROR,
                    detail = "Сервер долго не завершает загрузку. Проверьте сеть или VPN и нажмите «Повторить». Android может сохранить уже скачанную часть.")
            } catch (e: CancellationException) { monitor.cancel(); throw e }
            catch (_: Exception) {
                monitor.cancelAndJoin()
                mutable.value = mutable.value.copy(phase = ModelPhase.ERROR,
                    detail = "Проверьте интернет и свободное место. Если сервер недоступен, попробуйте другую сеть или VPN, затем повторите загрузку.")
            }
        }
    }

    private fun ready() = ModelDownloadState(ModelPhase.READY, detail = "Английский → русский · можно переводить без интернета.")

    private fun online(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private data class Snapshot(val bytes: Long, val total: Long, val status: Int, val reason: Int)
    private fun readDownload(startedWall: Long): Snapshot? = runCatching {
        val downloads = context.getSystemService(DownloadManager::class.java)
        downloads.query(DownloadManager.Query()).use { cursor ->
            var latestId = -1L
            var found: Snapshot? = null
            while (cursor.moveToNext()) {
                val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI)).orEmpty()
                // Public DownloadManager API; no access to ML Kit's private files or database.
                if (!uri.contains("gvt1.com") && !uri.contains("googleapis.com") && !uri.contains("google.com")) continue
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val modified = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP))
                if (status in listOf(DownloadManager.STATUS_SUCCESSFUL, DownloadManager.STATUS_FAILED) && modified < startedWall - 2000) continue
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                if (id <= latestId) continue
                latestId = id
                found = Snapshot(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                    status,
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
            }
            found
        }
    }.getOrNull()
}
