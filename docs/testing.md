# Тестирование интерфейса

Браузерные smoke-тесты проверяют собранный React-интерфейс Sambryx. Они запускают локальный
локальный Java HTTP-сервер и подменяют API на границе HTTP. Нужен JDK 21; Docker, учётные
записи сервера и настоящие SSH/Samba/AD-соединения для этого набора не нужны.

## Обычная задача

Основной порядок — issue с критериями, узкая локальная проверка, review diff, commit и
прямой fast-forward push в master, затем один применимый облачный CI. PR и отдельный
worktree необязательны. При изменении поведения сначала подтвердите ожидаемый Red
значимым targeted test, затем Green. Для документации и визуальных правок проверяйте
артефакт, без искусственного Red. Проверяйте diff, форматирование и синтаксис затронутых файлов.

Команды ниже предназначены для работы с соответствующим набором; полный backend,
браузеры и Docker/SSH не обязательны локально на каждую правку. CI выбирает область
по области изменений (таблица в README репозитория); для CI/security/shared/unknown
изменений и release полный набор выполняется в облаке. Параллельный CodeQL не задерживает
завершение лёгкой задачи, но остаётся обязательным для точного source SHA перед release.

## Первый запуск

Нужны JDK 21, Node.js 20+ и npm. Node.js используется для сборки приложения,
браузерные сценарии написаны на Java/JUnit с Playwright. Из корня проекта:

```bash
cd frontend
npm ci
npm test
npm run lint
npm run build
cd ..
./mvnw -B -f tools/browser/pom.xml exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.classpathScope=test \
  "-Dexec.args=install chromium"
./mvnw -B -f tools/browser/pom.xml verify
```

На минимальном Linux вместо `install chromium` используйте `install --with-deps chromium`.
Установка системных библиотек может потребовать административные права; в CI она выполняется
на одноразовом runner. Версия Playwright закреплена в `tools/browser/pom.xml`.
После её изменения установите соответствующий Chromium снова.

Из `frontend/` доступны обёртки `npm run test:browser:install` и `npm run test:browser`.
Вторая сначала собирает UI, затем запускает Java-тесты. Сборка приложения и Spring-контекст
в самом тестовом Maven-проекте не запускаются.

Собранный `frontend/dist` обслуживается только на `127.0.0.1` со случайным свободным портом.
Чужой preview-сервер не используется. Нет npm-зависимости от `@playwright/test`.
Для локальной диагностики допустим `PLAYWRIGHT_CHANNEL=chrome` перед командой Maven;
этот выбор нужно указать в отчёте, поскольку системный Chrome отличается от закреплённого Chromium.

## Что проверяется

Набор `tools/browser/src/test/java/sambryx/browser/` запускается в Chromium в четырёх конфигурациях:

| Конфигурация | Размер | Тема |
|---|---|---|
| desktop-light | 1440 × 1000 | Светлая |
| desktop-dark | 1440 × 1000 | Тёмная |
| mobile-light | 320 × 844 | Светлая |
| mobile-dark | 320 × 844 | Тёмная |

Проверки охватывают парольный вход и вход с ключом, bootstrap CSRF-токена, предупреждение
о смене SSH-ключа, навигацию, сохранение темы, поиск ресурсов, пустые результаты, ошибки
загрузки и устаревшие данные, вложенные диалоги, отмену опасных действий и блокировку
кнопок управления при недоступных данных Dashboard.

Сценарии `ConfigurationBackupIT` проверяют выбранный файл и последствия в подтверждении,
Cancel/Escape без mutation, pending без повторной отправки и конфликтующего сохранения,
успех с перечитыванием формы и списка копий, ошибку перечитывания после успешного restore,
отказ проверки, ошибку restart и потерю ответа. Снимки подтверждения, pending и результата
сохраняются в `frontend/test-results/` для каждой темы и размера экрана.

Текущий restore API не различает отказ `testparm` и сбой последующих команд структурированным
кодом: оба возвращают HTTP 500. Поэтому интерфейс сообщает, что восстановление не подтверждено,
скрывает серверные детали и предлагает проверить конфигурацию и службу перед повтором.
Это сообщение не обещает сохранность действующего файла при неизвестной ошибке. Успешный
restore и неудачное последующее перечитывание отображаются отдельно.

