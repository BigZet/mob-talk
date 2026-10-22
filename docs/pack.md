# Общая сборка: все наши моды вместе

С 22 октября 2026 все моды живут в одном отдельном инстансе Prism **«Наша сборка (все моды)»**
(папка `%APPDATA%\PrismLauncher\instances\mob-talk-pack`). Основной инстанс `1.21.1-fabric-dev` и мир `stream 1`
сборка не трогает: в ней своя копия мира «stream 1 (сборка)».

## Что внутри

| Мод | Откуда |
| --- | --- |
| Mob Talk (с жителями-людьми по MCA, CTOV-деревнями, свадьбой, семьёй) | этот репозиторий |
| Божественный блок | `divine-block`, ветка main |
| Русская флора (борщевик) | `rusflora`, ветка main |
| Parrot Cages | `parrot-cages`, ветка main |
| Viewer Parrots 1.2.1 | оригинальный jar (знает про клетки) |
| CTOV 3.6.3 + Lithostitched 1.8.1 | оригинальные jar (CC BY-NC-ND, не распаковываем) |
| WATERFrAMES, WaterMedia, YouTube-плагин, CreativeCore, WaterVision, Fabric API | jar из основного инстанса |

Точные коммиты и список jar — `mob-talk-pack\pack-manifest.txt`.

## Как собрать и обновить

```powershell
powershell -ExecutionPolicy Bypass -File scripts\build-pack.ps1
```

Скрипт собирает Mob Talk из текущего чекаута, выгружает `main` остальных репозиториев через `git archive` в
`%LOCALAPPDATA%\MobTalkPack\src` (рабочие копии других тредов не трогаются), собирает их и заменяет моды в инстансе.
Первый запуск создаёт инстанс из `1.21.1-fabric-dev` без его миров. Пока игра сборки запущена, скрипт отказывается работать.

## Меню и клавиши

- В меню паузы справа внизу кнопка **«Моды сборки»**: настройки Mob Talk (F7), Божественного блока (F9),
  панель попугаев (F8) и список всех клавиш модов; совпадающие клавиши подсвечены красным.
  Своя кнопка Божественного блока при Mob Talk не добавляется; кнопка Viewer Parrots остаётся справа вверху.
- Клавиши: V говорить с мобом, F7 настройки Mob Talk, B сделки, G подарок, R молитва вслух, F9 настройки бога,
  F8 панель попугаев. Конфликтов нет (проверяет самотест `-Only pack`).

## Whisper один на всех

Mob Talk поднимает Whisper при старте игры и держит его до выхода (между мирами не выгружает). Божественный блок
ходит на тот же адрес `127.0.0.1:63919` и свой helper не поднимает, пока жив helper Mob Talk.
В одноразовых копиях самотестов Mob Talk грузит Whisper только когда нужен (несколько игр сразу не влезут в видеопамять).

## Самотесты сборки

- `scripts\run-selftest.ps1 -Base mob-talk-pack -Only pack` — вся сборка: моды, клавиши, меню паузы и хаб,
  экраны F7/F9/F8, блоки всех модов, жители-люди, общий Whisper. Снимки `mobtalk-pack-*.png`.
- `scripts\run-selftest.ps1 -Base mob-talk-pack` — полный самотест Mob Talk внутри сборки.
- `scripts\run-pack-modtest.ps1 -Mod divine|church|cages|hogweed` — самотесты других модов внутри сборки.
- Платная озвучка в самотестах выключена (`-Dmobtalk.selftest.notts=true`, у бога TTS по умолчанию выключен):
  реплики «звучат» тихим тоном той же длины, а фразы для Whisper озвучены один раз и лежат в
  `%LOCALAPPDATA%\MobTalk\selftest-fixtures`. Настоящая озвучка: `-WithTts`.
- Окно самотеста не забирает фокус и не захватывает мышь: в каждую копию кладётся `selftest-guard.jar`
  (`tools\selftest-guard`, копия в `%LOCALAPPDATA%\PrismParallel\selftest-guard.jar`). Он работает, только если в JVM
  есть свойство самотеста.
- Сторож зависаний `Get-TestGameStall` (ParallelPrism.psm1): краш-отчёт или `latest.log` молчит 6 минут — скрипт
  убивает только javaw своей копии и снимает окно в `window-stuck.png`.
- Общий потолок — 6 игр Minecraft на машине (`Enter-GameSlot`, `-MaxGames 6`).

## Сервер

Viewer Parrots с 23 октября собирается из `main` репозитория `minecraft-parrot` (как остальные наши моды), а не берётся из инстанса.

Та же сборка ставится на выделенный сервер: `scripts\build-server-pack.ps1 -FromPack mob-talk-pack` раскладывает те же jar
(без чисто клиентских) плюс админ-моды в `%LOCALAPPDATA%\MobTalkPack\server`, `scripts\run-local-server.ps1` запускает,
`scripts\export-mrpack.ps1` делает файл для друзей. Подробно — `docs/server.md`, для VPS — `docker/README.md`.
