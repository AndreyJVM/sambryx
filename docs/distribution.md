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
python3 scripts/ci/verify-artifacts.py "$JAR_FILE"
docker build --build-arg "JAR_FILE=$JAR_FILE" --tag sambryx:local .
bash scripts/ci/smoke-docker.sh sambryx:local "$JAR_FILE"
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
| `docker-aliases.yml` | Ручное восстановление aliases уже выпущенных образов. |

В `ci.yml` права по умолчанию ограничены `contents: read`; запись разрешена только
заданию GitHub Release. В workflow документации запись разрешена заданию публикации.
CodeQL отдельно получает `security-events: write`. Имена проверок и зависимости выпуска
сохранены: GitHub Release и Docker publication независимо ожидают Build & Test,
Browser Smoke Tests и Production Compose Smoke. Сбой одной публикации всё ещё может
оставить частичный выпуск; атомарность выпуска требует отдельного изменения.

Локальные проверки и сборка обычного образа:

```bash
python3 -B -m unittest discover -s scripts/ci -p 'test_*.py' -v
bash -n scripts/ci/select-jar.sh
jar=$(bash scripts/ci/select-jar.sh target)
docker build --build-arg "JAR_FILE=$jar" --tag sambryx:local .
bash scripts/ci/smoke-docker.sh sambryx:local "$jar"
```

`select-jar.sh DIRECTORY` требует ровно один JAR, проверяет frontend через
`verify-artifacts.py` и выводит только путь в stdout; диагностика идёт в stderr.
Команды сборки требуют предварительного `./mvnw clean package`.
`docker-tags.py check` читает многострочную `STANDARD_TAGS` и отклоняет
пустые или некорректные ссылки. Режим `push` повторяет всю
проверку до первого вызова Docker и останавливается при первой ошибке push.
Локальные тесты этого режима подменяют Docker, не публикуя образы.
Alias repair проверяет ровно один release JAR, digest стандартного версионного образа
и его соответствие JAR через smoke-проверку до восстановления `latest`.
После восстановления проверяются `latest` и неизменность версионного тега;
наличие Astra-образа не требуется. Тесты shell-блоков этого workflow подменяют `gh`, Docker
и запуск smoke, проверяя порядок вызовов и остановку при ошибках; настоящий образ ими не проверяется.

Build & Test имеет timeout 30 минут, Browser Smoke Tests — 10, Production Compose Smoke — 20,
GitHub Release — 10, Docker publication — 40, CodeQL — 30, документация — 15.
Существующие таймауты Docker Hub sync (5) и alias repair (10) сохранены.
При ошибке Build & Test артефакт `backend-test-reports` сохраняет XML/TXT Surefire на 7 дней,
если отчёты уже созданы. Отменённые задания его не загружают. Браузерная диагностика
остаётся в `browser-smoke-report`. Эти артефакты доступны на странице запуска Actions.
Полный workspace, Compose config с подставленными значениями и application logs
в новые артефакты не включаются.