API-фикстуры используют только синтетические реквизиты и адреса. Незаданные API-запросы,
неожиданные мутации, внешние обращения и необработанные ошибки JavaScript завершают проверку
ошибкой. Новый endpoint нужно добавить в фикстуру явно; не заменяйте неизвестные ответы
универсальным успешным результатом.

Эти тесты не подтверждают серверную защиту CSRF, корректность проверки SSH-ключа библиотекой,
работу настоящего Samba/AD или совместимость с Astra. Для этого сохраняются backend integration
tests и отдельная Production Compose Smoke с реальными TLS/SSH/SMB.

## Выборочная проверка и диагностика

После сборки можно запустить только одну группу, например backup/restore:

```bash
./mvnw -B -f tools/browser/pom.xml verify '-Dit.test=ConfigurationBackupIT,BackupCreationIT'
```

JUnit-классы `WorkspaceIT`, `AdministratorSessionIT`, `ConfigurationBackupIT`,
`BackupCreationIT` и `ShareAccessIT` содержат обычные параметризованные тесты
с вариантами из `BrowserVariant`. Каждый тест получает отдельный browser context;
общий `BrowserTestRuntime` отвечает только за запуск, диагностику и освобождение ресурсов. Только проверка логотипов: `-Dit.test=BrandingIT`.
Полный набор ограничен таймаутом JVM в пять минут; автоматических retry нет.
Используйте ожидания Playwright по состоянию интерфейса вместо `sleep`.

JUnit-результаты сохраняются в `tools/browser/target/failsafe-reports/`.
Screenshots и failure traces находятся в `tools/browser/target/browser-artifacts/`.
Открыть конкретный trace из корня проекта:

```bash
./mvnw -B -f tools/browser/pom.xml exec:java \
  -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.classpathScope=test \
  "-Dexec.args=show-trace tools/browser/target/browser-artifacts/CASE/trace.zip"
```

Замените `CASE` именем каталога неудачного сценария. Отчёты не входят в Git.
CI-job **Browser Smoke Tests** прикладывает JUnit-результаты, screenshots и traces
в артефакте `browser-smoke-report` на семь дней. Логотипы проверяются отдельно в job
**Branding Assets**; для этого не требуется Node.js или сборка frontend.

Добавляя сценарий, проверяйте наблюдаемый результат и необходимые побочные эффекты.
Предпочитайте доступные роли и подписи элементов. При изменении поведения сначала добавьте
регрессионный тест, который воспроизводит проблему, затем изменяйте реализацию.


## Восстановление конфигурации через Docker + SSH

