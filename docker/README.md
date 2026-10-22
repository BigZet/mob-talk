# Сервер Mob Talk в Docker (VPS на Ubuntu)

Образ `itzg/minecraft-server` (закреплён тег `2026.10.0-java21`), Fabric Loader 0.19.5, Minecraft 1.21.1. Моды и конфиги
собирает `scripts\build-server-pack.ps1 -Docker` на Windows в `docker\data`, на VPS эта папка становится `./data`.
Ключи и пароль админки — только в `docker/.env` на сервере.

## Первый запуск

На Windows:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-server-pack.ps1 -FromPack mob-talk-pack -Docker
scp -r docker user@vps:/opt/mobtalk/
```

На VPS (Ubuntu 24.04, 8 ГБ памяти и больше):

```sh
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER        # перелогиниться
cd /opt/mobtalk/docker
cp .env.example .env && chmod 600 .env && nano .env   # ключи, MOBTALK_ADMIN_TOKEN, WHITELIST, OPS
docker compose up -d
docker compose logs -f mc            # ждать "Done (...)! For help, type \"help\""
```

Порт 25565 должен быть открыт в файрволе (`sudo ufw allow 25565/tcp`). Порт админки 8787 наружу не открывать.

## Перенос мира

Остановить сервер (`docker compose stop`), положить папку мира в `data/world` (например, копию «stream 1 (сборка)»
из `saves` инстанса сборки, без `session.lock`), запустить снова.

## Админка

Слушает только на loopback хоста. С любого своего устройства — через SSH-туннель:

```sh
ssh -L 8787:127.0.0.1:8787 user@vps
```

и открыть `https://127.0.0.1:8787` в браузере.

## Консоль, логи, обновление

- Логи: `docker compose logs -f mc` или `data/logs/latest.log`.
- Команда в консоль сервера: `docker exec mobtalk-mc mc-send-to-console "whitelist add Nick"`; интерактивно —
  `docker attach mobtalk-mc` (выход Ctrl+P, Ctrl+Q, не Ctrl+C).
- Обновить сборку: на Windows снова `build-server-pack.ps1 -FromPack mob-talk-pack -Docker`, затем
  `docker compose stop`, скопировать только `docker/data/mods` поверх `data/mods` на VPS (`rsync -a --delete`),
  `docker compose up -d`. Мир, allowlist и правки конфигов остаются.
- Остановить: `docker compose stop` (мир сохраняется).

## Резервные копии

`backup.sh` выключает автосохранение, сбрасывает мир на диск, пакует `data/world` в `backups/world-<время>.tar.gz`,
включает сохранение обратно и хранит последние 14 копий. Раз в сутки по cron:

```sh
chmod +x backup.sh
crontab -e
30 5 * * * /opt/mobtalk/docker/backup.sh >> /opt/mobtalk/docker/backups/backup.log 2>&1
```

Восстановить: `docker compose stop`, `rm -rf data/world`, `tar -xzf backups/world-….tar.gz -C data`, `docker compose up -d`.
