# Веб-админка Mob Talk: API

Контракт между сервером (трек W1, `io.github.mobtalk.server.web`) и страницей (трек W2, `assets/mobtalk/web/`). Меняет его W1 с записью
сюда; W2 работает против `DevStubServer`, который отдаёт этот же API из фикстур. План и разбивка — `docs/server-plan.md`, бриф W.

## Общее

- Адрес по умолчанию `https://127.0.0.1:8787/` (`bind`, `port` в `config/mobtalk-admin.json`; там же PBKDF2-хэш пароля, срок истории
  `historyDays` и `keepPrompts` — отдельный файл, чтобы хэш не попадал в общий серверный конфиг). На `0.0.0.0` админка пишет предупреждение в лог.
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
- Игрок в ответах — `{"uuid":"…","name":"Ник"}`, моб — `{"uuid":"…","name":"Имя","species":"villager"}`. Seed, ключи и пароли не попадают
  ни в один ответ и ни в одну запись истории. Координаты — только в разделах «Местоположение» и «Карта» (просьба Дмитрия); в историю, лог и
  остальные ответы они не попадают.
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
| `scene` | `tags` [`attack`, `flee`, `insult`, `threat`, `slap`, `kiss`, `date`, `propose`, `divorce`, `child`], `question`, `answer` — яркая реплика жителя |
| `wedding` | `outcome` (`married`/`jilted`/`cancelled`), `since`, `village`, `vow` |
| `moment` | момент для монтажа: `start`, `end`, `weight` (1–3), `type` (`god`/`combat`/`village`/`manual`), `title`, `quote`, `players`, `sources` [id записей], `keys`, `by`, `live` |
| `moment_edit` | правка момента: `target` (id), `title`, `starred`, `hidden`, `replay` {uuid: путь} |
| `rec_session` | сессия записи: `action` (`start`/`stop`/`reopen`/`resync`/`shift`), `session` (id; у `start` id — сама запись), `start`, `end`, `source`, `output`, `shift`, `why` |
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

`ms` — от приёма реплики. Шаги в этом порядке, пропущенный шаг (нет озвучки, отказ бюджета) просто отсутствует; при ошибке
`trace.error` = `{"step":"llm_first","message":"…"}`, а `data.error` — тот же текст строкой.

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

Источники — Mob Talk (`mobtalk`), сама админка (`admin`: срок истории, `keepPrompts`) и другие моды по протоколу `mobtalk-admin`
(раздел 12.3 плана), например `divineblock`. Порядок групп `mobtalk` — как вкладки F7; личные поля (микрофон, Whisper, HUD, обращения)
не показываются и `PATCH` их отвергает.

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

## Местоположение и телепорт

| Запрос | Ответ |
| --- | --- |
| `GET /api/players/positions` | `{"t":t,"players":[{"player":{…},"online":true,"world":"minecraft:overworld","x":12.5,"y":64,"z":-301.2,"yaw":90,"biome":"minecraft:plains","health":18,"food":20,"gamemode":"survival"}]}` — только онлайн. То же приходит событием `positions` в `/api/events` каждые 2 с, пока открыта хоть одна вкладка |
| `GET /api/players/{uuid}/position` | то же для одного игрока плюс `"respawn":{"world","x","y","z"}` или `null`; для офлайн — последняя известная точка, `online:false`, `t` — когда он там был |
| `POST /api/players/{uuid}/teleport` | тело — одно из: `{"world","x","y","z"}`, `{"toPlayer":"<uuid>"}`, `{"to":"spawn"}`, `{"to":"respawn"}`, `{"waypoint":"<id>"}` → `{"ok":true,"message":"…"}`; `409` — игрок не в игре; запись `command` в историю без координат |
| `GET /api/waypoints` | `{"waypoints":[{"id","name","world","x","y","z"}]}` (хранятся в `config/mobtalk-waypoints.json`) |
| `POST /api/waypoints` | `{"name","world","x","y","z"}` → `{"ok":true,"waypoint":{…}}` |
| `DELETE /api/waypoints/{id}` | `{"ok":true}`; `404` — нет такой |

## Карта

**Источник тайлов.** Если на сервере стоит squaremap (выбор Дмитрия — подробная карта; его веб-сервер выключен), админка отдаёт его
файлы `web/tiles/<namespace>_<path>/<zoom>/<x>_<z>.png` (папка ищется в `squaremap/web/tiles`, `config/squaremap/web/tiles` или задаётся
`squaremapTiles` в `mobtalk-admin.json`): тайлы 512×512, zoom API = `zoom.max` из `settings.json` мира (3 по умолчанию) − zoom squaremap.
Изменённые squaremap тайлы находятся просмотром папки раз в 10 с, пока открыта страница, и приходят событием `map`. Без squaremap
(или для мира, которого он не рисовал, например энда) — свой рендер ниже, 256×256; выбор — по каждому миру. Страница берёт `tileSize`, `maxZoom` и `source` (`squaremap`/`mobtalk`)
из `/api/map/info`; формула тайла та же: zoom 0 — 1 px = 1 блок, тайл покрывает `tileSize·2^zoom` блоков.