[Production Compose Smoke](production.md#проверка-конфигурации-и-smoke-тест) выполняет
публичные backup/restore-запросы через HTTPS и настоящий SSH. Успех подтверждается сравнением
байтов `smb.conf`, чтением API и SMB; отказ повреждённой копии — сохранностью действующего
файла и PID `smbd`, а также чтением SMB после ошибки. Этот тест запускается в CI в существующем
job Production Compose Smoke и использует только одноразовый Compose-проект.

`ConfigWorkflowIntegrationTest` отдельно проверяет HTTP error mapping и отсутствие команд
замены/restart при ошибке `testparm`; граница SSH там замокана, security filters отключены.
Проверка настоящих filters, TLS, CSRF и host-key verification сохраняется в Compose smoke.
Эти уровни не подтверждают восстановление пользователей, паролей, SID или всех данных из томов.

## Коллизии имён резервных копий

`BackupCollisionTest` фиксирует время на границе форматирования даты и выполняет команды
реального сервиса над временными файлами. Подменены только SSH executor, `sudo`, `testparm`
и `systemctl`; Bash, `cp`, `mktemp`, `ln` и NUL-safe retention выполняются локально.
Тесты подтверждают сохранность нескольких снимков в одну секунду, выбранной restore-копии,
восстановление имени с суффиксом и остановку без замены/restart при отказе создания safety copy.
Отдельно проверяются повторные совпадения, исчерпание имён, частичная запись, отказ публикации,
каталоги, висячие ссылки и пути с пробелами/кавычками.

Первое имя сохраняет формат `smb.conf.backup_yyyyMMdd_HHmmss`; при совпадении используются
суффиксы `_1`–`_99`. После 100 занятых имён операция завершается ошибкой. Готовое содержимое
публикуется hard link из скрытого временного файла в том же каталоге; существующая запись
не заменяется. Временный файл имеет права `0600` и удаляется при штатном выходе команды.
Нужны Bash, GNU-совместимый `ln -T`, поддержка hard link файловой системой и разрешение
существующей SSH-учётной записи выполнять команду через `sudo bash`; проект не меняет sudoers.
При отсутствии этих возможностей создание завершается ошибкой до restore/update.
Лимит retention остаётся равным 10; старые имена поддерживаются.

Эти проверки не доказывают общую конкурентность, атомарность restore, rollback, устойчивость
к аварийному завершению хоста или защиту от изменения каталога другим привилегированным
процессом. Compose smoke проверяет новую команду через настоящий SSH, но не фиксирует время
и не гарантирует воспроизведение коллизии; детерминированные коллизии покрывает файловый тест.

## Аудит зависимостей frontend

Проверка #28 от 25 октября 2026 года воспроизводится из `frontend/`:

```bash
npm ci
npm audit
npm audit --omit=dev
npm ls vite esbuild tailwindcss postcss-nested postcss-selector-parser react-router-dom react-router
```

Audit зависит от актуальной базы advisory: сохранённый lockfile фиксирует версии, но не будущий
результат аудита. Ненулевой код выхода означает наличие findings; не скрывайте его в CI.
`--omit=dev` помогает отделить runtime-зависимости, но сам по себе не доказывает применимость.

Исходный lockfile на `0bb8456` давал 7 findings: 6 moderate и 1 high. Выбран ограниченный
переход Vite 5.4.21 → 6.4.5 с esbuild 0.25.12. Vite 6 поддерживает существующий Node.js 20.15.0,
а установленный React plugin 4.7.0 поддерживает Vite 6. Требования Node.js, React, Tailwind,
маршрутизация, конфигурация сборки и Docker-поставка сохраняются. Используемые настройки не
затрагивают изменения SSR, Sass, resolve conditions и экспериментальных API из
[руководства миграции Vite 5 → 6](https://v6.vite.dev/guide/migration).

| Пакеты исходного отчёта | Применимость и решение |
|---|---|
| Vite (high) | Dev-server: обход ограничений файловой системы, обработка source maps и launch-editor; часть advisory относится к Windows, часть — и к другим ОС. Обновлён до 6.4.5, выше всех затронутых диапазонов исходного отчёта. В runtime Docker находятся JRE и JAR со статическими ресурсами, Vite туда не включается. |
| esbuild (moderate) | [GHSA-67mh-4wv8-2f99](https://github.com/advisories/GHSA-67mh-4wv8-2f99): доступ стороннего сайта к esbuild dev-server. Sambryx использует esbuild через Vite при сборке; зависимость обновляется вместе с Vite до исправленной версии 0.25.12. |
| postcss-selector-parser, postcss-nested, tailwindcss (3 moderate) | [GHSA-rj75-hqrm-r3gf](https://github.com/advisories/GHSA-rj75-hqrm-r3gf): CPU exhaustion при разборе недоверенных CSS-селекторов. Это одна первичная уязвимость и два зависимых package findings. Парсер 6.1.4 остаётся в build tooling Tailwind 3.4.19/postcss-nested 6.2.0; вход — доверенные исходники репозитория, HTTP-запросы и пользовательские CSS сервером не компилируются. Сборка непроверенных PR всё равно исполняет недоверенный код и должна оставаться изолированной без секретов. |
| react-router, react-router-dom (2 moderate) | Поставляются в JS bundle, остаются 6.30.6. [GHSA-wrjc-x8rr-h8h6](https://github.com/advisories/GHSA-wrjc-x8rr-h8h6) требует передачи контролируемого атакующим пути в Link/useNavigate. В Layout, DashboardPage и ActiveDirectoryPage назначения заданы константами; GlobalSearch строит назначения `/shares`, `/users`, `/groups`, а не берёт их из результатов API. [GHSA-337j-9hxr-rhxg](https://github.com/advisories/GHSA-337j-9hxr-rhxg) касается SSR hydration: здесь BrowserRouter/Routes и createRoot, без SSR и deserializeErrors. |

После обновления получены **5 moderate, 0 high** в полном audit и **2 moderate** в
`--omit=dev`. Это зарегистрированные остаточные findings, а не исправления Router или CSS parser.
Решение ограничено текущими путями данных: не передавайте внешние URL, query-параметры или значения
API в Link/useNavigate и не добавляйте серверную компиляцию пользовательского CSS без повторной
оценки. При появлении SSR, динамических назначений навигации, недоверенных CSS или новых advisory
пересмотрите исключения до публикации изменения. Повторяйте audit при обновлении lockfile.

Исправления этих остаточных advisory доступны в Router >=7.18.0 и parser >=7.1.6.
Переход Router 6 → 7 и Tailwind 3 → 4 либо принудительная замена parser за пределами диапазона
postcss-nested — отдельные изменения совместимости; в #28 они не выполняются. Не используйте
`npm audit fix --force` и не отключайте проверки ради нулевого отчёта.


## Срок действия сессии

[Контракт сессий](sessions.md) проверяют `AdministratorSessionIntegrationTest` с настоящими
security filters и подменённой SSH-границей, а также `ExecutionSessionManagerTest`.
Управляемый Clock позволяет проверить границы 15 минут и пяти минут ожидания без sleep.
Реальный сервис restore продолжает цепочку команд после отзыва доступа; отдельная проверка
подтверждает отказ новому запросу и отсутствие повторного disconnect.

`session.spec.mjs` проверяет предупреждение, явное продление, отказ просроченному продлению,
возвращение из сна, несколько вкладок и отсутствие продления автоматическим переходом.
Для предупреждения сохраняются снимки RU/EN на desktop/mobile в обеих темах.
Фикстуры браузера не доказывают серверный deadline; mock SSH не доказывает завершение
настоящего удалённого процесса. Эти границы проверяются разными уровнями тестирования.


## Проверки механизма выпуска

Для изменений CI/release в облаке применяется полный план, включая настоящий стандартный
Docker image и Production Compose Smoke. Локально достаточно затронутых guards и быстрых checks.
Release coordinator проверяется отдельно без внешней публикации.

Утилиты CI сгруппированы по назначению:

- `scripts/ci/checks/` — выбор проверок и верификация документации, JAR и Docker Hub.
- `scripts/ci/release/` — последовательная публикация новой версии и выбор JAR.
- `tools/smoke/` — отдельные Java/JUnit-проверки Docker image и Production Compose, без Spring-контекста и сборки приложения.
- `scripts/ci/tests/` — все Python-тесты утилит, отдельно от исполняемых скриптов.

Полный набор запускается из корня репозитория без установки Python-пакета проекта:

```bash
python3 -B -m unittest discover -s scripts/ci/tests -p 'test_*.py' -v
```

Для отдельных проверок release:

```bash
python3 -B -m unittest discover -s scripts/ci/tests -p 'test_release*.py' -v
```

Проверяются порядок публикации, отказы внешних операций, запрет перезаписи версий,
registry errors, CodeQL exact source SHA, dependency cancellation/skips и SHA-256 JAR.
Внешние команды и HTTP подменены; это не настоящий выпуск и не проверка GitHub concurrency scheduler.
[Порядок выпуска и ручное восстановление](distribution.md#release-recovery) описывают действия после сбоя.

## Java Docker image smoke

Нужен JDK 21. Быстрые тесты не используют Docker:

```bash
./mvnw -B -f tools/smoke/pom.xml test
```

Реальный image проверяется отдельно, без пересборки приложения:

```bash
./mvnw -B -f tools/smoke/pom.xml -Pdocker-smoke verify \
  -Dsmoke.image=sambryx:local "-Dsmoke.jar=$(realpath target/sambryx-0.0.1-SNAPSHOT.jar)"
```

Укажите уже собранный образ и соответствующий JAR. Проверяются non-root UID, идентичность JAR,
точные байты UI/assets, remote mode и HTTP 401 для анонимного запроса. Контейнер запускается
с read-only filesystem и временным `/tmp`, порт публикуется только на loopback. Проверка
удаляет только свой контейнер при успехе и отказе; принудительное завершение JVM через SIGKILL
не позволяет выполнить cleanup. Обычный backend `test` эти проверки не запускает.

JUnit-тесты вспомогательной логики используют временные JAR, настоящий локальный HTTP-сервер
и подменённую Docker CLI-границу. Профиль `docker-smoke` проверяет настоящий Docker image,
но не устанавливает SSH-соединение и не проверяет SMB: это остаётся задачей Production Compose Smoke.

## Java Production Compose smoke

После подготовки образов из [инструкции развёртывания](production.md):

```bash
./mvnw -B -f tools/smoke/pom.xml -Pproduction-smoke verify \
  -Dsmoke.image=sambryx-ci:production
```

Профили `docker-smoke` и `production-smoke` запускайте отдельно. Второму не нужен `smoke.jar`.
Он использует настоящий nginx, SSH, Samba и SMB-клиент в отдельном Compose-проекте:
проверяет доверие TLS, SSH host identity, вход по ключу, CSRF и secure/HttpOnly cookie,
сохранность UID, host keys, пароля, shares и SMB-файла после пересоздания. Проверяются
успешный backup/restore и отказ testparm для повреждённой копии без изменения активного
конфига, PID Samba и доступности SMB. Данные рабочих серверов не используются.

Compose запускается с `--env-file /dev/null`, уникальным project name и случайными loopback-портами.
Cleanup удаляет только volumes и контейнеры этого проекта. Приватные ключи создаются
во временном каталоге, SMB-реквизиты передаются через stdin во временный файл с правами 600.
Нет Python-кода внутри тестового Samba-контейнера. Application logs и реквизиты в отчёт не включаются.
SIGKILL и авария Docker-хоста могут помешать очистке; оставшийся тестовый проект нужно
проверить отдельно перед удалением.

Быстрые JUnit-тесты проверяют HTTP/CSRF, передачу stdin и изоляцию проекта. Они не
подтверждают настоящий SSH/SMB: это делает только явно запущенный `production-smoke`.
Пять проверок rendered Compose model находятся в `ComposeModelIT` и входят в `production-smoke`.
Отдельно, без запуска контейнеров:

```bash
./mvnw -B -f tools/smoke/pom.xml -Pproduction-smoke verify -Dit.test=ComposeModelIT
```

## Ограниченный отчёт готовности сервера

`ServerReadinessServiceTest` подменяет только `CommandExecutor`: проверяет успешные read-only
вызовы без заявления полной готовности, отказы команд, отсутствие exit status и остановку
после транспортного сбоя. Проверяются отсутствие чувствительного вывода и ограниченный
набор команд. `ServerReadinessIntegrationTest` использует настоящий сервис и security filters
с подменённым `SshSessionManager`: проверяются HTTP 401 и отчёт после успешного SSH-входа
при отказе удалённых проверок.

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -Dfrontend.skip=true \
  -Dtest=ServerReadinessServiceTest,ServerReadinessIntegrationTest test
npm --prefix frontend run build
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -f tools/browser/pom.xml \
  verify -Dit.test=ServerReadinessIT
```

Браузерные JUnit/Playwright-сценарии подменяют HTTP API: проверяют отказ проверки после входа,
следующее действие, недоступный/повреждённый отчёт, очистку старого результата и отсутствие
изменений на сервере. Проверены ожидание и защита от двойного запуска; успешные проверки
чтения сохраняют частичный статус. RU/EN-отчёты сохраняются в browser-artifacts на
desktop/mobile в обеих темах. Mock SSH/API не доказывает работу настоящего
Samba, sudoers или SMB. Рабочие серверы и реальные секреты для этих тестов не используются.
Границы отчёта описаны в [настройке сервера](server-setup.md#проверка-готовности-после-входа).
