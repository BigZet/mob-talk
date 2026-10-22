# Параллельные самотесты в игре

С 21 октября 2026 каждый прогон `scripts\run-selftest.ps1` идёт в своей одноразовой копии инстанса Prism,
поэтому несколько веток и тредов могут гонять самотесты одновременно. Основной инстанс `1.21.1-fabric-dev`,
его моды и мир `stream 1` скрипт не меняет, PrismLauncher не закрывает.

## Как запускать

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Only wedding|family|place|names|voices] [-ShotEverySeconds 60] [-Keep] [-MaxGames 3]
```

1. Влейте в свою ветку main (там `scripts\ParallelPrism.psm1` и новый `run-selftest.ps1`). Старый скрипт
   закрывает Prism и правит основной инстанс, а это ломает чужие прогоны: не запускайте его.
2. Скрипт собирает вашу ветку, ждёт свободный слот (не больше 3 игр на машине, считая и игры пользователя),
   копирует базовый инстанс в `%APPDATA%\PrismLauncher\instances\t-mt-<ветка>-<время>-<nn>` с JAR вашей ветки
   и копией мира `stream 1`, запускает его через уже открытый Prism и ждёт только свой `javaw`.
3. После выхода игры копия удаляется. Логи, `latest.log` и снимки лежат в
   `%LOCALAPPDATA%\MobTalk\selftest\<имя копии>\` (`selftest-run.log` в UTF-8, `selftest-latest.log`,
   снимки игры из `screenshots`, снимки окна `window-*.png` при `-ShotEverySeconds`).

## Что разведено между копиями

- Моды, конфиги, `options.txt`, мир и логи: у каждой копии свои папки.
- Whisper: копия в слоте N ходит на порт `63919 + N` (63920–63922), основной инстанс остаётся на 63919.
  Мод останавливает при выходе только свой helper; скрипт после игры добивает helper своего порта.
  Каждый helper на CUDA занимает 3–4 ГБ видеопамяти из 10: при трёх играх с голосовыми этапами может не хватить.
- Пауза при потере фокуса выключена в копии (`pauseOnLostFocus:false`), иначе фоновое окно встаёт на паузу.
- Окно: `Save-GameWindowShot` снимает окно своей копии по PID через PrintWindow, даже если оно за другими окнами.
- Слоты: файлы `%LOCALAPPDATA%\PrismParallel\slots\slot-N.lock` держит открытыми живой скрипт; упавший
  скрипт освобождает слот сам. Тот же модуль и те же слоты у divine-block, лимит общий.

## Запуск через Prism

Копия запускается командой `prismlauncher --launch <копия>`, которую принимает уже открытый Prism. Он
пересканирует `instances`, когда там появляется или исчезает папка; полузаписанную копию он
пропускает и потом отвечает в логе «resolves to nothing». Поэтому копия собирается в
`PrismLauncher\parallel-staging` и переносится в `instances` целиком, а `Start-TestInstance`, если в
`PrismLauncher\logs\PrismLauncher-0.log` нет «Loaded instance» копии, заставляет Prism пересканировать
(создаёт и удаляет пустую папку `t-rescan-*`). Запуск повторяется при отказе, при перезапуске Prism старым
скриптом и если за 3 минуты после принятого запуска `javaw` не появился (всего до 10 минут). Ожидание выхода
и таймаут — только по PID своей копии.

Проверено 21 октября: прогоны `-Only names` и `-Only place` шли одновременно (слоты 2 и 3, порты 63921 и 63922,
18:55:46–18:57:23), names прошёл; у place под нагрузкой (две игры и сборки других тредов) два снимка места
не успели («Подойдите к мобу ближе»), в одиночных прогонах того же кода place проходит. Этапы с жёсткими
паузами чувствительны к нагрузке: при сомнении повторите прогон один.

## Prism вне Codex

Prism, запущенный из оболочки Codex, наследует её подменённый `%LOCALAPPDATA%`
(`Packages\OpenAI.Codex_*\LocalCache\Local`) и не видит, например, `DivineBlock\openrouter-key.dpapi`.
Если Prism не запущен, `Start-TestInstance` поднимает его через `explorer.exe` (окружение рабочего стола).
Перезапустить уже открытый Prism так же: `scripts\restart-prism-clean.ps1` займёт все слоты, дождётся, пока
доиграют все игры Prism, закроет его, запустит через `explorer.exe` и освободит слоты.

## Наблюдение и уборка

- Снимок окна своей копии вручную:
  `Import-Module scripts\ParallelPrism.psm1; Save-GameWindowShot 't-mt-...' C:\путь\shot.png`.
- Свой процесс игры: `Get-InstanceGame 't-mt-...'`. Чужие `javaw` и Whisper не трогать.
- Оставленные копии (`-Keep`, сбой): `scripts\clean-test-instances.ps1` удаляет t-mt-* без запущенной игры,
  `-Name <копия>` одну, `-All` все t-* любых репозиториев, `-WhatIf` только показывает.

## Краткая инструкция для других тредов

> Самотесты теперь параллельные. Влейте свежий main в свою ветку и запускайте как раньше:
> `powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Only ...]`. Скрипт сам создаёт копию
> инстанса `t-mt-<ветка>-...` с вашим JAR и копией мира, ждёт слот (не больше 3 игр сразу), не закрывает
> Prism и не трогает `1.21.1-fabric-dev` и `stream 1`, после игры удаляет копию. Проверять, что `javaw`
> не запущен, больше не нужно; старый скрипт из своей ветки не запускайте. Логи и снимки:
> `%LOCALAPPDATA%\MobTalk\selftest\<копия>\`. Снимки окна по ходу: `-ShotEverySeconds 60`.
> Чужие `javaw` и Whisper не останавливать. Подробности: docs/parallel-selftest.md.

## Этап `server`: выделенный сервер и два клиента (трек G)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 -Only server [-Keep] [-ShotEverySeconds 60]
```

