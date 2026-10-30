# Статический анализ

Workflow `.github/workflows/codeql.yml` запускает GitHub CodeQL при push в `master`/`main`,
а также вручную через Actions. PR-триггер отключён, чтобы не дублировать push-run.
Результаты появляются в разделе
**Security → Code scanning** репозитория.

## Область анализа

| Задание | Код | Подготовка |
| --- | --- | --- |
| `Analyze (java-kotlin)` | Java/Spring Boot backend | Компиляция Maven с JDK 21 без frontend и тестов. |
| `Analyze (javascript-typescript)` | TypeScript/React frontend | Анализ исходников без установки npm-зависимостей и сборки. |
| `Analyze (python)` | Python-помощник и его тесты | Анализ исходников без запуска помощника. |

Конфигурация `.github/codeql/config.yml` включает набор `security-and-quality`.
Сгенерированные файлы, frontend-зависимости и встроенный Node исключены из поиска исходников
для интерпретируемых языков. Java извлекается из явно заданной компиляции Maven.

Анализ выполняется отдельно от сборки релиза. Он не заменяет unit- и интеграционные тесты,
ESLint или проверку развёртывания на целевой ОС. Токену задания разрешены чтение исходников
и запись результатов code scanning; секреты Docker Hub ему не передаются.

## Post-push анализ и выпуск

CodeQL выполняется параллельно основному CI. Его завершение не является предварительным
условием прямого fast-forward push и не задерживает завершение обычной docs-задачи.
Успешный workflow подтверждает выполнение анализа и загрузку результатов, но сам по себе
не означает отсутствия findings. Результаты нужно разбирать в Security → Code scanning.

В master ruleset нет обязательного PR, предварительных status checks или code-scanning
checks. Сохранены запреты non-fast-forward и удаления master; bypass actors отсутствуют.
Это сознательный post-push контракт: ошибочный commit может попасть в master, после чего
исправляется следующим проверенным commit. Не включайте старые предварительные блокировки
как часть обычного завершения задачи.

Release coordinator независимо требует успешный push-run `codeql.yml` на master/main для
точного source SHA и успех всех трёх Analyze. Отсутствующий, pending, failed, cancelled
или относящийся к другому SHA run не разрешает публикацию. Полный Quality Gate и
[остальные release guards](distribution.md#stable-release) сохраняются.

Если включён CodeQL Default setup, используйте Advanced setup этого workflow без
дублирующего анализа. Настройки ruleset хранятся в GitHub отдельно от YAML.

## Разбор результатов

Для каждой находки проверяйте путь данных и воспроизводимость. Для исправленной ошибки
добавляйте регрессионную проверку, если она подтверждает значимое поведение. Ложное
срабатывание отклоняйте с конкретным обоснованием; не отключайте весь набор запросов
ради одного предупреждения.

Документация:

- [Настройка CodeQL Advanced setup](https://docs.github.com/en/code-security/code-scanning/creating-an-advanced-setup-for-code-scanning/customizing-your-advanced-setup-for-code-scanning).
- [Защита слияния по результатам code scanning](https://docs.github.com/en/code-security/code-scanning/managing-your-code-scanning-configuration/set-code-scanning-merge-protection).
