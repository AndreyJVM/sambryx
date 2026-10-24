# Быстрый старт

## Запуск через Docker

Самый быстрый способ развернуть **Sambryx** — использовать готовый образ с Docker Hub.
Сначала подготовьте `.local/ssh/known_hosts` с [независимо проверенными ключами сервера](ssh-host-keys.md).

```bash
docker run -d \
  --name sambryx \
  --mount type=bind,src="$PWD/.local/ssh/known_hosts",dst=/etc/sambryx/known_hosts,readonly \
  -p 8080:8080 \
  --restart unless-stopped \
  andreyvorobevaqa/sambryx:latest
```

После запуска откройте браузер по адресу: http://localhost:8080/ui.

### Запуск из исходного кода
Требования:

- Java 21+
- Maven 3.8+ (или встроенный ./mvnw)
- Node.js 20+ (для сборки фронтенда, если собираете через Maven)

```shell
# Клонирование репозитория
git clone https://github.com/AndreyJVM/sambryx.git
cd sambryx

# Сборка и запуск
./mvnw spring-boot:run
```

После запуска откройте браузер по адресу: http://localhost:8080/ui.

Перед первым подключением настройте [доверенные SSH-ключи сервера](ssh-host-keys.md).
