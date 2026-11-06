# Разработка

## Требования

- JDK 21.
- Git и доступ к Maven/npm-репозиториям.
- Node.js 20+ и npm для отдельных frontend-команд. Основная Maven-сборка устанавливает
  локальные Node 20.15.0 и npm 10.7.0.
- Docker Engine и Compose для Sandbox и реальных smoke-проверок.
- Python 3 для существующих проверок CI и артефактов.

Установке готового Docker-образа эти инструменты сборки не нужны.

## Сборка и запуск

```bash
git clone https://github.com/AndreyJVM/sambryx.git
cd sambryx
./mvnw clean package
```

Результат — `target/sambryx-0.0.1-SNAPSHOT.jar` со встроенным frontend.
Версия имени JAR — Maven-версия проекта, а не номер Docker-релиза.
Для изолированного Samba/SSH-стенда:

```bash
bash scripts/sandbox/run-sandbox.sh
```

Скрипт собирает приложение и создаёт временный стенд. Данные стенда удаляются при
штатном завершении; не используйте его для рабочих файлов. Детали — в
[инструкции Sandbox](https://github.com/AndreyJVM/sambryx/blob/master/docker/README.md).

Для разработки UI запустите backend из IDE (`mari.samba.SambryxApplication`), затем:

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

UI: `http://localhost:5173/ui/`, API проксируется на `http://localhost:8080`.
Для SSH-входа backend нужен настроенный доверенный `known_hosts`.
Не публикуйте dev-сервер в рабочую сеть.

## Минимальные проверки изменения

| Область | Команды |
| --- | --- |
| Backend | `./mvnw -B test -Dfrontend.skip=true -Dtest=ChangedTest` — заменить `ChangedTest` реальным классом |
| Frontend | `npm --prefix frontend test`, `npm --prefix frontend run lint`, `npm --prefix frontend run build` |
| Браузер | Отдельный JUnit-класс в `tools/browser/`; команды и установка Chromium — в [тестировании](testing.md) |
| Smoke | Профили `tools/smoke/`; контейнеры запускаются только при явном выборе профиля |
| Документация | `python3 -B scripts/ci/checks/documents.py`, `mkdocs build --strict` с MkDocs Material |
| Любое изменение | `git diff --check` |

Spotless проверяет Java при обычном Maven test. Для новой логики добавляйте тест
наблюдаемого поведения, включая значимый отказ; внешнюю SSH/сетевую границу можно подменять.
Подмена не подтверждает работу настоящего сервера. Не сохраняйте секреты и реальные
пользовательские данные в fixtures, логах и screenshots.

## Что включить в изменение

Опишите проблему, новое поведение, выполненные проверки и известные ограничения.
При изменении настройки или пользовательского сценария обновите соответствующую инструкцию.
Не объявляйте поддержку AD или ОС на основании mock-тестов.

Карта кода — в [архитектуре](architecture.md), устройство поставки и релиза —
в [сборке и поставках](distribution.md), анализ безопасности — в [CodeQL](static-analysis.md).