Скрипт `scripts\run-server-play-selftest.ps1` собирает Mob Talk из этой ветки, кладёт сервер в
`%LOCALAPPDATA%\MobTalk\selftest\t-srv-<ветка>-<время>\server` (`build-server-pack.ps1 -FromPack mob-talk-pack`, наш jar и
остальные наши моды из `%LOCALAPPDATA%\MobTalkPack\server-integration\mods`, без BlueMap), копирует мир сборки
`stream 1 (сборка)`, берёт свободные порты (игра от 25570, админка от 8797), запускает сервер с
`-Dmobtalk.selftest.play=Test1,Test2 -Dmobtalk.selftest.notts=true` и двух клиентов `new-test-client.ps1`
(`-Dmobtalk.selftest.play.client=<ник>`). Голос бога в копии конфига выключен, Whisper не грузится, озвучка — тон.

Сервер — дирижёр (`ServerSelfTestPlay`): строит площадку на y=200 над точкой появления, призывает жителей и шлёт клиентам
реплики сценария пакетом `mobtalk:selftest`; клиенты (`SelfTestServer`) делают это своими настоящими пакетами и докладывают,
что получили. Test1 — стример, Test2 — игрок, оператор — сам сервер. Клиенты называют себя своим ником (личные имена пакета
«Дима» у копий подменяются). Шаги: уровни и админка (`/api/sources`, журнал), диалог и соседи, занятый моб, подарок, окно
сделки и торг, имя обидчика в памяти и ответе, лимит 3 в минуту, права на команды, голос через сервер (фраза из кэша
`selftest-fixtures`), молитва и мировая кара, отмена при смене мира, свадьба, выход игрока посреди разговора.
Итог `[selftest-play] ALL CHECKS PASSED` в `server.log` и `[selftest] ALL CHECKS PASSED (server stage)` в
`selftest-run.log`, если в логе сервера нет ERROR от наших модов. Около 8 минут, вызовы LLM — несколько десятков.

Рядом лежат `server.log`, `client-Test1-latest.log`, `client-Test2-latest.log`, снимки клиентов `mobtalk-server-*.png` и снимки
окон `window-*.png`. Копии клиентов ищутся только созданные этим прогоном: чужие `t-client-Test1-*` не трогаются.
