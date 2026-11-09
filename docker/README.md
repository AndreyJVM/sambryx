# Docker Sandbox for Sambryx

Эта директория содержит конфигурацию для запуска тестового окружения (Sandbox), чтобы разработчики могли проверять функциональность без установки Samba и SSH на свой рабочий компьютер (например, на Windows/Mac).

## Как это работает?

Мы используем `docker-compose.yml` в корне проекта.
Для `samba-node` загружается образ
`andreyvorobevaqa/samba-node-sandbox` (Ubuntu 22.04).
Docker использует `latest`; образ содержит актуальный setup-скрипт.
Локальная сборка нужна только при изменении самого Samba-образа:
`docker compose build samba-node`. Она требует доступа Docker к репозиториям Ubuntu.

Внутри этого образа запущены:
- **SSH Сервер** (на порту 22 внутри контейнера, и доступен на хосте как `2222`).
- **Samba (smbd, nmbd)** (на портах 139, 445 внутри контейнера, на хосте как `1139`, `1445`).

Также внутри контейнера автоматически:
- Создан SSH-пользователь `admin`; пароль задаётся runtime secret, а не вшивается в образ.
- Права на отдельные sudo-утилиты формируются тем же setup-скриптом, что и для внешнего сервера.
- Тестовые SMB-аккаунты используют случайный пароль; для проверки SMB задайте им пароль явно.
- Настроен базовый конфигурационный файл `/etc/samba/smb.conf` и создана публичная папка `/srv/samba/public`.

## Запуск песочницы

### Способ 1: Интерактивный Bash-скрипт (с чистой автоочисткой)
```bash
./scripts/sandbox/run-sandbox.sh
```
Скрипт:
1. Собирает frontend, JAR и web-образ, загружает готовый Samba-образ; генерирует пароль в `.local/sandbox/admin-password` с правами 0600.
2. Запускает контейнеры (`docker compose up -d`).
3. Ожидает успешный healthcheck Samba-сервера.
4. Позволяет просматривать логи (`logs`) или статус (`status`).
5. При завершении (`stop`, `q`, `Enter` или `Ctrl+C`) полностью останавливает сервисы, удаляет контейнеры, сети, volume и локально собранный образ веб-приложения (`--rmi local`), гарантируя абсолютно чистый запуск в следующий раз.

### Способ 2: Напрямую через Docker Compose
Сначала соберите JAR с актуальным frontend. Dockerfile упаковывает готовый JAR;
Compose не запускает Maven и npm.

```bash
./mvnw clean package
mkdir -p .local/sandbox
(umask 077; openssl rand -hex 32 > .local/sandbox/admin-password)
cp .env.sandbox.example .env
install -m 0644 /dev/null .local/sandbox/known_hosts
docker compose pull samba-node
docker compose up --no-build -d samba-node
docker compose exec -T samba-node sh -c 'for key in /etc/ssh/ssh_host_*_key.pub; do printf "samba-node "; cat "$key"; done' > .local/sandbox/known_hosts
docker compose up --build -d web
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
- **Password**: содержимое `.local/sandbox/admin-password`

### Вход при локальном запуске Web UI (из IDE или `mvn spring-boot:run` на хосте):
Если Spring Boot запущен прямо на вашей машине, порты Docker-контейнера проброшены на локалхост:

- **Host (IP Address)**: `127.0.0.1` или `localhost`
- **SSH Port**: `2222`
- **Username**: `admin`
- **Password**: содержимое `.local/sandbox/admin-password`

Sandbox получает ключи только собственного Docker-узла в `.local/sandbox/known_hosts`. Для удалённых серверов настройте [проверку подлинности SSH](../docs/ssh-host-keys.md).

## Обновление существующего стенда

Новый web-образ не меняет права уже работающего SSH-сервера. Fast rebuild сохраняет
контейнер node и проверяет новые sudo-требования до обновления web. Несовместимость
нужно устранить штатной подготовкой сервера после ревью, а не ручной разовой правкой
внутри контейнера. Для нового disposable sandbox загрузите актуальный Samba-образ и пересоберите web-образ.
При миграции существующего стенда сначала сохраните его данные и идентичность:
legacy Compose сохраняет только shares и каталог Samba, но не Linux-аккаунты/passdb/home.
Clean runner удаляет volumes и не подходит для такой миграции.
См. [контракт сохранения и обновление sudo](../docs/server-setup.md).
