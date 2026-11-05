# Сборка и поставка Docker

Docker — поддерживаемый способ поставки Sambryx. Панель управляет Samba-серверами через SSH.
JRE 21 входит в образ; Java на управляемом сервере не требуется.

## Образы

Поставляется один стандартный образ `andreyvorobevaqa/sambryx` на базе
`eclipse-temurin:21-jre-alpine`. Отдельных образов для Debian и Ubuntu нет:
это целевые семейства управляемого сервера и хоста Docker, а не база образа панели.

Java запускается от UID/GID `10001:10001`. Samba в образ не устанавливается.
Compose в корне проекта остаётся тестовым стендом с отдельным Samba-узлом на Ubuntu 22.04;
Production Compose Smoke использует Samba/SSH-узел на Ubuntu 24.04.
Эти контейнерные проверки не подтверждают все версии Debian/Ubuntu или работу на
нативном хосте каждой из этих ОС. Отдельная проверка Debian в текущем CI не выполняется.

## Локальная сборка

Backend и frontend собираются Maven один раз в Spring Boot JAR. Dockerfile упаковывает
готовый файл вместе с JRE 21, не запускает Maven или npm.

```bash
./mvnw clean package
JAR_FILE=target/sambryx-0.0.1-SNAPSHOT.jar
python3 scripts/ci/checks/verify-artifacts.py "$JAR_FILE"
docker build --build-arg "JAR_FILE=$JAR_FILE" --tag sambryx:local .
bash scripts/ci/smoke/smoke-docker.sh sambryx:local "$JAR_FILE"
```

Путь соответствует стандартной версии проекта. В `target` должен находиться ровно один `*.jar`.
Параметр Docker `JAR_FILE` задаёт путь относительно контекста сборки, по умолчанию `target/*.jar`.
Без готового JAR сборка образа завершится ошибкой. В контекст передаются только JAR-файлы из
`target`; исходники, зависимости npm и локальные настройки исключены.
Target `local` сохранён для совместимости с sandbox-скриптом.

## Проверки CI и выпуск

Задание `Build & Test` выполняет Maven-сборку с тестами, frontend lint/API-тесты и проверяет
обычный Docker-образ. Проверка JAR требует `index.html` и подключённые JavaScript/CSS-ресурсы.

Smoke-проверка запускает временный контейнер с read-only корневой файловой системой
и временным `/tmp`, публикует случайный порт только на `127.0.0.1` и проверяет:

- процесс работает без root;
- SHA-256 JAR совпадает с исходным артефактом;
- UI и ресурсы совпадают с содержимым JAR;
- публичный API сообщает режим `remote`;
- анонимный запрос `/api/auth/me` получает HTTP 401.

Контейнер удаляется после проверки, в том числе при ошибке. Smoke-проверка не подтверждает
SSH-операции или совместимость с Astra.

При выпуске по тегу `v*.*.*` Docker-задание получает проверенный JAR из GitHub Actions artifact.
Стандартный образ упаковывается и проходит smoke-проверку на runner до push. Публикуется этот же
локальный образ без повторной сборки. GitHub Release содержит JAR.

