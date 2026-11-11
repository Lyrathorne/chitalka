# Сборка release

Первый MVP сохраняет сертификат, которым подписаны локальные APK 0.1–0.8.1, чтобы Android разрешал обновление без удаления данных. Ключ не менялся: он перенесён в отдельное PKCS12-хранилище с новым случайным паролем. Новое хранилище, конфигурация и локальная резервная копия находятся вне репозитория. Нельзя публиковать их вместе с артефактом.

Gradle читает локальный файл `~/.codex/private/chitalka/signing.properties` или путь из `-PchitalkaSigningFile`. Поля:

```properties
storeFile=/private/path/chitalka-release.p12
storePassword=<private>
keyAlias=<private>
keyPassword=<private>
```

Без приватной конфигурации явный запуск release останавливается. CI собирает debug без этого файла; его временный сертификат не используется для распространяемых обновлений.

```powershell
rtk proxy .\gradlew.bat :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease -PchitalkaBuildRoot=C:/AndroidBuilds/chitalka
```

Release не является отладочным; R8 пока отключён для сохранения проверенной работы Readium/Room и инструментальных проверок. Это осознанное решение первого MVP, а не обещание минимального размера APK.

Для проверки release через AndroidJUnit: `-PchitalkaTestBuildType=release` и `:app:assembleReleaseAndroidTest`. Для чистой установки без удаления пользовательских данных тестовые APK получают отдельный applicationId через `-PchitalkaValidationIdSuffix=.mvpcheck` / `.mvprestore`. Не применять очистку данных к основному `ru.chitalka.app`.

Проверить APK: apksigner verify, сертификат, versionCode/versionName и отсутствие debuggable; затем установить именно этот файл и опубликовать с SHA-256. Локальная резервная копия ключа не заменяет отдельную копию на другом защищённом носителе.
