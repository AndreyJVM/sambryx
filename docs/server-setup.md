# Настройка целевого сервера Linux

Sambryx обращается с сервером по SSH от имени специального пользователя, которому выданы права `sudo`.

---

## 1. Установка Samba

На целевом Linux сервере (Ubuntu / Debian / Astra Linux):

```bash
sudo apt update
sudo apt install -y samba winbind krb5-user
```
*(Примечание: пакеты `winbind` и `krb5-user` требуются, если вы планируете использовать интеграцию с Active Directory)*

Для очистки резервных копий на SSH-сервере нужны Bash, GNU findutils (`find`, `xargs`)
и GNU coreutils (`mktemp`, `sort`, `tail`, `cut`, `rm`) с поддержкой NUL-разделителей.
Они входят в обычную установку поддерживаемых Debian/Ubuntu-систем; минимальные образы
и Astra Linux следует проверить отдельно. Перечисление и временный список выполняются
от имени SSH-пользователя: ему нужны чтение каталога резервных копий и доступный
временный каталог. Через `sudo` выполняется только удаление отобранных копий.

## 2. Создание пользователя `samba-admin`

Создайте сервисного системного пользователя с паролем:

```shell
sudo useradd -m -s /bin/bash samba-admin
sudo passwd samba-admin
sudo smbpasswd -a samba-admin
```

## 3. Настройка `sudoers`
Добавьте права для `samba-admin` на выполнение требуемых команд без ввода пароля `sudo`:

```shell
sudo visudo -f /etc/sudoers.d/sambryx
```

Вставьте следующую строку (всё в одну линию):

```shell
samba-admin ALL=(ALL) NOPASSWD: /usr/sbin/useradd, /sbin/useradd, /usr/sbin/userdel, /sbin/userdel, /usr/sbin/chpasswd, /usr/bin/chpasswd, /usr/sbin/groupadd, /sbin/groupadd, /usr/sbin/groupdel, /sbin/groupdel, /usr/bin/gpasswd, /usr/bin/smbpasswd, /usr/bin/pdbedit, /bin/systemctl, /usr/bin/systemctl, /bin/cat, /usr/bin/cat, /bin/mv, /usr/bin/mv, /bin/cp, /usr/bin/cp, /bin/rm, /usr/bin/rm, /bin/mkdir, /usr/bin/mkdir, /bin/chmod, /usr/bin/chmod, /bin/chown, /usr/bin/chown, /usr/bin/smbstatus, /usr/bin/du, /usr/bin/df, /usr/bin/find, /usr/bin/tail, /bin/kill, /usr/bin/kill, /usr/bin/net, /usr/bin/tee, /usr/bin/testparm
```
*(Примечание: Список команд расширен для поддержки новых функций: мониторинг логов (`tail`), дискового пространства (`du`, `df`), проверка конфигурации (`testparm`) и ввод в домен AD (`net`, `tee`))*

Защитите файл прав:

```shell
sudo chmod 0440 /etc/sudoers.d/sambryx
```
