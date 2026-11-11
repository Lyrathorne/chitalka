# Источники и лицензии Windows-версии

Оригинальный код приложения опубликован в репозитории пользователя; этот документ не назначает ему стороннюю лицензию.

| Компонент | Лицензия / источник |
|---|---|
| .NET / WPF / Microsoft.Data.Sqlite | MIT, https://github.com/dotnet |
| SQLite | Public domain, https://sqlite.org/copyright.html |
| WebView2 | Условия Microsoft, https://learn.microsoft.com/microsoft-edge/webview2/ |
| epub.js 0.3.93 | BSD-2-Clause, `Web/vendor/EPUBJS-LICENSE.txt` |
| JSZip 3.10.1 | MIT (выбран из MIT/GPLv3), `Web/vendor/JSZIP-LICENSE.txt` |
| FreeDict / WikDict EN→RU | CC BY-SA 3.0; Karl Bartel, Wiktionary contributors via DBnary; `Dictionary/manifest.json`, https://download.freedict.org/generated/eng-rus/eng-rus.tei |
| OPUS-MT EN→RU, opus-2020-02-11 | **CC BY 4.0 по LICENSE в исходном архиве**; Helsinki-NLP/OPUS, https://object.pouta.csc.fi/OPUS-MT-models/en-ru/opus-2020-02-11.zip |
| CTranslate2 4.6.3 | MIT, https://github.com/OpenNMT/CTranslate2 |
| SentencePiece 0.2.1 | Apache-2.0, https://github.com/google/sentencepiece |
| Python 3.13.11 embedded | PSF License; `Translator/Runtime/LICENSE.txt` |
| NumPy 2.3.5 | BSD-3-Clause и включённые зависимости; dist-info/licenses в runtime |
| PyYAML 6.0.3 | MIT; dist-info/licenses в runtime |
| setuptools 80.9.0 | MIT и vendored зависимости; dist-info/licenses в runtime |

Модель преобразована исходным OpusMTConverter CTranslate2 4.6.3 с квантованием int8; токенизаторы source.spm/target.spm не менялись. В ZIP модели сохранены исходные README и LICENSE. Указание Apache-2.0 в карточке Hugging Face не подменяет CC BY 4.0 из используемого исходного архива. Точные хеши источника/результата — `Translator/model-manifest.json`.

Словарь использует тот же проверенный пакет, что Android, с доступной изменённой SQLite-базой, manifest и скриптом преобразования `../tools/build_dictionary.py`. Пакет воспроизводим; атрибуция и CC BY-SA сохраняются. Не является гарантией качества всех значений.

Приватный Python-runtime принадлежит приложению и не устанавливает Python в систему. Пакеты взяты из точных PyPI wheels с SHA-256 в `tools/runtime-lock.json`; CPython ZIP проверяется по SHA-256. Лицензии зависимостей остаются внутри поставляемых пакетов. Сводный офлайн-текст также доступен через помощь приложения.

Пять тестовых EPUB Project Gutenberg не включаются в установщик. Их происхождение и контрольные суммы: `../docs/qa/manifest.json`. MyMemory используется только по явному подтверждению; правила сервиса: https://mymemory.translated.net/doc/usagelimits.php и https://mymemory.translated.net/doc/privacy.php.