**Свой рендер.** Вид сверху, как ванильная карта: цвет верхнего блока (`MapColor`), светлее на подъёме к северу, темнее на спуске, вода темнее с
глубиной. Рисуется из **загруженных** чанков по мере игры: чанк попадает в очередь при загрузке и раз в 30 с вокруг каждого игрока
(построенное и вскопанное); на серверном тике снимаются цвета не больше 2 чанков (микросекунды), тайлы и PNG — на фоновом потоке.
Всё, где игроки побывали, оказывается на карте; чтение region-файлов целиком (неисследованные игроками места) — не делается.
Тайлы лежат в `data/mobtalk/map/<мир>/<zoom>/<tx>_<tz>.png`.

| Запрос | Ответ |
| --- | --- |
| `GET /api/map/tiles/{world}/{zoom}/{tx}/{tz}.png` | PNG `tileSize`×`tileSize` (512 squaremap, 256 свой), `zoom` 0..`maxZoom` (1 px = 2^zoom блоков), левый верхний угол — блок (`tx·tileSize·2^zoom`, `tz·tileSize·2^zoom`), `world` URL-кодированный (`minecraft%3Aoverworld`). Неисследованное прозрачно; тайла нет — `204`. `ETag` + `Cache-Control: max-age=60`, `If-None-Match` → `304` |
| `GET /api/map/info?world=` | `{"world","minX","minZ","maxX","maxZ","spawn":{"x","z"},"worlds":["minecraft:overworld",…],"tileSize":512,"maxZoom":3,"source":"squaremap"}`; границы — по целым тайлам зума 0, `null`, если тайлов нет |
| `GET /api/map/markers?world=` | `{"world","markers":[{"kind":"village|waypoint|boss|spawn","id","name","x","z"}]}` |
| `GET /api/players/tracks?world=&from=&to=&player=&points=2000` | `{"world","from","to","tracks":[{"player":{…},"points":[[t,x,y,z],…]}]}`; без `world` — overworld, без `from` — последний час; каждый игрок прорежен до ~`points` (первая и последняя точки остаются) |

Пути: точка раз в 5 с или сразу при сдвиге больше 4 блоков, стоящий на месте пишется раз в минуту; хранятся 7 дней в
`data/mobtalk/admin-tracks/` (своё хранилище админки, не история). В `/api/events` при перерисовке тайла приходит `map`
`{"world","zoom","tx","tz"}` — перезагрузить этот тайл.

## 3D-карта (BlueMap)

BlueMap стоит на сервере со своим веб-сервером выключенным; рендер работает, только пока кто-то смотрит вкладку «3D».

| Запрос | Ответ |
| --- | --- |
| `GET /map3d/…` | файлы webroot BlueMap (`bluemapWebroot` в `mobtalk-admin.json`, по умолчанию `bluemap/web`) за той же сессией (без неё `401`). Файл `X`, если его нет — `X.gz` с `Content-Encoding: gzip` (так BlueMap хранит `.prbm` и `textures.json`); неотрисованный тайл `maps/*/tiles/…` — `204`. `maps/<id>/live/players.json` отвечает админка (`{"players":[{"uuid","name","foreign","position":{x,y,z},"rotation":{pitch,yaw,roll}}]}`, мир по id карты: `*nether*`, `*end*`, иначе overworld), `live/markers.json` — `{}`. Свой CSP: `script-src 'self' 'unsafe-eval'; worker-src 'self' blob:; img-src 'self' data: blob:; frame-ancestors 'self'`, `X-Frame-Options: SAMEORIGIN` — только здесь, остальная админка как была |
| `POST /api/map3d/keepalive` | `{"ok":true,"rendering":true}`: первый запуск — `bluemap start`; слать раз в 30–60 с, пока вкладка открыта. Без BlueMap — `503` |
| `GET /api/map3d/status` | `{"available","rendering","progress":0..1|null,"lastRender":t|null}`; `progress` — первое `NN%` из вывода `/bluemap` (читается не чаще раза в 10 с), `lastRender` — самая свежая папка карт |

