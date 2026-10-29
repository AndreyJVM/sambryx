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
| `docker-aliases.yml` | Ручное восстановление aliases уже выпущенных образов. |

В `ci.yml` права по умолчанию ограничены `contents: read`; запись разрешена только
заданию Publish Verified Stable Release. Оно также получает `actions: read` для проверки
CodeQL и чтения recovery artifacts. Repair получает только `contents: read` и `actions: read`;
registry credentials используются существующим Docker login. CodeQL сохраняет
`security-events: write`, а ruleset и обязательные проверки не ослабляются.

## Порядок стабильного выпуска

Поддерживаются только канонические теги `vX.Y.Z` без leading zero, prerelease и build metadata.
Создание настоящего тега — отдельное решение оператора. Проверка механизма не требует выпуска.

1. Tag run выбирает полный набор: frontend, backend, JAR, browser, стандартный image и
   настоящий Production Compose Smoke. Quality Gate должен завершиться успешно;
   skipped, failure и cancellation блокируют публикацию.
2. Координатор `scripts/ci/release/release.py` требует успешный push-run `codeql.yml` из master/main
   для **точного source SHA**, с успешными тремя Analyze jobs. Между workflow нет `needs`:
   проверка выполняется через Actions API. Если run отсутствует или ещё не успешен,
   сначала дождитесь/повторите CodeQL для выбранного commit, затем tag run.
3. Проверенный JAR упаковывается в стандартный образ. Smoke подтверждает non-root runtime,
   checksum `/app/app.jar`, frontend assets и API. Skopeo сохраняет точный OCI-архив.
4. Draft release сохраняет recovery record в body, затем assets `image.oci.tar`,
   `application.jar`, `release.json`. Пока запись не сохранена полностью, registry не меняется.
5. Публикуется только versioned Docker tag без `v`, затем его manifest digest проверяется, образ скачивается по digest и проходит smoke с
   каноническим JAR.
   Уже существующий иной digest вызывает отказ. Совпадающий digest повторно не записывается.
6. После проверки version image draft становится публичным GitHub Release. JAR и metadata
   уже загружены; assets не заменяются и не дублируются. GitHub latest выбирается численно,
   поэтому публикация старой версии не делает её последним release.
7. Только теперь обновляются Docker aliases `latest` и `major.minor`, с проверкой каждого
   конечного digest и повторной проверкой version tag.

Draft — подготовительная запись, а не публичный выпуск. OCI archive остаётся release asset
для восстановления точных байтов; он не содержит credentials и не является новой разновидностью
поставки. Runtime остаётся одним стандартным Linux/amd64 Alpine JRE21 образом.
Автоматические release notes сохраняются после recovery record в release body;
описание изменений можно редактировать, сохраняя начальную JSON-секцию record без изменений.

## Источник истины и повтор {#release-recovery}

`release.json` связывает schema version, source SHA, stable version, image repository,
manifest digest, SHA-256 JAR и всего OCI-архива, а также исходные Actions run ID/attempt.
Та же JSON-запись хранится в draft/release body. Исходный source tag должен по-прежнему
указывать на этот SHA. Совпадение revision/version labels не считается доказательством
идентичности; заново собранный образ не обязан иметь тот же digest из-за base image и времени.

Повтор загружает канонические assets, проверяет их checksum и archive manifest digest,
загружает архив в локальный Docker и снова запускает smoke с каноническим JAR. Он не сравнивает
их с новым JAR из повторной сборки. `skopeo copy --preserve-digests` отказывает, если transport
не может сохранить manifest digest; registry digest рассчитывается из точных байтов ответа.
Доступность registry проверяется отдельно: только структурированный `404 MANIFEST_UNKNOWN`
означает отсутствие version/alias. 401, 429, timeout, token endpoint error и прочие ошибки
останавливают процесс без предположения, что tag отсутствует.

| Состояние после сбоя | Продолжение |
| --- | --- |
| Нет draft и version image | Повтор может подготовить новый image после полного gate. |
| Draft и часть assets | Повтор восстанавливает недостающие точные файлы из исходного Actions artifact и дополняет draft без замены существующих assets. |
| Полный draft, version image отсутствует | Повтор публикует сохранённый OCI-архив и проверяет digest. |
| Version image есть, draft ещё не опубликован | Повтор проверяет совпадение digest и публикует GitHub Release. |
| GitHub Release публичный, один/оба aliases не обновлены | Повтор проверяет record/version и завершает только необходимые aliases. Можно использовать ручной repair с master. |
| Конфликт source, checksum, digest, asset name/state или неизвестная identity alias | Автоматический отказ. Сохраните summary и исследуйте конфликт; не удаляйте/заменяйте данные автоматически. |

