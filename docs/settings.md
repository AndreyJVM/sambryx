# Справочник настроек

Установка имеет три уровня: переменные Compose на Docker-хосте, свойства приложения
в контейнере панели и конфигурация Samba на управляемом сервере.
Изменение одного уровня не настраивает остальные автоматически.

## Переменные production Compose

Шаблон — `deploy/production/.env.example`. Рабочий файл — `deploy/production/.env`.
Команды запуска передают его через `--env-file`.

| Переменная | Назначение и значение по умолчанию |
| --- | --- |
| `COMPOSE_PROJECT_NAME` | Имя проекта: `sambryx-production`. При обновлении сохраняйте прежнее имя, чтобы использовать существующие сети и тома. |
| `SAMBRYX_IMAGE` | Обязательный опубликованный тег или digest. Placeholder из шаблона нужно заменить. |
| `PROXY_UID`, `PROXY_GID` | UID/GID Nginx: в шаблоне `1000:1000`. Должны быть ненулевыми и соответствовать доступу к TLS-ключу. |
| `TLS_CERT_FILE`, `TLS_KEY_FILE` | Файлы полной цепочки TLS и приватного ключа на Docker-хосте. |
| `HTTPS_BIND_IP`, `HTTPS_PORT` | Публикация HTTPS: `127.0.0.1:8443`. Для LAN укажите конкретный адрес Docker-хоста. |
| `SSH_KNOWN_HOSTS_FILE` | Путь на хосте к проверенным публичным SSH-ключам. Файл должен существовать и читаться UID 10001. |
| `SSH_AUTHORIZED_KEYS_FILE` | Только встроенный Samba-узел: файл публичного ключа SSH-пользователя. Это другой ключ и другая задача, чем `known_hosts`. |
| `SAMBA_BIND_IP`, `SAMBA_PORT` | Только встроенный Samba-узел: публикация SMB, по умолчанию `127.0.0.1:445`. |

Относительные файловые пути в этих Compose-файлах разрешаются от корня репозитория,
а не от расположения `.env`. Полный порядок подготовки — в [Production Compose](production.md#setup).

**`.env` не передаётся целиком приложению.** Compose использует переменные для подстановки
в модель; в контейнер попадают только явно объявленные `environment`/`env_file`.
Простое добавление свойства Samba в `.env` не изменит backend.

## Свойства приложения

Значения ниже определены в `SambaProperties`; это свойства Spring Boot.
При необходимости передайте их в контейнер панели явно и пересоздайте `web`.

| Свойство | По умолчанию | Область действия |
| --- | --- | --- |
| `samba.paths.config` | `/etc/samba/smb.conf` | Путь конфигурации на Samba-сервере |
| `samba.paths.backups` | `/etc/samba/backups` | Каталог копий на Samba-сервере |
| `samba.paths.log` | `/var/log/samba/log.smbd` | Журнал на Samba-сервере |
| `samba.ssh.known-hosts` | `/etc/sambryx/known_hosts` | Файл доверия внутри контейнера панели |
| `samba.ssh.connect-timeout` | `7000ms` | Таймаут установления SSH-соединения |
| `samba.ssh.command-timeout` | `30s` | Таймаут команды; не общий предел HTTP-запроса |
| `samba.ssh.server-alive-interval` | `30s` | Интервал проверки живости SSH-соединения |
| `samba.ssh.server-alive-count-max` | `3` | Число пропущенных проверок живости |
| `samba.security.allowed-roots` | `/mnt`, `/media`, `/srv`, `/data`, `/home` | Допустимые корни файловых операций на сервере |
| `samba.security.max-login-attempts` | `5` | Порог неудачных попыток входа |
| `samba.security.login-block-duration` | `3m` | Продолжительность блокировки попыток входа |
| `samba.group.prefix` | `smb_` | Префикс управляемых Linux-групп |

Например, для нестандартного пути конфигурации создайте `docker-compose.settings.yml`
в корне каталога установки:

```yaml
services:
  web:
    environment:
      SAMBA_PATHS_CONFIG: /srv/samba-config/smb.conf
```

Передавайте override последним `-f` во всех командах управления этим стеком:

```bash
docker compose --env-file deploy/production/.env -f docker-compose.production.yml -f docker-compose.settings.yml config --quiet
docker compose --env-file deploy/production/.env -f docker-compose.production.yml -f docker-compose.settings.yml up -d --no-deps --force-recreate --wait web
```

Для встроенного Samba-узла добавьте `-f docker-compose.samba.yml` перед settings-файлом.
Команда пересоздания завершит текущие сессии панели.
Предварительно настройте доступ SSH-пользователя к файлу и связанные sudo-разрешения.
Это свойство меняет путь операций панели; оно не перенастраивает аргументы запуска
Samba и не переносит файл. Совпадение с конфигурацией работающей службы проверяется отдельно.

В production-файле также явно включены secure/HttpOnly cookie, SameSite Lax
и обработка forwarded-заголовков. Они рассчитаны на поставляемый Nginx с HTTPS;
не публикуйте порт `web:8080` напрямую.

## Что не настраивается этим файлом

- SSH-хост, порт, пользователь и реквизиты вводятся в форме подключения, а не в production `.env`.
- 15 минут бездействия административной сессии и период завершения операций сейчас
  заданы в коде. `server.servlet.session.timeout` не заменяет эту проверку.
- Префикс группы не переименовывает существующие группы. После его изменения старые
  группы могут исчезнуть из списка управления, а ссылки в ресурсах останутся прежними.
- Смена пути backup не переносит старые копии. Новый каталог и права нужно подготовить отдельно.
- Смена host-пути `known_hosts` требует пересоздания панели с новым файловым подключением;
  не меняйте доверие без [независимой проверки ключа](ssh-host-keys.md).

Срок действия и завершение сессии — в [правилах сессий](sessions.md).
Данные, которые необходимо сохранять при обновлении, — в [Production Compose](production.md).
