# Docker Sandbox for Sambryx

Эта директория содержит конфигурацию для запуска тестового окружения (Sandbox), чтобы разработчики могли проверять функциональность без установки Samba и SSH на свой рабочий компьютер (например, на Windows/Mac).

## Как это работает?

Мы используем `docker-compose.yml` в корне проекта.
Для сервиса `samba-node` используется готовый образ с Docker Hub: `andreyvorobevaqa/samba-node-sandbox:latest` (на базе `ubuntu:22.04`).

Внутри этого образа запущены:
- **SSH Сервер** (на порту 22 внутри контейнера, и доступен на хосте как `2222`).
- **Samba (smbd, nmbd)** (на портах 139, 445 внутри контейнера, на хосте как `1139`, `1445`).

Также внутри контейнера автоматически:
- Создан системный Linux-пользователь `admin` с паролем `admin` и правами Sudo через sudoers.
- Создан Samba-пользователь `admin` с паролем `admin`.
- Настроен базовый конфигурационный файл `/etc/samba/smb.conf` и создана публичная папка `/srv/samba/public`.

## Запуск песочницы

### Способ 1: Интерактивный Bash-скрипт (с чистой автоочисткой)
```bash
./scripts/sandbox/run-sandbox.sh
```
Скрипт:
1. Собирает актуальные frontend и JAR, проверяет ресурсы UI и упаковывает Docker-образ.
2. Запускает контейнеры (`docker compose up -d`).
3. Ожидает успешный healthcheck Samba-сервера.
4. Позволяет просматривать логи (`logs`) или статус (`status`).
5. При завершении (`stop`, `q`, `Enter` или `Ctrl+C`) полностью останавливает сервисы, удаляет контейнеры, сети, volume и локально собранный образ веб-приложения (`--rmi local`), гарантируя абсолютно чистый запуск в следующий раз.

### Способ 2: Напрямую через Docker Compose
Сначала соберите JAR с актуальным frontend. Dockerfile упаковывает готовый JAR;
Compose не запускает Maven и npm.

```bash
./mvnw clean package
docker compose up --build -d
```

Для сборки нужны JDK 21 и Python 3 (проверка артефакта в sandbox-скрипте).
Maven устанавливает локальные Node.js/npm для frontend.

---

## Авторизация в Sambryx

Откройте интерфейс в браузере: **`http://localhost:8080/ui`**

### Вход при запуске Web UI в Docker Compose:
Поскольку бэкенд Web UI и Samba находятся в одной docker-сети `samba-network`, обращаться к Samba нужно по сетевому имени контейнера:

- **Host (IP Address)**: `samba-node`
- **SSH Port**: `22`
- **Username**: `admin`
- **Password**: `admin`

### Вход при локальном запуске Web UI (из IDE или `mvn spring-boot:run` на хосте):
Если Spring Boot запущен прямо на вашей машине, порты Docker-контейнера проброшены на локалхост:

- **Host (IP Address)**: `127.0.0.1` или `localhost`
- **SSH Port**: `2222`
- **Username**: `admin`
- **Password**: `admin`

Sandbox получает ключи только собственного Docker-узла в `.local/sandbox/known_hosts`. Для удалённых серверов настройте [проверку подлинности SSH](../docs/ssh-host-keys.md).
