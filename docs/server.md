# Выделенный сервер сборки

Два равноценных пути: **домашний компьютер с белым IP** (первым, он же локальный тест) и **VPS в Docker** (`docker/README.md`).
Оба собираются из одних и тех же jar, что и клиентская сборка `mob-talk-pack`. Все команды — из корня репозитория,
`powershell -ExecutionPolicy Bypass -File …`.

## Скрипты

| Скрипт | Что делает |
| --- | --- |
| `scripts\build-server-pack.ps1` | собирает папку сервера `%LOCALAPPDATA%\MobTalkPack\server` |
| `scripts\run-local-server.ps1` | запускает её в этом окне, ключи из окружения или DPAPI |
| `scripts\new-test-client.ps1` | второй игрок: одноразовая копия сборки с офлайн-ником, сразу на сервер |
| `scripts\export-mrpack.ps1` | клиентский `.mrpack` для друзей (`docs/friends.md`) |
| `scripts\PackBuild.psm1` | общее для `build-pack.ps1` и серверных скриптов |
| `scripts\server-mods.json` | закреплённые версии и SHA-512 админ-модов и Fabric server launcher |

## Сборка папки сервера

```powershell
scripts\build-server-pack.ps1 -FromPack mob-talk-pack     # те же jar, что у клиентов (рекомендуется)
scripts\build-server-pack.ps1                             # или собрать всё из репозиториев, как build-pack.ps1
```

В папке: `fabric-server-mc.1.21.1-loader.0.19.5-launcher.1.1.2.jar`, `mods\` (наши моды и сторонние jar сборки, кроме
чисто клиентских, плюс Lithium, FerriteCore, spark, Chunky; `-WithLuckPerms` добавит LuckPerms), `config\`
(конфиги сборки копируются один раз, `mobtalk-server.json` пустой — все поля по умолчанию), `server.properties`
(`view-distance=8`, `simulation-distance=6`, `max-players=8`), `eula.txt` и `server-manifest.txt` — коммиты, имена и SHA-512
всех jar. Повторная сборка меняет только jar и манифест: мир, allowlist и правки конфигов остаются. Пока сервер запущен,
скрипт отказывается работать. Mob Talk, пока он клиентский (`environment: client`), в сервер не попадает сам;
`-NoMobTalk` пропускает его явно.

## Локальный тест (один компьютер)

```powershell
scripts\run-local-server.ps1                      # online-mode=false, порт 25565, -Xmx6G
scripts\run-local-server.ps1 -World "stream 1 (сборка)"   # копия мира из инстанса сборки как мир сервера
scripts\run-local-server.ps1 -Fresh               # новый мир (старый уходит в world-backups\)
```

Клиент 1 — инстанс «Наша сборка» в Prism, «Сетевая игра» → `127.0.0.1`. Клиент 2:

```powershell
scripts\new-test-client.ps1 -Nick Test2
```

Копия занимает слот из шести игр (`Enter-GameSlot`) и удаляется, когда её игру закроют (`-Keep` оставит).
Ник — латиница, цифры и `_`, 3–16 символов: сервер отвергает другие.

## Ключи

Сервер берёт `OPENROUTER_API_KEY`, `VENICE_API_KEY`, `ELEVENLABS_API_KEY`, `MOBTALK_ADMIN_TOKEN` из окружения. Если
переменной нет, `run-local-server.ps1` расшифровывает DPAPI-файл, который сохранило меню мода (`MobTalk\…-key.dpapi`,
затем `DivineBlock\…-key.dpapi`), и передаёт значение только процессу сервера — на диск и в лог ничего не пишется.
Сохранить ключ или пароль админки для этого пользователя Windows:

```powershell
scripts\run-local-server.ps1 -SetSecret MOBTALK_ADMIN_TOKEN    # спросит значение скрытым вводом
scripts\run-local-server.ps1 -CheckOnly                         # покажет, откуда взялся каждый ключ, без запуска
```

## Игра с друзьями с домашнего компьютера

1. Проброс порта TCP 25565 на роутере на этот компьютер, разрешение в брандмауэре Windows:
   `New-NetFirewallRule -DisplayName "Minecraft server" -Direction Inbound -Protocol TCP -LocalPort 25565 -Action Allow`
   (от администратора).
2. `scripts\run-local-server.ps1 -Public` — `online-mode=true`, allowlist включён.
3. В консоли сервера: `whitelist add <ник>` для каждого друга, `op <свой ник>`.
4. Друзьям — файл из `scripts\export-mrpack.ps1` и внешний IP (`docs/friends.md`).

Автозапуск при входе в Windows (ключи DPAPI доступны только под своим пользователем, поэтому задача от него же):

```powershell
$a = New-ScheduledTaskAction -Execute powershell.exe -Argument "-ExecutionPolicy Bypass -File `"$PWD\scripts\run-local-server.ps1`" -Public -NewWindow"
Register-ScheduledTask -TaskName 'Mob Talk server' -Action $a -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME)
```

Нагрузка: если на той же машине стрим, игра и Whisper, смотрите `/spark tps` и `/spark profiler` во время игры.

## Клиентская раздача

```powershell
scripts\export-mrpack.ps1        # -> %LOCALAPPDATA%\MobTalkPack\dist\mob-talk-pack-<версия>+<коммит>.mrpack
```

Сторонние jar, которые Modrinth знает по SHA-1 (CTOV, Lithostitched, Fabric API, видео-моды, CreativeCore), идут ссылками
с SHA-1 и SHA-512; наши моды и Viewer Parrots — внутри файла в `overrides/mods`. CTOV и Lithostitched только ссылками:
если Modrinth их не узнал, экспорт падает.

## Тесты

```powershell
Invoke-Pester scripts\tests
```

Проверяют манифест сервера против `pack-manifest.txt`, закреплённые админ-моды, `server.properties` локальный/публичный,
`-NoMobTalk`, пропуск клиентских модов, сохранение мира и правок, и `modrinth.index.json` клиентского пака. Сети не требуют.