Через 3 минуты без keepalive админка шлёт `bluemap stop`. Команды уходят только после строки `BlueMap loaded!` в `latest.log`; до неё
keepalive и status отвечают `"loading":true`. Сразу после загрузки — `bluemap stop` (BlueMap помнит start/stop между перезапусками),
а если вкладку уже смотрят — `bluemap start`. Не проверено на настоящем BlueMap: нужен ли
`'unsafe-eval'` (оставлен на всякий случай, только для `/map3d/`), формат прогресса в выводе `/bluemap` и точные поля `live/players.json`.

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

Исповедь (Б4, docs/confession.md): лента — `/api/history?kind=confession[&player=…]` (факт и числа; слова — только с тумблером
`confessionHistoryText`), епитимьи Mob Talk:

| Запрос | Ответ |
| --- | --- |
| `GET /api/confession/penances` | `{"items":[{"player":{…},"kind":"candle","title":"свеча","hud":"Епитимья: …","priest","place":"temple|village","progress","target","done","deeds","assigned":t,"due":t}]}` |
| `POST /api/confession/penances/{uuid}/credit` | засчитать: игрок может вернуться к иерею за разрешением |
| `POST /api/confession/penances/{uuid}/cancel` | снять без наказания: отложенные грехи снова ждут исповеди (`absolve_release` у бога) |

## Моменты для монтажа (идея З4)

Сервер сам собирает яркие моменты из истории (кары и чудеса, боссы, баны, свадьбы, смерти, сцены с жителями, сорванный торг):
у каждого вида вес 0–3 и «разгон» — сколько секунд смотреть до события (`admin.moments.weights`, `.preroll` в `mobtalk-admin.json`).
События одного игрока ближе `mergeSeconds` склеиваются, вес — наибольший (+1 при трёх и больше событиях), в список попадает момент
не легче `threshold`. Ручная метка (`/clip`, клавиша «Отметить момент», кнопка в админке) — всегда, на `manualBackSeconds` назад.
Таймкоды считаются от начала сессии записи: она открывается OBS игрока (F7 → «Метки для монтажа»), входом стримера
(`sessionAuto`), `/clip start` или из админки. Поправки — `/clip shift <с>` или «Сдвиг»/«Начало» во вкладке «Моменты».

| Запрос | Ответ |
| --- | --- |
| `GET /api/moments/sessions?player=` | `{"sessions":[{"id","player","start","end","open","source","output","shift","seconds","moments"}],"open":[…]}` |
| `POST /api/moments/sessions` `{"player":{uuid,name},"action":"start"\|"stop","back":с}` | `{"ok":true,"session":{…}}` |
| `POST /api/moments/sessions/{id}` `{"start":t}` или `{"shift":мс}` | `{"ok":true,"session":{…}}` |
| `GET /api/moments?session=&minWeight=1..3&everyone=&hidden=&hours=` | `{"session","items":[{"id","t","start","end","timecode","weight","type","title","originalTitle","quote","players","mob","sources","by","starred","hidden","replay"}],"pending":[…]}` |
| `POST /api/moments` `{"player","title","back"}` | ручная метка |
| `PATCH /api/moments/{id}` `{"title"?,"starred"?,"hidden"?}` | правка |
| `GET /api/moments/export?session=&format=youtube\|csv\|edl\|xml&starred=&minWeight=&everyone=` | файл: главы YouTube, CSV (`;`, BOM), EDL CMX3600 с маркерами Resolve, XML (xmeml, 30 fps) |

## Сервер и отладка

| Запрос | Ответ |
| --- | --- |
| `GET /api/server` | `{"version":"0.7.0","minecraft":"1.21.1","loader":"0.19.5","mods":[{"id","name","version"}],"uptimeSeconds":7260,"world":{"name":"world","difficulty":"normal","dayTime":6000},"players":{"online":2,"max":8},"pack":["строки pack-manifest.txt"],"admin":{"bind":"127.0.0.1","port":8787,"fingerprint":"AB:CD:…"}}` |
| `GET /api/server/commands` | `{"roots":["mobtalk","divineblock","vp","whitelist",…]}` для автодополнения |
| `POST /api/server/command` | `{"command":"say привет"}` (без `/`) → `{"ok":true,"result":1,"output":["строки ответа"]}`; запись `command` в историю |
| `GET /api/logs?lines=200&filter=mobtalk,divineblock&level=WARN` | хвост `logs/latest.log`: `{"lines":[{"t":"19:35:18","level":"INFO","logger":"mobtalk","text":"…"}]}`; `lines` ≤ 2000, `filter` — подстроки имени логгера, `level` — минимальный |
| `GET /api/logs/export?filter=&level=&lines=` | `text/plain; charset=utf-8`, `Content-Disposition: attachment; filename="mobtalk-log-<yyyyMMdd-HHmm>.txt"`. Без параметров — весь `latest.log`; с ними — те же фильтры, что `/api/logs`, `lines` ≤ 200 000. Ключи и координаты вычищены |
| `GET /api/diagnostics` | `application/zip`, `mobtalk-diagnostics-<yyyyMMdd-HHmm>.zip`: `latest.log`, `errors.json`, `server.json`, `overview.json`, `settings.json` (значения всех источников), `history.jsonl` (последние 500 записей за сутки). Всё через `Redact` |
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
| `positions` | как `GET /api/players/positions`, каждые 2 с |
| `map` | `{"world","zoom","tx","tz"}` — тайл перерисован |