После release step, включая его обычный отказ, CI сохраняет только три канонических файла
в `release-recovery-RUN_ID-ATTEMPT` на 90 дней. Если asset upload прервался, повтор использует
именно artifact исходного run/attempt из record. При потере runner до сохранения artifact,
его истечении/удалении или незавершённом GitHub asset со state, отличным от `uploaded`,
автоматическое восстановление может быть невозможно. Нужны исходные байты и отдельное
решение владельца по конфликту; пересборка и перезапись версии не являются восстановлением.
Полностью загруженные release assets доступны независимо от retention Actions artifact.

На странице исходного Actions run используйте **Re-run failed jobs** после устранения причины.
**Re-run all jobs** также сохраняет канонический опубликованный/draft результат: новый JAR
не заменяет recovery assets. Summary содержит source SHA, checksums, image digest,
наблюдаемые version/alias digests и последний достигнутый этап. При жёсткой отмене/потере runner
summary и artifact могут не успеть сохраниться; draft body остаётся удалённой записью состояния.
Никогда не переустанавливайте существующий tag и не применяйте upload с заменой assets.

## Aliases и конкурентность

Release и ручной Repair Docker Release Aliases используют общую job concurrency group
`sambryx-release-mutation`, `cancel-in-progress: false`. Блокировка охватывает всю мутирующую
фазу, включая проверку текущих pointers. Обычные PR tests выполняются независимо.
Tag workflow не отменяет уже публикующий run при повторном запуске. GitHub concurrency
не обещает FIFO и может отменить заменённый pending run; такой run нужно повторить.
Это не распределённая блокировка против ручных действий владельца registry вне Actions.
Не запускайте исторические версии workflow до этого механизма для публикации/repair.

Версии сравниваются как числовые тройки: `0.10.0 > 0.9.9`. Для каждого alias отдельно
учитываются публичные stable GitHub releases и identity текущего alias среди versioned registry
tags. Старый retry не откатывает ни `latest`, ни `major.minor`; superseded pointers сохраняются.
Для superseded alias проверяется digest более нового release. Если pointer отсутствует или
не завершён, старый retry требует repair с указанной новой версией. Неизвестный текущий digest
или pointer на ещё не опубликованный новый release требует расследования. Проверяются оба aliases, включая итоговый
version digest; общей транзакции GitHub и registry нет, частичные состояния остаются возможными.

Ручной workflow запускается только с master для уже публичного release с полным record и
совпадающим version digest. Единственное legacy-исключение — заранее проверенный baseline
`v0.12.0` из `scripts/ci/release/data/release-v0.12.0.json`. Он повторяет checksum/archive/JAR smoke-проверки, не пересобирает
образ, не публикует version tag и не создаёт release. Baseline фиксирует source SHA, release/asset ID, имя и checksum единственного release JAR,
а также точный registry manifest digest. При repair проверяются актуальный source tag,
CodeQL исходного commit, неизменность release/asset identity и скачанные байты JAR;
образ скачивается по зафиксированному digest и проходит smoke с этим JAR.
Архив для legacy не создаётся и assets опубликованного release не меняются.
Baseline фиксирует проверенное опубликованное состояние; он не восстанавливает
историческое доказательство воспроизводимости сборки. Любое несовпадение блокирует aliases.
Это не общий путь миграции: остальные legacy versions без полного record запрещены,
а автоматическая публикация не использует legacy-исключение.
Для восстановления выбирайте самую новую stable version нужной линии. Emergency rollback —
отдельная явно согласованная операция; у repair нет параметра обхода monotonicity.

## Проверка механизма без выпуска

```bash
python3 -B -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
bash -n scripts/ci/release/select-jar.sh
jar=$(bash scripts/ci/release/select-jar.sh target)
docker build --build-arg "JAR_FILE=$jar" --tag sambryx:local .
bash scripts/ci/smoke/smoke-docker.sh sambryx:local "$jar"
```

Команды сборки требуют предварительного `./mvnw clean package`. `select-jar.sh DIRECTORY`
требует ровно один JAR и проверяет frontend через `verify-artifacts.py`.
`docker-tags.py check/push` остаётся низкоуровневой утилитой проверки списка tags;
release и repair больше не используют её push-loop для публикации pointers.

Tests запускают координатор с state doubles и подменёнными subprocess CLI/HTTP boundaries:
порядок, retry после upload/version/release/alias failure, отсутствие rebuild и duplicate assets,
конфликты, недоступность registry, checksum/digest, numeric SemVer и dependency guards.
Они не доказывают настоящую публикацию Docker Hub/GitHub и выполнение concurrency scheduler.
Actionlint и проверка job graph дополняют эти тесты; полный CI проверяет реальный стандартный
образ и Production Compose, не публикуя release на PR/master.

Build & Test имеет timeout 30 минут, Browser Smoke Tests — 10, Production Compose Smoke — 20,
Publish Verified Stable Release — 40, repair — 20, CodeQL — 30, документация — 15.
Backend test reports сохраняются на 7 дней, браузерная диагностика — в `browser-smoke-report`.
Полный workspace, credentials, Compose config с подставленными значениями и application logs
в recovery artifact не включаются.
