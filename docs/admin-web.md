# Веб-админка Mob Talk: API

Контракт между сервером (трек W1, `io.github.mobtalk.server.web`) и страницей (трек W2, `assets/mobtalk/web/`). Меняет его W1 с записью
сюда; W2 работает против `DevStubServer`, который отдаёт этот же API из фикстур. План и разбивка — `docs/server-plan.md`, бриф W.

## Общее

- Адрес по умолчанию `https://127.0.0.1:8787/` (настройка `admin.bind`, `admin.port`). На `0.0.0.0` админка пишет предупреждение в лог.
  Сертификат самоподписанный (`config/mobtalk-admin.p12`), отпечаток SHA-256 печатается в консоль и в `/mobtalk admin web`.
- Статика: `GET /` → `assets/mobtalk/web/index.html`, `GET /<путь>` → `assets/mobtalk/web/<путь>` (подкаталоги можно, `..` нельзя).
  Типы по расширению (`.js` → `text/javascript`, `.css`, `.svg`, `.png`, `.woff2`, `.json`). Всё, что не `/api/*` и не найдено, → 404.
- Заголовки на всех ответах: `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline';
  connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
  `Referrer-Policy: no-referrer`, `Cache-Control: no-store` для `/api/*`. CORS нет: чужие источники не получают ответов.
- Все тела JSON в UTF-8. Время — миллисекунды Unix (`t`, `from`, `to`), длительности — миллисекунды (`ms`) или секунды (поле `*Seconds`).
  Деньги — доллары, `double` (`usd`).
- Каждый запрос, меняющий состояние (`POST`, `PATCH`, `DELETE`), должен нести заголовок `X-MobTalk: 1`, иначе 403 `csrf` (защита от
  форм с чужих сайтов вдобавок к `SameSite=Strict`).
- Ошибка — всегда `{"error":"код","message":"понятный русский текст"}` с кодом HTTP:
  `400 bad_request` (неверные параметры), `401 unauthorized` (нет сессии), `403 forbidden` / `csrf`, `404 not_found`,
  `409 conflict`, `429 too_many` (+ `retryAfter` в секундах и заголовок `Retry-After`), `500 internal`, `503 unavailable`
  (сервис ещё не подключён: например, Божественного блока нет в сборке).
- Игрок в ответах — `{"uuid":"…","name":"Ник"}`, моб — `{"uuid":"…","name":"Имя","species":"villager"}`. Координаты, seed, ключи и пароли
  не попадают ни в один ответ и ни в одну запись истории.
- Списки с пагинацией: параметры `cursor` (непрозрачная строка из прошлого ответа) и `limit` (по умолчанию 50, максимум 500), ответ
  `{"items":[…],"next":"курсор или null"}`. Порядок — новые первыми, если не сказано иное.

## Вход и сессия

| Запрос | Тело | Ответ |
| --- | --- | --- |
| `POST /api/login` | `{"password":"…"}` | `200 {"user":"admin","level":"ADMIN","expires":t}` и cookie `mt_session` (HttpOnly, Secure, SameSite=Strict, 12 ч); `401 wrong_password`; `429 too_many` после 5 попыток в минуту с одного IP |
| `POST /api/logout` | — | `200 {"ok":true}`, cookie стёрта |
| `GET /api/session` | — | `200 {"user":"admin","level":"ADMIN","expires":t}` или `401` |

Все остальные `/api/*` без сессии → `401 unauthorized`. Страница при `401` показывает форму входа. Каждая попытка входа пишется в историю
(`admin_login`: IP, успех). Уровень сейчас один (`ADMIN`, единый пароль); поле `level` оставлено на будущее.

## Обзор

`GET /api/overview` — для карточек:

```json
{"t":1761161400000,"online":2,"maxPlayers":8,"tps":19.9,"mspt":12.4,"msptP95":21.0,"memUsedMb":3120,"memMaxMb":6144,
 "dialoguesActive":1,"llmQueue":0,"llmInFlight":1,"costTodayUsd":0.42,"errorsLastHour":0,"uptimeSeconds":7260,
 "streamer":{"uuid":"…","name":"BigZet"}}
```

## Метрики

Сбор раз в 10 с. Хранение: 10-секундные точки за 48 ч и 5-минутные свёртки за 90 дней.

Ряды (`series`):

| Имя | Смысл | Свёртка |
| --- | --- | --- |
| `mspt`, `mspt_p95`, `mspt_max` | миллисекунды на тик за 10 с: среднее, p95, максимум | среднее, среднее, максимум |
| `tps` | тиков в секунду | среднее |
| `mem_used`, `mem_max` | память JVM, МБ | среднее, максимум |
| `online` | игроков онлайн | максимум |
| `chunks`, `entities` | загруженные чанки и сущности, все миры | среднее |
| `mobs_profiled` | мобов с профилем Mob Talk в загруженных чанках | среднее |
| `dialogues_active`, `llm_queue`, `llm_inflight` | диалоги идут, ждут в очереди, запросов к LLM в полёте | максимум |
| `dialogues` | диалогов начато за интервал | сумма |
| `cost_usd` | потрачено за интервал | сумма |
| `errors` | ошибок за интервал | сумма |
| `hogweed` | борщевика в загруженных чанках (если есть `rusflora`) | среднее |
| `bosses`, `curses` | живые священные боссы и активные проклятия (если есть Божественный блок) | максимум |
| `lat_<вид>_p50`, `lat_<вид>_p95` | задержки, мс; вид: `stt`, `llm_first`, `llm`, `tts`, `total` | максимум |

| Запрос | Ответ |
| --- | --- |
| `GET /api/metrics?from=&to=&points=&series=mspt,tps` | столбцы для uPlot: `{"from":t,"to":t,"step":10,"t":[сек…],"series":{"mspt":[12.1,null,…],"tps":[…]}}`. `t` — секунды Unix (как ждёт uPlot), `step` — секунд на точку. `points` — желаемое число точек (по умолчанию 300, максимум 2000): сервер берёт 10-с точки или 5-мин свёртки и прореживает по правилу свёртки ряда. Пропуски — `null`. По умолчанию `to` = сейчас, `from` = `to` − 1 ч, `series` = все. Неизвестный ряд → `400` |
| `GET /api/metrics/now` | последняя точка: `{"t":t,"values":{"mspt":12.1,…},"worlds":[{"id":"minecraft:overworld","chunks":441,"entities":812}]}` |
| `GET /api/latency?kind=llm&from=&to=` | `{"kind":"llm","count":57,"p50":1840,"p95":3900,"p99":5200,"max":6100,"buckets":[{"le":250,"n":0},{"le":500,"n":3},…,{"le":null,"n":1}]}`; виды как у `lat_*` |

## История

Append-only журнал по дням: `data/mobtalk/history/<вид>/<день>.jsonl`, срок хранения — настройка `admin.historyDays` (по умолчанию 90).

Запись:

```json
{"id":"dialogue.20261022.5f3a","t":1761161400000,"kind":"dialogue",
 "player":{"uuid":"…","name":"BigZet"},"mob":{"uuid":"…","name":"Тереза","species":"villager"},
 "text":"BigZet → Тереза: «где тут кузнец?» — «За колодцем, где дым»",
 "data":{…поля вида…}}
```

`id` — непрозрачная строка. `text` — одна строка для ленты. В списке приходит без `data.trace`, `data.prompt`, `data.raw`; целиком — по `id`.

Виды и `data`:

| `kind` | `data` |
| --- | --- |
| `dialogue` | `question`, `answer`, `actions` [строки], `usd`, `tokensIn`, `tokensOut`, `model`, `stt` (`local`/`server`/`server-local`/`text`), `error`, `ms` (всего), `trace` (ниже), при `debug.keepPrompts` — `prompt`, `raw` |
| `witness`, `village_talk` | `line`, `about`, `usd` |
| `gift` | `item`, `count`, `reaction`, `heartsBefore`, `heartsAfter` |
| `trade` | `offer`, `outcome` (`deal`/`refused`/`bargain`), `items` |
| `relation_change` | `field` (`relationship`/`hearts`/`label`), `before`, `after`, `reason` |
| `rumor` | `village`, `about` (игрок), `line` |
| `verdict` | `prayer`, `verdict`, `act` (`wrath`/`miracle`/`none`), `deed`, `power`, `fury` |
| `ban` | `reason`, `by`, `lifted` (bool) |
| `boss` | `boss`, `event` (`spawn`/`death`/`despawn`), `by` |
| `player_join`, `player_leave` | `sessionSeconds` (для leave) |
| `config_change` | `source`, `key`, `before`, `after`, `by` (`консоль`, `админка`, ник), `rollbackOf` (id или null) |
| `error` | `type`, `message`, `where`, `stack`, `context`, `repeat` |
| `command` | `command`, `by`, `result`, `output` [строки] |
| `admin_login` | `ip`, `ok` |
| `cost` | `kind` (`stt`/`llm`/`tts`/`jev`/`witness`/`village_talk`), `model`, `usd`, `units` |

Трасса диалога `data.trace`:

```json
{"steps":[{"step":"accepted","ms":0},{"step":"budget","ms":2},{"step":"context","ms":18},{"step":"prompt","ms":21},
          {"step":"llm_first","ms":980},{"step":"llm_done","ms":1840},{"step":"parsed","ms":1846},{"step":"actions","ms":1850},
          {"step":"tts","ms":2620},{"step":"delivered","ms":2700}],
 "sizes":{"promptChars":6120,"tokensIn":1830,"tokensOut":64,"audioBytes":48211},"error":null}
```

`ms` — от приёма реплики. Шаги в этом порядке, пропущенный шаг (нет озвучки, отказ бюджета) просто отсутствует; при ошибке `error` —
текст и шаг, на котором она случилась.

| Запрос | Ответ |
| --- | --- |
| `GET /api/history?kind=dialogue,gift&player=&mob=&q=&from=&to=&cursor=&limit=` | `{"items":[запись без тяжёлых полей…],"next":…}`. `player`/`mob` — UUID или ник/имя; `q` — подстрока без учёта регистра по `text` и строковым полям `data` |
| `GET /api/history/{id}` | запись целиком |
| `GET /api/history/export?format=csv|jsonl&…фильтры` | файл (`Content-Disposition: attachment`), до 100 000 записей; CSV: `t,kind,player,mob,text` |
| `GET /api/history/stats?kind=&from=&to=&bucket=hour|day&by=` | счётчики для графиков: `{"bucket":"day","t":[сек…],"series":{"<значение by>":[n…]}}`; `by` — поле записи (`kind`, `player`, `mob`) или `data.<поле>` (например `data.act`); без `by` — один ряд `count` |

## Расходы

`GET /api/costs?from=&to=&group=day|player|kind|model` →
`{"group":"day","rows":[{"key":"20261022","label":"22 окт","usd":0.42,"units":131,"count":57}],"totalUsd":0.42,
"limits":{"perPlayerMinute":0,"perPlayerDayUsd":0,"serverDayUsd":0,"maxDialoguesPerPlayer":2}}`. Источник — записи `cost`. Лимиты
меняются как обычные настройки источника `mobtalk` (0 = без ограничения).

## Настройки

Источники — Mob Talk (`mobtalk`) и другие моды по протоколу `mobtalk-admin` (раздел 12.3 плана), например `divineblock`.

| Запрос | Ответ |
| --- | --- |
| `GET /api/sources` | `{"sources":[{"id":"mobtalk","title":"Mob Talk","groups":[{"id":"talk.reply","title":"Ответ","section":"Общение","fields":[{"key":"maxReplyWords","title":"Длина ответа","type":"int","min":5,"max":120,"step":1,"default":45,"unit":"слов","help":"…","danger":null}]}]}]}`. Типы: `int`, `decimal`, `bool`, `enum` (+`options`:[{`value`,`title`}]), `string`. `danger` — текст предупреждения, если правка опасна |
| `GET /api/sources/{id}/values` | `{"values":{"maxReplyWords":45,…}}` |
| `PATCH /api/sources/{id}/values` | тело `{"values":{"maxReplyWords":60}}` → `{"ok":true,"applied":["maxReplyWords"],"errors":{"x":"почему"}}`. Применяется без перезапуска, каждое изменение — запись `config_change`. Неизвестный ключ и выход за границы — в `errors`, остальное применяется |
| `GET /api/sources/{id}/actions` | `{"actions":[{"id":"unban","title":"Снять бан","args":[{"key":"player","title":"Игрок","type":"string"}],"danger":"…"}]}` |
| `POST /api/sources/{id}/actions/{action}` | тело `{"args":{…}}` → `{"ok":true,"message":"Бан снят"}` |
| `GET /api/config/history?source=&cursor=&limit=` | записи `config_change` (как `/api/history?kind=config_change`) |
| `POST /api/config/rollback/{id}` | возвращает ключ записи к `before`, пишет новую `config_change` с `rollbackOf`; `409`, если значение с тех пор меняли |

## Игроки

| Запрос | Ответ |
| --- | --- |
| `GET /api/players` | `{"players":[{"uuid","name","online":true,"level":"ADMIN|STREAMER|PLAYER","streamer":false,"whitelisted":true,"banned":false,"banReason":null,"op":true,"onlineSeconds":7260,"lastSeen":t,"costTodayUsd":0.31,"costTotalUsd":4.10,"dialogues":212}]}` — онлайн и все известные |
| `GET /api/players/{uuid}` | то же плюс `"costByDay":[{"day":"20261022","usd":0.31}]` (30 дней), `"relations":[{"mob":{…},"relationship":40,"label":"друг","hearts":5}]`, `"settings":{…личные настройки, только чтение…}`, `"activity":"talking|trading|praying|idle"` |
| `POST /api/players/{uuid}/level` | `{"level":"ADMIN|PLAYER"}` (оператор или нет) |
| `POST /api/players/{uuid}/kick` | `{"reason":"…"}` |
| `POST /api/players/{uuid}/ban` / `unban` | `{"reason":"…"}` / — |
| `POST /api/players/{uuid}/whitelist` | `{"on":true}` |
| `POST /api/streamer` | `{"uuid":"…"}` или `{"uuid":null}` — снять |

Ответ действий — `{"ok":true,"message":"…"}`; игрок офлайн и действие требует онлайна (кик) → `409`.

## Мобы и деревни

| Запрос | Ответ |
| --- | --- |
| `GET /api/mobs?village=&q=&cursor=&limit=` | `{"items":[{"uuid","name","species","village":{"id","name"}|null,"trait":"ворчун","alive":true,"loaded":true,"relations":3,"lastEvent":{"t":…,"text":"…"}}],"next":…}` |
| `GET /api/mobs/{uuid}` | `{"mob":{…},"card":{"name","character","voice","memory"},"state":{"calm":60,"trust":55,"interest":40,"mood":70,"word":"в духе"},"relations":[{"player":{…},"relationship":40,"label":"друг","hearts":5}],"facts":[{"t","text","importance"}],"events":[{"t","text"}],"family":{"spouse":{…}|null,"children":[{…}]}}` |
| `POST /api/mobs/{uuid}/card` | `{"name"?,"character"?,"voice"?,"memory"?}` → `{"ok":true}` |
| `POST /api/mobs/{uuid}/reset` | состояние и память к умолчанию, имя и характер остаются |
| `POST /api/mobs/{uuid}/clear-history` | очистить историю реплик моба |
| `GET /api/villages` | `{"villages":[{"id","name","residents":14,"chronicle":[{"t","text"}],"rumors":[{"t","text","about":{игрок}}],"standings":[{"player":{…},"points":-40,"rank":"подозреваемый"}]}]}` |
| `POST /api/villages/{id}/forgive/{uuid}` | очки игрока в деревне к нулю → `{"ok":true}` |

## Бог

`503`, если Божественного блока нет в сборке.

| Запрос | Ответ |
| --- | --- |
| `GET /api/divine/souls` | `{"souls":[{"player":{…},"sins":3,"ladder":2,"curses":[{"id","title","until":t}],"banned":false}]}` |
| `GET /api/divine/active` | `{"curses":[{"player":{…},"title","until":t}],"bosses":[{"id","title","health":120,"maxHealth":300,"since":t}]}` |

Вердикты — `/api/history?kind=verdict`, статистика кар — `/api/history/stats?kind=verdict&by=data.deed`, действия (снять проклятия,
разбан, сбросить лестницу) — `/api/sources/divineblock/actions`.

## Сервер и отладка

| Запрос | Ответ |
| --- | --- |
| `GET /api/server` | `{"version":"0.7.0","minecraft":"1.21.1","loader":"0.19.5","mods":[{"id","name","version"}],"uptimeSeconds":7260,"world":{"name":"world","difficulty":"normal","dayTime":6000},"players":{"online":2,"max":8},"pack":["строки pack-manifest.txt"],"admin":{"bind":"127.0.0.1","port":8787,"fingerprint":"AB:CD:…"}}` |
| `GET /api/server/commands` | `{"roots":["mobtalk","divineblock","vp","whitelist",…]}` для автодополнения |
| `POST /api/server/command` | `{"command":"say привет"}` (без `/`) → `{"ok":true,"result":1,"output":["строки ответа"]}`; запись `command` в историю |
| `GET /api/logs?lines=200&filter=mobtalk,divineblock&level=WARN` | хвост `logs/latest.log`: `{"lines":[{"t":"19:35:18","level":"INFO","logger":"mobtalk","text":"…"}]}`; `lines` ≤ 2000, `filter` — подстроки имени логгера, `level` — минимальный |
| `GET /api/errors` | `{"groups":[{"id","type":"java.lang.IllegalStateException","message","where":"DialogueService.accept","count":3,"first":t,"last":t,"stack":"…","context":"…"}]}` |
| `POST /api/errors/clear` | обнулить группы → `{"ok":true}` |
| `POST /api/server/backup` | снимок мира без остановки (`save-off` → `save-all flush` → zip → `save-on`) → `{"ok":true,"backup":{"name":"20261022-193518.zip","size":104857600,"t":t}}`; второй, пока идёт первый, → `409` |
| `GET /api/backups` | `{"backups":[{"name","size","t"}]}` |
| `POST /api/server/reload-config` | перечитать `mobtalk-server.json` → `{"ok":true}` |

## Живые события (SSE)

`GET /api/events` — `text/event-stream`, комментарий-пинг `: ping` каждые 15 с. События (`event:` и `data:` с JSON):

| `event` | `data` |
| --- | --- |
| `hello` | `{"t":t}` сразу после подключения |
| `metrics` | точка как в `/api/metrics/now`, каждые 10 с |
| `history` | новая запись истории любого вида (без тяжёлых полей) |
| `presence` | `{"player":{…},"online":true}` |
| `config` | запись `config_change` |
| `error` | группа ошибок после изменения |
| `backup` | `{"name","size","t"}` |

`GET /api/live/dialogues` — SSE для вкладки «Сейчас»: `snapshot` сразу и при каждом изменении,
`data` = `{"dialogues":[{"id","player":{…},"mob":{…},"step":"llm_first","stepSince":t,"startedAt":t,"question":"…"}],"activities":[{"player":{…},"activity":"talking"}]}`.
`POST /api/live/dialogues/{id}/cancel` → `{"ok":true}`.

При обрыве страница переподключается (`EventSource` сам ретраит; сервер шлёт `retry: 3000`).

## DevStubServer

Заглушка для разработки страницы без Minecraft: тот же `AdminHttpServer` (вход, CSRF, заголовки, маршруты), но данные из фикстур
`src/test/resources/admin-stub/*.json` и синтетические метрики (синусоиды с шумом, живые точки по SSE каждые 2 с, новые записи истории
и диалоги «Сейчас» раз в несколько секунд).

```
gradlew runAdminStub                       # http://127.0.0.1:8788/, пароль dev
gradlew runAdminStub --args="--port 8790 --web C:/путь/к/assets/mobtalk/web"
```

Заглушка слушает только loopback и по HTTP (cookie без `Secure`), страницу берёт с диска из `src/main/resources/assets/mobtalk/web`
текущего worktree — правки видны после обновления страницы. Изменения через `PATCH`/`POST` держатся в памяти до перезапуска.