`GET /api/live/dialogues` — SSE для вкладки «Сейчас»: `snapshot` сразу и при каждом изменении,
`data` = `{"dialogues":[{"id","player":{…},"mob":{…},"step":"llm_first","stepSince":t,"startedAt":t,"question":"…"}],"activities":[{"player":{…},"activity":"talking"}]}`.
`POST /api/live/dialogues/{id}/cancel` → `{"ok":true}`.

При обрыве страница переподключается (`EventSource` сам ретраит; сервер шлёт `retry: 3000`).

## DevStubServer

Заглушка для разработки страницы без Minecraft: тот же `AdminHttpServer` (вход, CSRF, заголовки, маршруты), но данные из фикстур
`src/test/resources/admin-stub/*.json`, шумовой рельеф ±1024 блока вокруг спавна (вода, песок, трава, лес, горы; кусок незера),
шесть часов путей по кругу у двух игроков и синтетические метрики (синусоиды с шумом, живые точки по SSE каждые 2 с, новые записи истории
и диалоги «Сейчас» раз в несколько секунд).

```
gradlew runAdminStub                       # http://127.0.0.1:8788/, пароль dev
gradlew runAdminStub --args="--port 8790 --web C:/путь/к/assets/mobtalk/web"
```

Без `index.html` в папке страница отвечает 404, API работает. Заглушка слушает только loopback и по HTTP (cookie без `Secure`), страницу берёт с диска из `src/main/resources/assets/mobtalk/web`
текущего worktree — правки видны после обновления страницы. Изменения через `PATCH`/`POST` держатся в памяти до перезапуска.

## Подключение к серверу (волна 1, для интегратора)

Сделано треком H (23 октября): `io.github.mobtalk.server.AdminLink` (старт/стоп вместе с выделенным сервером, метрики, журнал, карта,
источники из entrypoint, `/mobtalk admin web`) и `io.github.mobtalk.server.LiveGameView` (живой `GameView`). Пункты ниже — исходный
список точек; из них не сделано: `divineSouls`/`divineActive` (503, нет операций у источника `divineblock`), расход LLM в `journal().cost`
(клиент диалога не читает `usage.cost`), `hearts` в отношениях (0).

- `fabric.mod.json`: `"mobtalk-admin": ["io.github.mobtalk.server.web.MobTalkAdminSource"]` (класс в пакете `server.web`, а не `server`).
- `MobTalkMod`: на `SERVER_STARTED` — `AdminWeb.start(runDir, new AdminWeb.Hooks(game, saver, worldDir, ServerConfig::get, ServerConfig::update,
  SettingsScope::personal, limits), LOGGER::info)`, на `SERVER_STOPPING` — `stop()`. Нужны: `GameView` на живом сервере (игроки, мобы из
  `ConversationStore`, деревни, бог через источник `divineblock`, позиции и телепорт), `Backups.Saver` (команды `save-off`/`save-all flush`/
  `save-on` на серверном потоке), сэмплер метрик раз в 10 с через `ServerTickEvents` (`Metrics.record`, MSPT/TPS, память, чанки, сущности,
  `journal.drainStarted()`, `drainSpent()`, `errors.drain()`), источники других модов из entrypoint `mobtalk-admin` в `sources().add`.
- `DialogueService`: `journal().dialogueStarted(...)` → `step(...)` по шагам трассы → `finish(...)`/`fail(...)`; расходы — `journal().cost(...)`,
  распознавание — `journal().transcribed(ms)`; остальные события (подарок, торг, слух, вход/выход) — `journal().event(kind, …)`.
- Карта: `MapSampler.register(web.map())` в `MobTalkMod.onInitialize` (события загрузки чанков и тика). `GameView.mapMarkers(world)` —
  деревни (центр по `VillageStanding`/POI), живые боссы, спавн мира.
- Команда `/mobtalk admin web` → `AdminWeb.describe()`, `/mobtalk admin web password <новый>` → `setPassword`.