Экспериментальный Astra-образ исключён из текущей сборки и публикации.
Ранее опубликованные `astra-*` теги не удаляются и не обновляются этим workflow.
Совместимость с Astra Linux 1.8 и Parsec не подтверждена; дальнейшая проверка Docker + SSH
отслеживается в [#10](https://github.com/AndreyJVM/sambryx/issues/10) и требует доступа к целевой ОС.
Стандартный образ сохраняет прежние настройки JVM без ослабления MPROTECT.

## SSH-туннель к панели {#ssh-туннель-к-панели}

Если HTTP-порт панели доступен на сервере по адресу `127.0.0.1:8080`, откройте туннель
с рабочей машины:

```bash
ssh -N -L 127.0.0.1:8080:127.0.0.1:8080 administrator@panel-server
```

Затем откройте `http://127.0.0.1:8080/ui/`. Туннель защищает доступ браузера к панели;
сама панель подключается к Samba-серверу по отдельному SSH-соединению.

Production-конфигурация с HTTPS и дополнительным Samba/SSH-узлом описана в
[руководстве Docker Compose](production.md).

Перед первым подключением настройте [доверенные SSH-ключи сервера](ssh-host-keys.md).

## Обслуживание workflow

Пять workflow разделены по назначению:

| Файл | Назначение |
| --- | --- |
| `ci.yml` | Сборка, Python guards, frontend, Browser Smoke Tests, Production Compose Smoke и выпуск по тегу. |
| `codeql.yml` | Независимый анализ Java, JavaScript/TypeScript и Python. |
| `docs.yml` | Публикация документации из `master` в GitHub Pages. |
| `dockerhub-description.yml` | Синхронизация и проверка описания Docker Hub. |

В `ci.yml` запись разрешена только release-job. Он получает `contents: write` для
GitHub Release и `actions: read` для проверки CodeQL. Docker Hub использует отдельные secrets.

## Порядок стабильного выпуска {#stable-release}

Поддерживаются канонические теги `vX.Y.Z` без leading zero, prerelease и build metadata.
Настоящий тег и публикация требуют отдельного решения оператора.

1. Tag run выполняет полный план проверок, включая backend, frontend, браузер и Production Compose.
   Quality Gate и сборка JAR должны завершиться успешно.
2. `scripts/ci/release/release.py publish` проверяет совпадение checkout, исходного тега и
   `GITHUB_SHA`, затем успешный CodeQL из master/main для этого SHA по всем трём языкам.
3. Проверяется отсутствие GitHub Release, включая draft, и versioned Docker image.
   Любая существующая версия останавливает процесс. Ошибки registry не считаются отсутствием образа.
4. Скачанный из build-job JAR проверяется, копируется как `application.jar` и получает `SHA256SUMS`.
   Dockerfile упаковывает этот JAR; локальный smoke проверяет non-root runtime, идентичность JAR,
   ресурсы UI и API. Повторной Maven-сборки в release-job нет.
5. Создаётся draft GitHub Release с автоматически сформированными notes. Публикуется образ
   `andreyvorobevaqa/sambryx:X.Y.Z`, затем скачивается по digest и снова проходит smoke.
6. В draft загружаются `application.jar`, `SHA256SUMS`, `release.json`. Metadata содержит
   source SHA, версию, image repository, manifest digest и SHA-256 JAR.
7. Draft становится публичным. GitHub latest не переводится на более старую stable-версию.
   Наличие публичного release проверяется повторно.

Docker aliases `latest` и `major.minor` больше не обновляются автоматически.
Существующие aliases не удаляются, но могут указывать на прежний выпуск. Для установки используйте
конкретную версию или digest. Исторические releases и их assets остаются без изменений.

## Частичный сбой и ручное восстановление {#release-recovery}

Публикация в GitHub и Docker Hub не является одной транзакцией. При ошибке скрипт останавливается,
а CI пытается сохранить подготовленные JAR, checksum и metadata в Actions artifact
`release-files-RUN_ID-ATTEMPT` на 90 дней. При аварийной потере runner или истечении retention
эти файлы могут быть недоступны. OCI archive не создаётся; автоматического resume и repair нет.

| Состояние | Действие оператора |
| --- | --- |
| Нет draft и version image | Исправить причину; можно повторить исходный tag run. |
| Есть draft, но образ не опубликован | Проверить причину и отсутствие version image. При неоднозначности выбрать новую версию; не удалять draft автоматически. |
| Есть version image, но release не завершён | Не пересобирать и не перезаписывать version tag. Сохранить digest, исходные файлы и журналы; принять отдельное решение о ручном завершении. |
| Release уже публичный | Повтор не требуется и будет отклонён. Проверить опубликованные assets и digest. |

Для ручного завершения сначала подтвердите source SHA тега и labels, digest образа,
совпадение JAR внутри скачанного по digest образа с исходным `application.jar`, его SHA-256 и
успешный smoke. Labels сами по себе не доказывают идентичность. Если исходные файлы отсутствуют
или проверка не сходится, не достраивайте выпуск новой сборкой под той же версией: расследуйте
сбой и используйте новый номер версии. Загрузка недостающих assets и публикация draft — отдельные
ручные действия после проверки; существующие assets нельзя заменять через `--clobber`.

Не удаляйте опубликованные версии и не переносите Git-теги ради повторного запуска.
Не используйте исторические workflows с прежней логикой восстановления для новых выпусков.
Release-jobs сериализованы через `sambryx-release-mutation`; это не защищает от ручных изменений
registry за пределами Actions.

## Проверка механизма без выпуска

```bash
python3 -B -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
bash -n scripts/ci/release/select-jar.sh
jar=$(bash scripts/ci/release/select-jar.sh target)
docker build --build-arg "JAR_FILE=$jar" --tag sambryx:local .
bash scripts/ci/smoke/smoke-docker.sh sambryx:local "$jar"
```

Команды сборки требуют предварительного `./mvnw clean package`.
`select-jar.sh DIRECTORY` требует ровно один JAR и проверяет frontend через `verify-artifacts.py`.

Tests проверяют порядок публикации, блокировку существующих версий, отказы внешних операций,
CodeQL exact source SHA, registry errors, checksum и dependency guards. Внешние команды и HTTP
подменены: это не реальная публикация и не проверка GitHub concurrency scheduler.
Полный CI отдельно проверяет настоящий image и Production Compose; обычный master push
не выпускает release. В артефакт не включаются secrets, полный workspace или application logs.
