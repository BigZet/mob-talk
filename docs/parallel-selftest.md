# Параллельные самотесты в игре

С 21 октября 2026 `scripts\run-selftest.ps1` запускает самотест в одноразовой копии инстанса Prism, поэтому
несколько веток и тредов могут тестировать одновременно. Базовый инстанс `divine-block-test`, его моды и миры
скрипт не меняет, PrismLauncher не закрывает. Модуль `scripts\ParallelPrism.psm1` общий с mob-talk
(там же подробности: docs/parallel-selftest.md в mob-talk), лимит игр общий на машину.

## Как запускать

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Mode true|cruel|...] [-Jvm '-Ddivineblock.cemeterytest=true'] [-ShotEverySeconds 60] [-Keep] [-MaxGames 3]
```

- Скрипт собирает ветку, ждёт свободный слот (не больше 3 игр на машине, считая чужие и игры пользователя),
  копирует `divine-block-test` в `%APPDATA%\PrismLauncher\instances\t-db-<ветка>-<время>-<nn>` с JAR ветки,
  запускает с `-Ddivineblock.selftest=<Mode>` и ждёт только свой `javaw`, затем удаляет копию.
- `-Mode` — значение флага самотеста (по умолчанию `true`), `-Church` — самотест церкви (`-Shots <папка>`
  копирует её снимки), `-Jvm` — дополнительные флаги для этого прогона. Флаги живут только в копии:
  в `divine-block-test` ничего не остаётся. Пути во флагах пишите через `/`: Prism читает `\` в JvmArgs как экранирование.
- Отчёт, `latest.log` и снимки: `%LOCALAPPDATA%\DivineBlock\selftest\<копия>\` (мод пишет `report.txt`
  туда по `-Ddivineblock.selftest.report`).
- Whisper Mob Talk и Божественного блока в копии слота N ходит на порт `63919 + N`.
- Оставленные копии: `scripts\clean-test-instances.ps1` (t-db-*), `-Name`, `-All`, `-WhatIf`.

## Краткая инструкция для других тредов

> Самотесты теперь параллельные. Влейте свежий main в свою ветку и запускайте
> `powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Mode ...] [-Jvm '...']`. Скрипт сам
> создаёт копию `t-db-<ветка>-...` с вашим JAR, ждёт слот (не больше 3 игр сразу), не закрывает Prism и не
> трогает `divine-block-test`, после игры удаляет копию. Отчёт: `%LOCALAPPDATA%\DivineBlock\selftest\<копия>\report.txt`.
> Свои флаги — через `-Mode`/`-Jvm`, а не правкой instance.cfg. Чужие `javaw` и Whisper не останавливать.
