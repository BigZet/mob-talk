# План: сборка Mob Talk на выделенном сервере Fabric

22 октября 2026, согласован с Дмитрием (решения в разделе 0). Это рабочий документ для координатора и всех тредов:
раздел 12 — общие контракты, которых держатся все треки; раздел 13 — брифы тредов, готовые к запуску.

## 0. Решения Дмитрия

- Сразу настоящий серверный мод, без промежуточного «хоста» на клиенте стримера.
- За LLM, озвучку и распознавание платит единственный админ-хостер (Дмитрий), ключи только на сервере.
- Распознавание речи: попробовать OpenRouter STT через сервер и локальный Whisper (CPU или GPU) у каждого игрока.
- Реплику слышат все в радиусе; все звуки мобов общие и позиционные.
- Деревня одна, личности мобов одни, слухи общие с именами («Дима ударил»), отношения и поручения у каждого игрока свои.
  Житель женат только на одном игроке.
- Кары бьют всех игроков. Вечный бан, блага (эффекты, алмазы) и телепорт домой — только просящему, по его точке респавна.
- Один стример на сервере, назначается через меню/админку. Команды модов — стримеру и админу, у админа полный доступ,
  у игроков команд нет. Серверные моды для администрирования поставить.
- Веб-админка в первой версии, настройки сразу разграничить на серверные и личные. Делать параллельно, красиво и удобно.
  **Функционал максимальный**: графики и статистика, история всего, трассировка диалогов, отладка, управление игроками, мобами,
  настройками, консоль команд и резервные копии (бриф W, два треда).
- Будущий хост — VPS (скорее Ubuntu), в Docker. Сейчас тест локально, второго аккаунта нет (`online-mode=false`).
- Первая версия: голосовой диалог, состояние мобов, подарки, торг, бог, жители-люди (MCA), деревни CTOV.
  Во вторую: свадьба, дети, ревность, совместный сон, инициатива мобов к стримеру.
- **После переноса серверная версия — единственная.** Сборка изначально задумывалась серверной: все баги правятся уже в ней,
  одиночка — тот же серверный код на встроенном сервере, отдельной «клиентской» ветки не остаётся. Известные сбои самотестов
  (11 FAIL: особая ночь, сон вместе, корова-очевидец, кольцо в руке, ключи DPAPI под нагрузкой) разбираются после переноса на сервере,
  а не до него.
- Хост (предварительно, 22 октября вечером): **выделенный сервер Timeweb, i9-9900K, RTX 3080 10 ГБ, 64 ГБ ОЗУ, около 31 ₽/час,
  арендуется на 1–2 дня игры**, домен Дмитрия на Timeweb (A- и SRV-записи на сервер). Сравнение вариантов — в отдельном треде
  (артефакт `claude.ai/artifact/Gdn75uKZnn6g97131EBfgh`). Домашний компьютер с белым IP остаётся запасным путём, трек E делает оба.
  Видеокарта на сервере есть, поэтому распознавание по умолчанию для друзей — **Whisper на сервере** (`server-local`, `large-v3` на
  RTX 3080, бесплатно); локальный Whisper у игрока — для тех, у кого он есть; OpenRouter STT — запасной, если helper на сервере лёг.
- Стримером может быть сам Дмитрий или другой, неопытный игрок. Twitch-авторизация — только на клиенте самого стримера через
  графическое меню (как уже делает Viewer Parrots), коды Twitch никому не передаются; сервер лишь знает, кто стример.
- Озвучка: по умолчанию через OpenRouter (там же Jev, один ключ), Venice — альтернатива по настройке; голоса ElevenLabs через провайдера.
- Админка — веб с авторизацией, доступ с любого устройства (телефон, другой компьютер): HTTPS, пароль, сессии, защита от перебора.
- Whitelist по никам на сервере включён; `online-mode=true` на публичном сервере, `false` только для локального теста без второго аккаунта.
- MCP для разработки согласуются и ставятся до старта тредов (раздел 15).

## 1. Что показал осмотр кода

| Мод | Сейчас | Что нужно для сервера |
| --- | --- | --- |
| Mob Talk | `environment: client`. Ядро `core/` (15,6 тыс. строк) — чистая Java без Minecraft. Игровая логика `client/game/` (6,8 тыс. строк) работает с серверными сущностями через встроенный сервер одиночки; из 26 файлов только `DatasetHarvester` трогает `MinecraftClient`. Оркестратор `MobTalkClient` (1444 строки) смешивает микрофон, Whisper, LLM, действия мобов, экраны. Отношения уже по UUID игрока (`ConversationStore.Relation`), память мира в `data/mobtalk/conversations.json`. Ключи: `OPENROUTER_API_KEY` из окружения уже поддерживается (`OpenRouterToken`). Из 23 миксинов только `ChatScreenAccessor` и `LivingEntityRendererInvoker` клиентские, остальные патчат общие классы. | Разделить на серверную и клиентскую части и связать пакетами. Данные почти готовы. |
| Божественный блок | `environment: *`, логика на сервере, души по UUID (`GodLedger.soul`), настоящий бан (`Banishment`, `UserBanList`), телепорт по точке респавна, ключи из окружения (`ApiKeys`), пакеты клиенту для эффектов и голоса, команды `/divineblock act|boss|cemetery|clear|curses|lift|list|ult|unban`. Молитва голосом через локальный Whisper (`LocalWhisper` принимает только localhost). | Аудит целей кар и благ, стример, общий путь распознавания, источник настроек для админки. |
| Русская флора, Parrot Cages | `environment: *`. | Замер нагрузки борщевика (`HogweedBlock` random tick) при 6 игроках. |
| Viewer Parrots 1.2.1 | `environment: *`, серверные команды `command/ViewerParrotCommands`, Twitch-авторизация на клиенте (`twitch/`, `auth/`), пакеты `network/`. | Узнать корни команд и путь зрительских команд, фильтровать по правам. |
| CTOV, Lithostitched, WATERFrAMES, WaterMedia, WaterVision, CreativeCore, Fabric API | `environment: *`, оригинальные jar. | Ставятся на сервер и клиентам. CTOV и Lithostitched (CC BY-NC-ND) — только ссылками. |

## 2. Архитектура Mob Talk после переноса

Один jar, `environment: *`, входы `main` (сервер и одиночка) и `client`.

**Сервер (`io.github.mobtalk.server`)**
- `core/` без изменений. `client/game/*` переезжает в `main` как `io.github.mobtalk.game` вместе с общими миксинами
  (`mobtalk.mixins.json`); клиентскими остаются `ChatScreenAccessor`, `LivingEntityRendererInvoker` (`mobtalk.client.mixins.json`).
- `DialogueService` — приём текста игрока → бюджет → контекст (`MobContextCapture`, `PlaceCapture`) → `ConversationEngine` → действия
  (`MobActionController`) → озвучка → рассылка игрокам в радиусе. Всё, что `MobTalkClient` делает вокруг диалога, кроме микрофона и экранов.
- `SpeechService` — озвучка один раз на реплику, кэш по (голос, текст), рассылка чанками игрокам в радиусе.
- `TranscriptionService` — распознавание для тех, кто шлёт аудио на сервер (OpenRouter STT).
- `PlayerBudget`, `Permissions`, `ServerConfig`, `PlayerSettingsStore` — раздел 12.
- Веб-админка `server/web` — раздел 13, трек W.

**Клиент (`io.github.mobtalk.client`)** — микрофон по удержанию V, кнопки B/G, F7 только с личными настройками, экраны сделок/инфо,
подписи и HUD, приём и позиционное воспроизведение аудио, рендер жителей-людей (MCA), локальный Whisper-helper по желанию.

**Одиночка** работает тем же серверным кодом на встроенном сервере: пакеты идут внутри одной JVM, ничего не теряется.

## 3. Голос

Распознавание, три пути, игрок выбирает в F7, сервер объявляет разрешённые в `ServerFeatures`:
1. **Локальный helper** (как сейчас; GPU или CPU) — клиент распознаёт сам, шлёт `TalkText`.
2. **Сервер → OpenRouter STT** — клиент шлёт `TalkAudio`, сервер зовёт `POST https://openrouter.ai/api/v1/audio/transcriptions`
   (JSON с base64-аудио и полем формата; модели вида `openai/whisper-1`, оплата за секунду; таймаут провайдера 60 с, наши 15 с в запасе).
   Рекомендуемый путь для друзей без GPU: ставить нечего, ключ на сервере.
3. **Текст** — чат или окно, запасной путь.
4. **Локальный Whisper на сервере** (`server-local`) — сервер с видеокартой (Timeweb RTX 3080 или домашний компьютер) поднимает
   `tools/whisper_server.py` сам и распознаёт аудио всех игроков `large-v3`, бесплатно и точнее OpenRouter. На RTX 3080 10 ГБ модель
   занимает около 3 ГБ, очередь на 6 игроков — одна фраза за раз, 15 с распознаются примерно за 1–2 с. **Путь по умолчанию для
   друзей** на выбранном хосте; при недоступности helper сервер сам переключает игрока на OpenRouter STT (`server`), об этом —
   строка в `TalkStatusS2C` и запись в историю. На сервере без GPU путь выключен.

Порядок по умолчанию для игрока: `local` → `server` (OpenRouter) → `text`; `server-local` включается админом только если у сервера
есть GPU. Божественный блок переводится на те же пути. Озвучка: сервер генерирует через OpenRouter (по умолчанию; голоса ElevenLabs
через провайдера) или Venice (настройка `ttsProvider`), кэширует, шлёт сжатый звук игрокам в радиусе `voiceSilentRange`; клиент играет
от позиции моба с затуханием `voiceFullRange`.

## 4. Игроки и отношения

- Общее: профили мобов, деревни, хроника, слухи, семьи, бонды мобов. Личное по UUID: отношение, сердечки, сделки, услуги, поручения.
  Проверить ключи в `Favor`, `Bargaining.Session`, `QuestIntent`, `FriendActions`.
- Слухи и очевидцы называют игрока его именем (`PlayerIdentity.name` с ником сервера плюс личные обращения игрока).
- Брак: один супруг-игрок на жителя (данные уже так, свадьба во второй версии).
- STAY, GUARD, защита знакомых от деспавна — на сервере для всех.

## 5. Божественный блок в мультиплеере

Кары мира — на всех (как есть). Бан, блага, телепорт домой — UUID просящего и его респавн. Бан настоящий, снимает админ
(`/divineblock unban` или админка). Ключи только из окружения.

## 6. Режим стримера и права

- `streamer` (UUID) в `ServerConfig`, назначение `/mobtalk admin streamer <ник>` или в админке, один на сервер.
- Три уровня (`Permissions.Level`): **ADMIN** (операторы сервера и список `admins` в конфиге) — все команды всех наших модов;
  **STREAMER** — команды модов сборки, кроме админских; **PLAYER** — ни одной команды модов, только действия в мире
  (говорить, дарить, торговать, молиться). Ванильные команды не трогаем. Фильтр на сервере по корню команды.
- Зрительские команды Viewer Parrots — только у стримера. Жители и бог знают, кто стример (в промпте), без особых реакций в первой версии.
- **Самообслуживание стримера.** Twitch подключает сам стример на своём клиенте через меню Viewer Parrots (F8 → вход в Twitch, поток
  устройства, без копирования кодов); сервер хранит только ник стримера. Админ назначает стримера в админке или командой, после чего у
  стримера в F8 появляется подсказка «вы стример: подключите Twitch». Трек F проверяет, что авторизация Viewer Parrots работает на
  выделенном сервере с клиента, а не только в одиночке; если нет — трек C добавляет в F8 кнопку, которая просит сервер включить режим.
- Ключи и координаты никогда не попадают в чат, HUD и журнал админки.

## 7. Настройки: три слоя

| Слой | Где правится | Где хранится | Примеры |
| --- | --- | --- | --- |
| Серверные | админка, `/mobtalk admin`, оператор | `config/mobtalk-server.json` на сервере | модели, лимиты, бюджет, функции, стример, права, озвучка, дальности |
| Личные игрока | F7 на клиенте | `config/mobtalk.json` у игрока, копия на сервере по UUID (`PlayerSettings`) | микрофон, путь распознавания, громкость, обращения, пол, HUD, жители-люди |
| Серверные, видимые игроку | только чтение в F7 | приходят `ServerFeatures` | что включено, лимиты, разрешённые пути распознавания |

Источник правды для списка и границ — `core/Tunables` (уже есть группы и диапазоны) плюс новый `SettingsScope` (какое поле личное).

## 8. Производительность

Сетевые вызовы на фоновых потоках (`BackgroundTasks`), на сервере общий пул и очередь с приоритетом «ответ собеседнику раньше
очевидцев и болтовни деревни». Лимиты: одновременных диалогов на сервер, диалогов на игрока в минуту, бюджет в день на игрока
и на сервер. Борщевик — замер и при необходимости бюджет блоков за тик. Боссы — один священный босс на мир. Сохранение памяти
мира асинхронное.

## 9. Раздача и хостинг

- Клиентам `mrpack` из Prism: наши jar внутри, CTOV, Lithostitched, Fabric API, видео-моды — ссылками на Modrinth. Друзья импортируют
  один файл. Версии фиксирует `pack-manifest.txt`.
- Серверу — тот же набор jar плюс админ-моды: Lithium, FerriteCore, Spark, Chunky, по желанию LuckPerms.
- Docker: `itzg/minecraft-server`, `TYPE=FABRIC`, `VERSION=1.21.1`, тома `mods/`, `config/`, `world/`, ключи и токен админки через
  `.env` вне git. Админка слушает 127.0.0.1, на VPS открывается через SSH-туннель.
- **Выбранный хост: Timeweb, выделенный сервер i9-9900K + RTX 3080 10 ГБ + 64 ГБ, ~31 ₽/час, на 1–2 дня игры.** Что это меняет:
  - Сервер арендуется на время игры, поэтому развёртывание должно быть **повторяемым за 15 минут**: один скрипт `deploy/timeweb.sh`
    (Ubuntu 22.04/24.04): Docker + NVIDIA Container Toolkit, `docker compose up` с профилем `gpu`, восстановление мира из последнего
    бэкапа, проверка `nvidia-smi` внутри контейнера Whisper. Перед отключением сервера — `backup.sh` (мир, `config/`, `data/mobtalk/`,
    админ-сертификат) в объектное хранилище Timeweb S3 или на компьютер Дмитрия по `rsync`/`scp`; без бэкапа сервер не выключать.
  - Whisper на сервере — отдельный контейнер `whisper` (образ с CUDA, `faster-whisper`, модель `large-v3` скачивается при первом старте
    и кэшируется в томе), слушает `127.0.0.1:63919` внутри compose-сети; Mob Talk ходит на него как на локальный helper. Путь
    `server-local` по умолчанию для друзей.
  - Домен на Timeweb: A-запись `mc.<домен>` → IP сервера, SRV `_minecraft._tcp.<домен>` → порт 25565 (чтобы друзья вводили просто
    домен); админка `admin.<домен>` за Caddy с Let's Encrypt (профиль `with-caddy`), пароль как в бриф W1. При новой аренде IP меняется —
    `deploy/timeweb.sh` обновляет записи через API Timeweb DNS (токен в `.env`), иначе — вручную в панели.
  - Ресурсы: 64 ГБ позволяют `MEMORY=12G` серверу Minecraft, остальное Whisper и ОС; `view-distance=10`, `simulation-distance=8`.
  - Цена: 31 ₽/час ≈ 750 ₽ за сутки; ключи и бюджет OpenRouter — отдельно. В админке в разделе «Сервер» показывать время аренды и
    напоминание о бэкапе (W2, маленькая карточка; W1 — `GET /api/server` отдаёт время старта контейнера).
- **Вариант «домашний компьютер как сервер»** (белый IP от провайдера) — запасной. Плюсы: это тот же локальный тест, только с проброшенным портом
  25565 — лишней работы ноль; ключи остаются в DPAPI; **видеокарта сервера может распознавать речь всем игрокам локальным Whisper**
  (четвёртый путь `server-local`, бесплатный и точнее OpenRouter STT); админка открывается с того же компьютера без туннеля.
  Минусы: компьютер должен быть включён всё время игры; если Дмитрий сам стример, на одной машине сервер, игра, OBS и Whisper —
  нагрузка на видеопамять и тики (нужен замер: Spark на сервере во время стрима); при падении интернета дома падает всё.
  Рекомендация: первые игры с друзьями — с домашнего компьютера (скрипт `run-local-server.ps1 -Public`, сервис Windows через
  `nssm` или задача планировщика, `online-mode=true`, allowlist друзей), VPS — когда понадобится круглосуточность или
  освободить машину стримера. Трек E делает оба пути, Windows-путь первым.

## 10. Локальный тест

`scripts/run-local-server.ps1` — сервер в `%LOCALAPPDATA%\MobTalkPack\server`, `online-mode=false`, порт 25565, ключи из окружения.
Клиент 1 — инстанс `mob-talk-pack`, клиент 2 — одноразовая копия (`New-TestInstance`) с офлайн-аккаунтом Prism под другим ником.
Лимит 6 игр на машине соблюдается (сервер не считается игрой, но Whisper-helper один).

## 11. Волны и порядок слияния

| Волна | Треки | Условие старта |
| --- | --- | --- |
| 0 | **S1** Каркас (Fable), **D** Бог (Opus), **E** Хостинг (Opus), **F** Разведка (Opus), **W1** Админка: API, метрики, история (Opus), **W2** Админка: интерфейс (Opus) | сейчас |
| 1 | **A** Диалог по сети (Fable), **B** Голос (Opus), **C** Игроки, права, админ (Opus), **W1** продолжает: подключение к живому серверу | после слияния S1 |
| 2 | **G** Самотест сервера (Opus), затем **H** Интеграция и VPS (Fable) | после слияния A, B, C, W |

Порядок слияния в main: S1 → D (другой репозиторий, независимо) → E → F → A → B → C → W → G. После каждого слияния `gradlew test`,
после волны 1 — сборка пака и самотест `-Only pack`. Интегратор — трек H (Fable), он же решает конфликты объединением обеих сторон.

## 12. Общие контракты (обязательны для всех треков)

Эти имена и сигнатуры создаёт трек S1 в первые же часы и коммитит первым коммитом ветки `bigzet/server-skeleton`; остальные треки
волны 0 работают по этому разделу, не дожидаясь S1, а треки волны 1 — по коду S1. Менять контракт может только S1 (волна 0) или A (волна 1),
с записью в этот раздел.

### 12.1 Пакеты (`io.github.mobtalk.net`, Fabric `CustomPayload`, канал `mobtalk:<имя>`)

Клиент → сервер:

| Пакет | Поля | Смысл |
| --- | --- | --- |
| `TalkStartC2S` | `int mobId` | игрок зажал V на мобе; сервер помечает внимание моба и отвечает `TalkStatusS2C` |
| `TalkTextC2S` | `int mobId, String text, boolean fromVoice, byte[] picture` (JPEG ≤ 200 КБ или пусто) | реплика игрока (локальный helper или текст) |
| `TalkAudioC2S` | `int mobId, String format` (`wav16k` или `mp3`), `int seconds, byte[] audio` (≤ 900 КБ, лимит payload 1 МБ) | аудио для распознавания на сервере |
| `TalkCancelC2S` | `String reason` | игрок отпустил цель, сменил мир и т. п. |
| `GiftC2S` | `int mobId` | подарок из руки (G) |
| `MobActionC2S` | `int mobId, String intentJson` (`ActionIntent`) | STAY/следуй/GUARD/стоп из экрана |
| `PlayerSettingsC2S` | `String json` (`PlayerSettings`) | личные настройки при входе и при изменении |
| `ScreenRequestC2S` | `String screen` (`info`, `family`, `village`), `int mobId` | запрос данных для экрана |

Сервер → клиент:

| Пакет | Поля | Смысл |
| --- | --- | --- |
| `TalkStatusS2C` | `String status, boolean busy, String error` | «слушаю», «думает…», отказ бюджета, ошибка |
| `MobSpeechS2C` | `int mobId, UUID mob, String speaker, String text, boolean toYou, int clipId` (0 — без звука), `double range` | реплика; `toYou` — адресована этому игроку |
| `SpeechClipS2C` | `int clipId, int index, int count, String format, byte[] chunk` (≤ 512 КБ) | звук реплики чанками; клиент собирает и играет от моба |
| `MobStateS2C` | `int mobId, int calm, int trust, int interest, int mood, String word, int relationship, String label, int hearts` | HUD и подпись |
| `MobLabelS2C` | `int mobId, String name, String line` | имя и строка под именем (беременность, ранг) |
| `ServerFeaturesS2C` | `String json` (`ServerFeatures`) | что включено, лимиты, пути распознавания, уровень прав игрока, стример |
| `ScreenDataS2C` | `String screen, String json` | данные для экранов info/family/village/deal |
| `NoticeS2C` | `String text, boolean toast` | уведомление |

Кодеки — `PacketCodec` вручную, каждый пакет — `record` в своём файле, у каждого есть тест кодирования-раскодирования в `src/test`.

### 12.2 Интерфейсы сервисов (`io.github.mobtalk.server`)

```java
public interface TranscriptionService {
    /** Текст или пустая строка; исключение — понятная игроку причина. hints — слова мира для подсказки. */
    CompletableFuture<String> transcribe(UUID player, byte[] audio, String format, int seconds, String hints);
}
public interface SpeechService {
    record Clip(int id, String format, byte[] data, int millis) {}
    CompletableFuture<Optional<Clip>> synthesize(String voiceId, String text);          // кэш по (voiceId, text)
    void broadcast(ServerWorld world, Entity source, double range, Clip clip);          // режет на SpeechClipS2C
}
public interface PlayerBudget {
    enum Kind { DIALOGUE, TRANSCRIPTION, SPEECH, WITNESS, VILLAGE_TALK }
    record Verdict(boolean allowed, String reason) {}
    Verdict allow(UUID player, Kind kind);
    void charge(UUID player, Kind kind, double usd, int units);
    String report();                                                                     // для команд и админки
}
public interface Permissions {
    enum Level { PLAYER, STREAMER, ADMIN }
    Level level(ServerPlayerEntity player);
    boolean mayRun(ServerCommandSource source, String commandRoot);                     // корень: "mobtalk", "divineblock", "vp"...
}
public final class ServerConfig {                                                        // серверный слой настроек
    public static MobTalkConfig get();                                                   // неизменяемый снимок
    public static void update(UnaryOperator<MobTalkConfig> change);                     // сохраняет JSON, зовёт слушателей
    public static void listen(Consumer<MobTalkConfig> listener);
    public static UUID streamer(); public static Set<UUID> admins();
}
public final class PlayerSettingsStore {                                                 // личный слой, по UUID
    public static PlayerSettings of(UUID player); public static void put(UUID player, PlayerSettings s);
}
```

`SettingsScope` (в `core`): `static boolean personal(String field)` — список личных полей `MobTalkConfig`: `whisper*`, `micDevice`,
`micGain`, `voiceVolume`, `sttPath`, `hud*`, `playerNames*`, `playerGender`, `humanVillagers`, `labels*`, `keys*`. Остальные — серверные.

### 12.3 Админ-API для других модов (без общего кода)

Mob Talk объявляет Fabric-entrypoint `mobtalk-admin` типа `java.util.function.UnaryOperator<String>` (JSON-строка на входе и выходе), чтобы
Божественный блок не зависел от jar Mob Talk. Запросы:

| Запрос | Ответ |
| --- | --- |
| `{"op":"describe"}` | `{"id":"divineblock","title":"Божественный блок","groups":[{"id":"wrath","title":"Гнев","fields":[{"key":"...","title":"...","type":"int|decimal|bool|enum|string","min":..,"max":..,"step":..,"options":[..],"help":"..."}]}]}` |
| `{"op":"values"}` | `{"key":value,...}` |
| `{"op":"patch","values":{"key":value}}` | `{"ok":true,"applied":[...],"errors":{"key":"почему"}}` — применяется без перезапуска |
| `{"op":"journal","since":<millis>,"limit":200}` | `{"items":[{"t":millis,"kind":"verdict|ban|miracle|prayer","player":"ник","text":"..."}]}` |
| `{"op":"actions"}` / `{"op":"action","id":"unban","args":{"player":"ник"}}` | список кнопок и выполнение (снять бан, снять проклятия) |

Mob Talk сам реализует тот же протокол для себя (`MobTalkAdminSource`) из `Tunables` + `SettingsScope`. Админка показывает все источники одинаково.

### 12.4 Правила для всех тредов

- Worktree `mob-talk/.claude/worktrees/<трек>`, ветка `bigzet/<трек>`; для Божественного блока — worktree в `divine-block`.
- Новая логика в новых классах; в общих файлах — короткие точки подключения. Чужие файлы (по таблице владения) не править:
  если нужно — написать интегратору в треде, что и где нужно подключить.
- Коммиты на русском, подпись `Co-Authored-By` по модели. Не пушить, в Prism не ставить, игру не запускать без разрешения;
  самотесты только в одноразовых копиях, без TTS, не больше 6 игр на машине. Не трогать `1.21.1-fabric-dev` и мир `stream 1`.
- Проверка минимальная: `gradlew test build` плюс тесты своего сценария. Не заявлять о проверке в игре без прогона.
- Секреты только из окружения или DPAPI; в коде, логах, конфигах, журнале админки их нет.
- В конце — короткий отчёт в треде: что сделано, что проверено, что не доделано, какие точки подключения нужны от интегратора.

### 12.5 Уточнения контракта от трека S1 (22 октября, вечер; код в ветке `bigzet/server-skeleton`)

- **`GameBridge`** (`io.github.mobtalk.game`) — без единого класса Minecraft, чтобы `DialogueService` тестировался с `FakeGameBridge`
  (он лежит в `src/test/java/io/github/mobtalk/game`). Отличия от списка в брифе: `villageAt(UUID player, String dimension, int x, int z)`
  (координатная форма `villageWhereStanding`; форма с `ServerWorld` осталась только у `MobGameBridge`), `event(UUID mob, String text)`
  (моб по памяти, незнакомец пропускается), добавлены `memory()` и `config()`; `Outcome` — вложенный record интерфейса
  (`GameBridge.Outcome`, у `MobGameBridge` он наследуется). `apply` — короткая форма с 7 аргументами, `applyWithRevision` — полная с 13.
- **`ServerFeatures`** и **`PlayerSettings`** лежат в `core` (чистые данные с Gson, нужны обеим сторонам). `PlayerSettings` — JSON с одними
  личными полями `MobTalkConfig` (`of(config)`, `fromJson`, `toJson`, `applyTo(serverSnapshot)`), чужие и серверные ключи отбрасываются.
  `ServerFeatures(version, enabled, dialogue, transcription, speech, sttPaths, maxRecordingSeconds, level, streamer)`; сейчас сервер шлёт
  `dialogue/transcription/speech = false` и `sttPaths = [local, text]` — треки A и B меняют это в `MobTalkMod.features`.
- **`SettingsScope`**: личные поля по настоящим именам — `whisper*`, `microphoneName`, `showStateHud`, `showBargainHud`, `showNameTags`,
  `playerNames*`, `playerGender`, `humanVillagers`; по префиксу личными станут и будущие `mic*`, `hud*`, `labels*`, `keys*`, `voiceVolume`,
  `sttPath`. Кроме `personal(field)` есть `fields()`, `personalFields()`, `serverFields()`, `serverOnly(config)`, `merge(server, personal)`.
- **`ServerConfig`**: к списку брифа добавлены `init(Path configDir)` (выделенный сервер; файл `mobtalk-server.json` хранит серверные поля
  плюс ключи `streamer` и `admins`), `adopt(MobTalkConfig)` (одиночка: клиент отдаёт встроенному серверу серверные поля своего
  `mobtalk.json`, файл не пишется), `persistent()`, `setStreamer`, `setAdmins`. `get()` — копия при каждой записи: взятый снимок не меняется.
- **`PlayerSettingsStore`**: `open(worldDir)`, `close()`, `of`, `put`, `remove`, `size`, `flush()`; запись на своём потоке.
- **`MobTalkMod`**: службы подменяются `setTranscription/setSpeech/setBudget/setPermissions` (треки B и C), мост ждут через
  `bridgeReady(server)`, возможности собирает `features(player)`, приёмники C2S трек A заменяет в `registerReceivers`. Клиентские приёмники
  S2C и отправка `PlayerSettingsC2S` при входе — `client/ClientNet` (трек A и B заменяют тела приёмников своими).
- **Миксины**: общие теперь в `io.github.mobtalk.game.mixin` (`mobtalk.mixins.json`), клиентские — в `io.github.mobtalk.client.game.mixin`.
- **Скрипты**: `run-local-server.ps1` целиком за треком E (он уже есть в его ветке); S1 добавил `scripts/run-server-selftest.ps1`
  (`gradlew runServer -PmobtalkSelftest=true`, раздел 16.3) и конфигурацию `runs.server` в `build.gradle`.
- **Entrypoint `mobtalk-admin`** в `fabric.mod.json` Mob Talk не объявлен: класс `MobTalkAdminSource` делает трек W1, строку
  `"mobtalk-admin": ["io.github.mobtalk.server.MobTalkAdminSource"]` в `entrypoints` добавляет интегратор при слиянии W.

### 12.6 Уточнения контракта от трека A (22 октября, ночь; код в ветке `bigzet/server-dialogue`)

- **Пакеты расширены** (старые формы остались конструкторами): `MobSpeechS2C(mobId, mob, speaker, text, toYou, clipId, range, kind, millis,
  heard, asker)` — `kind` ∈ `answer|witness|initiative|village|notice|fallback`, `heard` — слова игрока (у соседей вместе с `asker`),
  пустой `text` — моб промолчал; `MobStateS2C(mobId, mob, calm, trust, interest, mood, word, wordSign, relationship, label, hearts,
  favor, bargain, bargainSign)` — `word` и `bargain` ключи `mobtalk.state.*`/`mobtalk.bargain.*`, `favor` готовая строка услуги;
  `MobLabelS2C(mobId, mob, name, line)` — пустое имя снимает подпись. Звук: клип целиком чанками `SpeechClipS2C` в формате `pcm24k`
  (PCM движка, 48 байт в мс); трек B может слать `mp3`/`wav` — клиент декодирует `AudioDecode`.
- **Экраны** (`ScreenRequestC2S(screen, mobId)` → `ScreenDataS2C(screen, json)`): `info`/`info_secrets` → `{"message","error"}`,
  `village` → JSON-массив строк, `state` → строка в чат, `deal` (mobId = syncId) → `{"mob","syncId","entityId","name"}`, `trade` → открыть
  сделки, `forgive`. `MobActionC2S.intentJson` — `ActionIntent` в JSON; STOP с `target:"all"` — все мобы; ответ `ScreenDataS2C("action")`.
  `NoticeS2C(text, false)` — строка в чат как есть (сервер сам ставит «[Mob Talk]»).
- **`GameBridge`** дополнен: `MobView(entityId, id, name, type, dimension, x, y, z)`, `PlayerView(id, name, dimension, x, y, z, alive,
  merchant, timeOfDay)`; `mobByEntityId`, `mob`, `player`, `playersNear`, `knownMobsNear`, `attend`, `currentMerchant`, `afterWords`
  (слова семьи и свадьбы после ответа), `stopAllFollowers(игрок)`, `forgiveVillage`, `countInHand`, `friendStatus`; очереди поводов
  `pollInitiative/refundInitiative/pendingInitiatives/floorBusy`, `pollTalk/refundTalk/talkWaiting/talking`, `pollNotice`, `weddingActive`.
  `FakeGameBridge` держит мобов и игроков с позициями (`addMob`, `addPlayer`).
- **`MobTalkMod`**: `dialogue()` — служба этого сервера (создаётся вместе с мостом, закрывается на остановке), `features().dialogue = true`;
  приёмники C2S целиком в `DialogueService`. Точки для B: `TalkAudioC2S` → `MobTalkMod.transcription()` (службу подменить `setTranscription`,
  в `features` добавить `transcription=true` и путь `server` в `sttPaths`); озвучка — заменить ветку `audio.awaitAll` в
  `DialogueService.speak` на `SpeechService.synthesize` + `Outbox.clip`. Для C: `budget().allow` зовётся перед `DIALOGUE`, `TRANSCRIPTION`,
  `WITNESS`; `charge` пока только за секунды распознавания — расходы LLM/TTS считать в движке (точка: `finishDelivery`).
- **Семья и свадьба на сервере выключены** (`DialogueService.Options.family=false` на выделенном сервере); в одиночке экраны семьи,
  свадьба (`WeddingVoice` с клиентским движком), кровать и правка карточки в F7 по-прежнему идут через встроенный сервер — трек второй версии.

### 12.6 Что сделал интегратор (трек H, ночь на 23 октября; ветка `bigzet/server-integration`)

- Слиты по порядку: main (W1 карта, E Docker, squaremap/BlueMap) → A `bigzet/server-dialogue` → B `bigzet/server-voice` (и в `divine-block` main) →
  C `bigzet/server-players` (с фиксом 5298b0f) → F `bigzet/server-research`. Конфликты A/B/C разрешены объединением: `ServerFeatures`
  объявляет пути распознавания B и `dialogue` A; `TalkAudioC2S` с `mobId < 0` (проверка микрофона из F7) идёт в `VoiceIntake`, остальное —
  в `DialogueService.talkAudio`; на клиенте приёмники A, ответ `stt-test` — B, экран `server-settings` — C; путь записи выбирает `SttRoute`.
  Звук реплик идёт путём A (`DialogueService.speak` → `ServerOutbox.clip` чанками после полной загрузки озвучки); потоковая отдача B
  (`ServerSpeech.stream`, `SpeechClips` на клиенте) собрана, но не подключена — хвост.
- Админка подключена к живому серверу: `server/AdminLink` поднимает `AdminWeb` на `SERVER_STARTED` выделенного сервера (в одиночке не
  поднимается), `server/LiveGameView` реализует `GameView` (игроки, мобы и деревни из `ConversationStore`, позиции, телепорт, бан, белый список,
  стример, консоль, метки карты), резервные копии через `save-off`/`save-all flush`/`save-on`, метрики раз в 10 с из `getTickTimes()`,
  `MapSampler`, источники других модов из entrypoint `mobtalk-admin` (divineblock подключается), `/mobtalk admin web`.
  `DialogueService.Journal` — трасса каждого ответа (`accepted → budget → context → prompt → llm_first → llm_done → parsed → tts → delivered`),
  события `gift`, `witness`, `village_talk`, `player_join/leave`. Расход LLM пока 0: клиент диалога не читает `usage.cost` — хвост.
  `GameView.divineSouls/divineActive` отвечают 503: у `AdminBridge` Божественного блока нет операций `souls`/`active` — точка подключения для D.
- Семья и свадьба на выделенном сервере включены серверной настройкой `familyLife` (`DialogueService.Options.family`); пол и обращения
  игрока — из его личных настроек (C). В игре с двумя игроками не проверено.
- Сборка: Viewer Parrots теперь собирается из `main` репозитория `minecraft-parrot` (как остальные наши моды; его игровые тесты при сборке jar
  не гоняются), а не берётся из инстанса `parrot-cages-test`.
- Проверено: `gradlew test` зелёный (510), `run-server-selftest.ps1` — `ALL CHECKS PASSED`, серверный пак `build-server-pack.ps1 -Out … -Port 25566`
  поднят с двумя клиентами `new-test-client.ps1`; админка в браузере: обзор, игроки, карта (тайлы squaremap), консоль, телепорт, стример,
  белый список, правка настройки и откат, резервная копия, история, SSE, `/api/diagnostics`; остановка через консоль админки чистая.
  Живая проверка с Дмитрием (ночь на 23 октября, ник BigZet и статист Test2): диалог текстом с репликой у соседа, подарок, торг,
  права (отказ игроку, доступ после назначения), голос через сервер — работают. Замечания Дмитрия: 1) вместо озвучки слышен
  ровный гул (похож на тон-заглушку самотестов; на сервере заглушка не включена, синтез тем же кодом даёт речь — ищется по
  диагностике клипов в логе, строки «Mob Talk: clip …»); 2) два игрока с одним обращением «Дима» неразличимы для моба в промпте
  (данные по UUID верны) — отдавать обращение вместе с ником при совпадении; 3) жители во время свадьбы не знают о ней (событие не в
  контексте собеседника) — тема семьи; 4) сделки не пишутся в историю админки (журнал получает диалоги, подарки, очевидцев).
  5) таблички над мобами не появлялись: `MobStateSync` сдвигал время повтора каждый тик и слал подпись один раз, а клиент снимает
  подписи без обновления 15 с — исправлено (повтор раз в 5 с), подтверждено диагностикой клиента. Гул больше не повторился
  (клипы на клиенте — живая речь); 6) свадьба игрока на выделенном сервере отложена: `WeddingVoice` живёт на клиенте через встроенный
  сервер, по самотесту G служба застревала на клятве — нужен серверный водитель церемонии.
  7) вкладка F7 «Выбранный моб» на выделенном сервере говорит «Не удалось получить карточку»: `MobTalkClient.selectedSnapshot`
  читает память встроенного сервера одиночки — брать карточку пакетом (`ScreenRequestC2S("info")`), как `/mobtalk info`.
  Занятый моб, отмена при смене мира, выход во время диалога, лимит и молитва проверены этапом `-Only server` трека G (слит).

### 12.7 Локальная доработка после интеграции (23 октября; ветка `bigzet/affectionate-lumiere-ba634a`)

- **Свадьба игрока на выделенном сервере:** `server/WeddingHost` (включён `DialogueService.Options.ceremony` только на выделенном; в одиночке
  службу по-прежнему ведёт клиентский `WeddingVoice`). Тексты у модели, реплики службы `MobSpeechS2C(kind="wedding")` всем в 48 блоках
  от говорящего, звук — склеенные фразы одним клипом, заранее озвучивается следующая реплика. Клятва: экран `wedding_vow`
  (`{"open","seconds","entityId"}`) открывает клиенту запись до `weddingVowSeconds` и делает невесту собеседником; реплика жениха
  перехватывается в `DialogueService.talkText` до разговора. Мост: `GameBridge.wedding()` (`WeddingView`) и `takeWeddingScript()`.
  Пол игрока на свадьбе — из личных настроек. Предел клипа `TalkAudioC2S` (≈28 с wav16k) режет длинную клятву при распознавании сервером.
- **F7 «Выбранный моб» на сервере:** экран `card` (`{"snapshot","card"}`, Gson); правка карточки, сброс личности и очистка истории
  на сервере по-прежнему недоступны.
- **Два игрока с одним обращением:** `PlayerIdentity.name(uuid, nick)` добавляет ник в скобках, когда другой игрок этого сервера
  называется так же (только при серверном резолвере; одиночка без изменений).
- **Пол и имя жителя:** биография модели заменяла посеянное имя без знания пола («Валентина» с мужским телом и голосом). Писатель
  биографии теперь получает `name` и `sex`, имя другого пола в `setBackstory` не ставится, при открытии мира такие имена меняются
  на подходящие (`SocialLedger.renameToSex`, кроме семейного древа) и история пишется заново. Отказ «занят» — без рода.
- **Админка:** расход LLM по `usage.cost` OpenRouter (`LlmSpend`, запрос с `usage.include`) — в запись диалога и бюджет игрока,
  очевидцы — записью расхода; «Торг» в истории: торг словами и сделки окна торговли (`MerchantTradeMixin`). Вкладка «Бог»: операции
  `souls`/`active` в `AdminProtocol` Божественного блока (main divine-block 439a09c), `LiveGameView` спрашивает источник `divineblock`.
- **Свадьба в контексте жителей:** снимок жителя в 128 блоках от колокола несёт `environment.wedding` (`Wedding.contextLine`: кто
  женится, что идёт, его роль), строка в промпте.
- Не сделано: озвучка свадьбы потоком (клип после полной загрузки), расход TTS и Jev в журнале, облик детей-людей на выделенном
  сервере (клиент не получает родословную, пол берёт по UUID).

### 12.8 Тестовый VPS Timeweb и планы после него (23 октября)

- Тестовый сервер: Timeweb, Новосибирск, 8 vCPU / 16 ГБ, Ubuntu 24.04, `mc.amlplay.ru` (201.51.29.100), сборка de7e07c из
  `build-server-pack.ps1 -Public`, служба `mc.service` (пользователь `mc`, `/home/mc/server`), ключ OpenRouter в `/etc/mobtalk.env`
  (root, 600), ufw — только 22 и 25565, админка на 127.0.0.1:8787 через `ssh -L`.
- OpenRouter отдаёт 403 «Access denied by security policy» с российских IP (даже `/api/v1/models` без ключа). Обход: sing-box
  на сервере (`/etc/sing-box/config.json`, mixed-прокси 127.0.0.1:8118, urltest по узлам NL/DE/FI подписки Дмитрия), JVM сервера
  с `-Dhttps.proxyHost/-Dhttp.proxyHost=127.0.0.1 -D…Port=8118 -Dhttp.nonProxyHosts=localhost|127.*|[::1]`. Дома на белом IP —
  то же через локальный прокси VPN-клиента; VPN в режиме «весь трафик» ломает входящие подключения.
- **В план (просьба Дмитрия): новый мир из веб-админки.** Сейчас админка мир не сбрасывает (только `run-local-server.ps1 -Fresh`
  локально). Нужно: кнопка «Новый мир» на вкладке «Сервер» (только ADMIN, двойное подтверждение вводом названия): резервная копия
  текущего мира, `stop`, перенос `world` в `world-backups/`, по желанию сид, тип мира и копия мира из пака, затем запуск
  (на VPS перезапуск делает systemd/Docker). Данные Mob Talk мира (`data/mobtalk/`) уходят вместе с миром.
- В план: параметр прокси в `run-local-server.ps1` и в docker/VPS-развёртывании (`-Proxy host:port`).
- **В план (просьба Дмитрия): вкладка «Бог» информативнее.** Сейчас она показывает только души и активные кары
  (`souls`/`active` из `AdminProtocol` Божественного блока; есть ещё операция `journal`, вкладка её почти не использует).
  Подробности решить с Дмитрием; кандидаты из того, что divine-block уже хранит: лента молитв (текст, тон `PrayerTone` — вежливо,
  грубо, покаяние, срочно; подношение и его ценность) с ответом бога; решение `GodDecider.Choice` — чудо, сила, предупреждение, кто
  решил (правила или Jev) и записка; причины вердикта `Verdict.Reason` (вклад в милость и силу); милость/грехи души (`Soul`,
  `GodLedger`) по игроку с графиком во времени и последними поступками `DeedWatcher`; активные кары и блага с откатами (`Wrath`),
  состояние боссов-кар (`BossPunishment`, печати), зоны кар и печати на карте админки; фильтр по игроку и времени, расход Jev.
- **В план (вопрос Дмитрия): распознавание речи в веб-админке.** Сейчас в «Настройках» админки из распознавания есть только
  `sttTimeoutSeconds`; `sttServer` (распознавание на сервере через OpenRouter), `sttModel`, `sttFallbackModel`, `sttServerLocal`
  меняются лишь правкой `config/mobtalk-server.json`. Добавить их в `MobTalkAdminSource.EXTRAS` (раздел «Голос», группа
  распознавания; модель — строка как `llmModel`). Путь игрока `sttPath` остаётся личным (F7); в карточке игрока админки показать,
  какой путь он выбрал и какой получил (`SttRoute.Choice`).
- **В план (просьба Дмитрия): проклятия не дольше 20 минут.** Сейчас срок divine-block — `curseDays` игровых дней (1–7,
  по умолчанию 3 = 60 мин реального времени при работающем сервере, `Curses.add`). Сделать предел 20 мин реального времени
  (24 000 тиков): верхняя граница срока в `Curses.add` для всех проклятий (порча земли, мор, Каин, тьма и др.), настройка
  в минутах вместо дней в F9 и админке, старые сохранённые проклятия с бо́льшим `until` обрезать при загрузке.
- **В план (просьба Дмитрия, тест 23 окт): одна кара за один грех.** Сейчас кары стакаются: на вердикт с бюджетом 4 выбирается
  деяние ×4 и следом идут «кара продолжается — …» (`Prayers`, продолжения: превращение, Левиафан, Голиаф, порча, наковальни подряд),
  а новые молитвы под карой добавляют ещё. Нужно: один грех (одна молитва-хула или проступок) → одна кара; пока кара идёт,
  следующая молитва не накладывает новую, а усиливает или продлевает текущую; продолжения убрать или сделать частью той же кары.
  Места кар не должны переносить грешника дважды: в тесте его выкинуло во двор церкви («Изыди из храма»), а потом унесло
  к дому («Ступай в дом свой») — одна кара = одно место (`Zones.perform`), переносы не цепляются друг за друга.
- **В план (просьба Дмитрия): деяния бога списком с тумблерами.** Сейчас в админке и F9 выключение — текстовые поля
  `disabled` (id деяний через запятую) и `disabledBosses` (id боссов). Нужно: список всех деяний по видам — кары, блага
  (помощь), шутки — и всех боссов, у каждого русское название, описание (`Miracle`/`BossKind`), группа (`MiracleGroup`) и тумблер;
  «выключить всю группу», поиск. Хранить можно в тех же полях; новый тип настройки (`multi`/набор флагов) в `AdminProtocol`
  Божественного блока и отрисовка в админке Mob Talk и в F9.
- **В план (просьба Дмитрия): режим игры игрока из админки.** Сейчас `gamemode` только читается (`/api/players/positions`).
  Нужно: в карточке игрока на вкладке «Игроки» переключатель выживание / творческий / приключение / наблюдатель (только ADMIN),
  `POST /api/players/{uuid}/gamemode {"mode":"creative"}` → `ServerPlayerEntity.changeGameMode` на серверном потоке, `409` для
  офлайн-игрока (или запомнить и применить при входе), запись `command` в историю.
## 13. Брифы тредов

### S1 «Каркас сервера» — Fable, волна 0, ветка `bigzet/server-skeleton`

**Цель.** Mob Talk становится модом `environment: *`: серверная логика в `main`, контракты раздела 12 существуют в коде,
клиент и сервер собираются, одиночка работает как раньше, выделенный сервер стартует с модом и принимает клиента.

**Шаги.**
1. `fabric.mod.json`: `environment: "*"`, `entrypoints.main = io.github.mobtalk.MobTalkMod`, `client` как есть; миксины разделить:
   `mobtalk.mixins.json` (21 общий) и `mobtalk.client.mixins.json` (`ChatScreenAccessor`, `LivingEntityRendererInvoker`) с `environment: client`.
2. Перенести `src/client/java/io/github/mobtalk/client/game/**` в `src/main/java/io/github/mobtalk/game/**` (git mv, пакет переименовать),
   `DatasetHarvester` оставить в клиенте (он один использует `MinecraftClient`). Проверить, что ни один файл в `game/` не импортирует
   `net.minecraft.client.*`; где импортирует (`MobGameBridge.playerName`, `TradeBridge`), выделить клиентскую часть в клиент.
3. Создать `io.github.mobtalk.net`: все пакеты раздела 12.1 как `record` с `PacketCodec`, регистрация `PayloadTypeRegistry` в `MobTalkMod`
   (C2S и S2C) и `MobTalkClient` (приёмники S2C). Тест кодирования-раскодирования для каждого пакета (`NetCodecTest`).
3а. Выделить интерфейс `GameBridge` (в `game/`) из публичного API `MobGameBridge` (`capture`, `apply`, `giftFromHand`, `openTrade`,
   `witnesses`, `villageAt`, `info`, `event`, `onServer`): `MobGameBridge implements GameBridge`, а `DialogueService` и тесты работают
   с интерфейсом — иначе серверную логику нельзя тестировать без Minecraft (раздел 16, `FakeGameBridge`).
4. Создать `io.github.mobtalk.server`: интерфейсы раздела 12.2 и заглушки `StubTranscription` (возвращает ошибку «не настроено»),
   `StubSpeech` (пустой клип), `AllowAllBudget`, `OpsOnlyPermissions` (операторы — ADMIN, остальные — PLAYER), рабочие `ServerConfig`
   (читает `config/mobtalk-server.json`, при отсутствии — из `mobtalk.json` одиночки, серверные поля по `SettingsScope`), `PlayerSettingsStore`
   (в памяти + `data/mobtalk/players.json`), `SettingsScope` в `core` с тестом «каждое поле `MobTalkConfig` отнесено к слою».
5. `MobTalkMod.onInitialize`: `ServerLifecycleEvents.SERVER_STARTED` → `MobGameBridge.attach(server, store, ServerConfig.get())`,
   `SERVER_STOPPING` → `detach`; `ServerPlayConnectionEvents.JOIN` → отправить `ServerFeaturesS2C`; регистрация приёмников C2S, которые пока
   только логируют и отвечают `TalkStatusS2C("сервер ещё не умеет диалог")`. `Tuning.apply` на сервере — из `ServerConfig`.
6. `MobTalkClient`: ничего из диалога не вырезать (это трек A), но: в одиночке `ensureGameBridge` больше не создаёт мост — его создаёт
   `MobTalkMod`; клиент получает мост через `MobGameBridge.active(client.getServer())` как раньше. При подключении к выделенному серверу
   `client.getServer() == null` — все действия, требующие моста, пишут в чат «на сервере эта часть появится в треке A» вместо NPE.
7. `scripts/run-local-server.ps1` временный минимальный (трек E сделает полный): скачать Fabric server 1.21.1 в
   `%LOCALAPPDATA%\MobTalkPack\server`, положить jar Mob Talk и Fabric API, `eula=true`, `online-mode=false`, запуск. Запустить, убедиться,
   что сервер стартовал без ошибок миксинов и клиент из одноразовой копии инстанса подключился (без Prism-установки в основной инстанс).
8. Обновить `docs/design.md` (новая схема пакетов) и записать в этот документ любые уточнения контракта.

**Владеет.** `fabric.mod.json`, `build.gradle`, `*.mixins.json`, всё под `game/`, новые `net/`, `server/`, `MobTalkMod`, `SettingsScope`,
`docs/design.md`. В `MobTalkClient` — только пункт 6.

**Приёмка.** `gradlew test build` зелёный (313 старых тестов + кодеки + `SettingsScope`); одиночка в одноразовой копии инстанса: диалог
текстом с жителем работает как раньше (самотест `-Only names` или короткий ручной прогон); выделенный сервер стартует с модом, клиент
подключается, в логе сервера `ServerFeaturesS2C` отправлен. Отчёт интегратору: список точек подключения для A, B, C, W.

### D «Бог в мультиплеере» — Opus, волна 0, репозиторий `divine-block`, ветка `bigzet/multiplayer`

**Цель.** Божественный блок корректен при 6 игроках: цели кар и благ — просящий, стример известен, настройки доступны админке.

**Шаги.**
1. Аудит целей: пройти `Miracles`, `Ultra`, `Banishment`, `Curses`, `Zones`, `Protection`, `SkyCourt`, `Aftermath`, `Jests`, `Tornadoes`
   и всё, где `getClosestPlayer`, `world.getPlayers()`, `getPlayerList()`. Правило: **личные** деяния (бан, блага, эффекты, алмазы,
   телепорт домой, проклятия на игрока, суд) — только UUID просящего, респавн просящего (`getSpawnPointPosition` + `getSpawnPointDimension`,
   иначе спавн мира); **мировые** (потоп, Содом, метеоры, казни, смерч) — всех, как сейчас. Для каждого деяния в `docs/punishments.md`
   добавить пометку «личное/мировое». Если просящий вышел с сервера до конца речи бога — личная кара ждёт его входа (`GodLedger.soul` →
   отложенные кары) или отменяется с записью в журнал; выбрать «ждёт до 1 игрового дня».
2. `DivineState.streamer` (UUID) и команда `/divineblock streamer <ник>|clear` (только оператор); промпт бога знает ник стримера и то,
   что просящий — стример или нет. Никаких особых реакций в первой версии.
3. Entrypoint `mobtalk-admin` (раздел 12.3): класс `AdminBridge implements UnaryOperator<String>` в `divine-block`, объявлен в
   `fabric.mod.json` под ключом `"mobtalk-admin"`. `describe` строится из `DivineConfig` (39 полей по группам как в F9), `patch`
   применяет без перезапуска (как уже делает F9), `journal` — вердикты, кары, баны, молитвы из `GodLedger`, `actions` — `unban`, `lift`
   (снять проклятия), `clear`. Без зависимости от Mob Talk: только JDK и Gson (уже в проекте).
4. Разделение настроек: в `DivineConfig` отметить личные поля (голос бога у клиента, громкость, `whisperUrl`, `whisperAutoStart`) —
   они остаются в F9 у игрока, остальное — серверные, в F9 только чтение, если игрок не ADMIN (уровень придёт позже от Mob Talk;
   пока — оператор сервера). Для мультиплеера F9 должен открываться без краша, когда настройки серверные.
5. Тесты: `MiracleTargetsTest` на фейковых игроках (кто получает личную кару, кто — мировую), `AdminBridgeTest` (describe/values/patch/journal
   по JSON), существующие тесты зелёные. Самотест `scripts\run-pack-modtest.ps1 -Mod divine` (в одноразовой копии, без TTS).

**Владеет.** Весь репозиторий `divine-block`, кроме `core/LocalWhisper.java` и `client/VoicePrayer.java` (их меняет трек B).

**Приёмка.** Тесты зелёные, самотест `divine` проходит, `docs/punishments.md` с пометками, `docs/multiplayer.md` в divine-block
(что личное, что мировое, как снять бан).

### E «Сборка и хостинг» — Opus, волна 0, ветка `bigzet/server-hosting`

**Цель.** Один скрипт собирает серверную папку из тех же коммитов, что `build-pack.ps1`; локальный сервер поднимается одной командой;
клиентская раздача — `mrpack`; Docker для VPS.

**Шаги.**
1. `scripts/build-server-pack.ps1`: переиспользовать `Export-Main`/`Build-Repo` из `build-pack.ps1` (вынести общее в
   `scripts/PackBuild.psm1`), собрать `%LOCALAPPDATA%\MobTalkPack\server\mods` из тех же jar + серверные админ-моды (Lithium, FerriteCore,
   Spark, Chunky; скачивать с Modrinth по фиксированным версиям для 1.21.1, записывать в `server-manifest.txt`), `config/` с
   `mobtalk-server.json` по умолчанию, `server.properties` (`online-mode=false` локально, `view-distance=8`, `simulation-distance=6`,
   `max-players=8`, `motd`), `eula.txt`, Fabric server launcher 1.21.1 с фиксированной версией loader.
2. `scripts/run-local-server.ps1 [-Fresh] [-World <имя>]`: запуск сервера с `-Xmx6G`, ключами из окружения (`OPENROUTER_API_KEY`,
   `VENICE_API_KEY`, `ELEVENLABS_API_KEY`, `MOBTALK_ADMIN_TOKEN`; если нет в окружении — прочитать DPAPI-файлы через `OpenRouterToken`
   и передать процессу как переменные, не записывая на диск), лог в окно и файл, `-Fresh` — новый мир. Копия мира «stream 1 (сборка)»
   по флагу `-World`. Отказ, если порт занят.
3. `scripts/new-test-client.ps1 [-Nick <ник>]`: одноразовая копия инстанса `mob-talk-pack` через `New-TestInstance` с офлайн-аккаунтом
   Prism под указанным ником и автоподключением к `127.0.0.1:25565` (`--server`/`--quickPlayMultiplayer`), в счётчик игр через `Enter-GameSlot`.
4. `docker/`: `docker-compose.yml` на `itzg/minecraft-server` (`TYPE=FABRIC`, `VERSION=1.21.1`, `FABRIC_LOADER_VERSION`, `MEMORY=6G`,
   `ONLINE_MODE=true`, `ENABLE_RCON=false`), тома `./data`, `.env.example` с именами переменных без значений, порт 25565 наружу,
   админка 127.0.0.1:8787 только на loopback хоста. `scripts/build-server-pack.ps1 -Docker` раскладывает mods/config в `docker/data`.
   `docker/README.md`: как поднять на Ubuntu, бэкап мира (`tar` по cron), обновление сборки, просмотр логов.
4а. **Timeweb с GPU** (решение Дмитрия, раздел 9): профиль compose `gpu` с контейнером `whisper` (образ на `nvidia/cuda` runtime +
   `faster-whisper`, `tools/whisper_server.py` из репозитория, модель `large-v3` в томе, `deploy: resources: reservations: devices: gpu`),
   доступен серверу Minecraft как `whisper:63919`; переменная `MOBTALK_WHISPER_URL` для `ServerConfig`. Профиль `with-caddy`: Caddy
   с Let's Encrypt для `admin.<домен>`. `deploy/timeweb.sh <IP> <домен>`: с чистой Ubuntu ставит Docker и NVIDIA Container Toolkit,
   копирует `docker/` и бэкап, поднимает compose, проверяет `nvidia-smi` в контейнере и ответ `/health` Whisper, обновляет A- и SRV-записи
   через API Timeweb DNS (токен в `.env`; если API недоступен — печатает, что ввести вручную). `deploy/backup.sh`: `save-off`/`save-all`,
   архив мира, `config/`, `data/mobtalk/`, сертификата → S3 Timeweb или `scp` на компьютер Дмитрия, `save-on`. Репетиция от чистой
   Ubuntu до игры — цель 15 минут, время записать в `docs/server.md`.
5. Клиентская раздача: `scripts/export-mrpack.ps1` — `modrinth.index.json` с нашими jar как файлами внутри (`overrides/mods`) и
   CTOV, Lithostitched, Fabric API, WATERFrAMES, WaterMedia, CreativeCore, WaterVision — ссылками на Modrinth с SHA-512. Проверить импорт
   в Prism в одноразовом инстансе. Инструкция для друзей `docs/friends.md` (пол-страницы: поставить Prism, импортировать файл, микрофон, F7).
6. Проверка: собрать серверную папку с текущими jar (Mob Talk пока клиентский — пропустить его флагом `-NoMobTalk`), поднять сервер,
   подключиться ванильным клиентом из копии инстанса, WATERFrAMES и CTOV на сервере грузятся без ошибок; `docker compose up` на машине
   Дмитрия поднимается (если Docker Desktop есть; иначе отметить в отчёте).

**Владеет.** `deploy/**`, `scripts/build-server-pack.ps1`, `scripts/run-local-server.ps1`, `scripts/new-test-client.ps1`, `scripts/export-mrpack.ps1`,
`scripts/PackBuild.psm1`, `docker/`, `docs/server.md`, `docs/friends.md`, раздел «Сервер» в `docs/pack.md`. `build-pack.ps1` — только вынос общего в модуль.

**Приёмка.** Локальный сервер с текущими jar стартует скриптом, два клиента (основной + копия) подключаются, `mrpack` импортируется в Prism,
`docs/server.md` описывает команды.

### F «Разведка» — Opus, волна 0, ветка `bigzet/server-research`

**Цель.** Ответы, без которых треки B, C, D не могут закончить, в `docs/server-research.md`.

**Шаги.**
1. **Viewer Parrots.** По jar (`javap`, строки, `fabric.mod.json`): корни серверных команд из `command/ViewerParrotCommands`; как
   зрительская команда с Twitch (клиент стримера, `twitch/`, `api/CommandRequest`) доходит до сервера — пакет, команда или только
   на клиенте-хосте; работает ли мод на выделенном сервере вообще (есть `bootstrap/ServerContext`). Рекомендация для C: где фильтровать.
   Исходники в `C:\Users\bigze\Documents\GH\minecraft-parrot` — читать можно, менять нельзя.
2. **OpenRouter STT.** Живая проба (ключ из DPAPI через `OpenRouterToken`, `MOBTALK_LIVE=1`): 8 русских фраз из
   `%LOCALAPPDATA%\MobTalk\selftest-fixtures` (там готовые WAV) на `openai/whisper-1` и 1–2 новых STT-моделях из
   `GET /api/v1/models?output_modalities=transcription`; сравнить с локальным `large-v3` (поднять `tools/whisper_server.py` на свободном порту,
   не 63919, если игра не запущена), записать WER на глаз, задержку, цену за фразу. Рекомендация модели для B и формат (WAV или MP3).
3. **WATERFrAMES на сервере.** Поднять ванильный Fabric-сервер с WATERFrAMES, WaterMedia, CreativeCore, WaterVision, подключиться клиентом,
   поставить экран, запустить YouTube-ссылку. Что нужно на сервере, что на клиенте, нет ли крашей.
4. **Борщевик.** На том же сервере с `rusflora`: мир с лугами, 6 игроков эмулировать нельзя — зато `/forceload` 6 областей по
   `simulation-distance`, `spark profiler` 2 минуты, доля тиков `HogweedBlock`. Если > 5 % тика — предложить и реализовать в `rusflora`
   бюджет блоков за тик (настройка, по умолчанию щадящая), тест.
5. **Боссы.** В `DivineBosses`: допускается ли несколько священных боссов одновременно; если да — предложить D ограничение «один на мир».
6. **Лимиты сети.** Проверить предел `CustomPayload` в 1.21.1 (1 МБ) и размер 15 с аудио в WAV 16 кГц моно (~480 КБ) и MP3 (~120 КБ);
   рекомендация формата для `TalkAudioC2S`.

**Владеет.** `docs/server-research.md`; при необходимости `rusflora` (бюджет тиков, в ветке `bigzet/tick-budget` этого репозитория).

**Приёмка.** Документ с разделом на каждый пункт: факт, как проверен, рекомендация. Пункты 1 и 2 — первыми, они нужны B и C в начале волны 1.

**Итоги F (22.10, подробно в `docs/server-research.md`).**
- Viewer Parrots: корни `viewerparrot` и `vp`. Twitch и панель F8 на выделенном сервере **не работают** (`TwitchChatBridge` создаётся
  только при `!isDedicated()`, F8 зовёт сервисы в своей JVM). Путь «стример входит в Twitch через F8 на клиенте» в разделе 6 без правки
  Viewer Parrots невозможен; предложено: на сервере мост в гостевом режиме (анонимное чтение чата, токен не нужен), канал задаёт админ.
  Дмитрий 22.10 согласился с рекомендацией («Ок» на вариант «гость»); правка — отдельная задача в `minecraft-parrot`. Для C: игрок уровня 0 и так не получает `/vp`; выдать `/vp` стримеру фильтром нельзя, в первой версии `/vp` — ADMIN.
- STT для B: `mistralai/voxtral-mini-transcribe-3-0` по умолчанию, `openai/whisper-large-v3` запасная; `gpt-4o-*-transcribe` не брать
  (меняют язык). Запрос: `input_audio {data, format}`, `language`; ответ `usage.cost` в долларах, оплата целыми секундами.
  Формат `TalkAudioC2S` — WAV 16 кГц моно; свой таймаут 8 с и повтор на запасной модели.
- Сеть (для S1/A, раздел 12.1): 32 767 / 1 048 576 байт — пределы только незарегистрированных каналов; у зарегистрированных
  payload потолок кадра 2 МиБ после сжатия (8 МиБ после распаковки). WAV 15 с (480 КБ) — одним пакетом; клятва 120 с (3,84 МБ) —
  чанками или Opus.
- Боссов одновременно может быть сколько угодно — D ставит «один на мир» в `DivineBosses.spawn`.
- Борщевик: 0,3 % тика при сотне очагов на 6 зон; бюджет тиков не нужен, `rusflora` не менялась.
- WATERFrAMES: на выделенном сервере работает — экран ставится командой, YouTube играет у клиента, позиция ролика общая,
  сервер не нагружается (6,7 мс на тик). Ставить все пять jar и на сервер, и клиентам.

### W «Веб-админка» — два треда, волна 0 → 1: W1 «API, метрики, история» (Opus, ветка `bigzet/admin-api`) и W2 «Интерфейс» (Opus, ветка `bigzet/admin-ui`)

**Цель.** Полный центр управления и отладки сервера внутри мода, с любого устройства после входа: графики и статистика, история всего,
что происходило (диалоги, вердикты бога, расходы, ошибки, изменения настроек), управление игроками, настройками, стримером, whitelist,
банами, консоль команд, резервные копии. Решение Дмитрия: функционал максимальный. Без сборки фронтенда; из внешнего кода только
вендоренная в jar библиотека графиков uPlot (MIT, ~45 КБ, автор Leon Sorokin — записать в credits) — остальное своё.

**Разделение.** W1 делает серверную часть и хранение, W2 — страницу. Контракт между ними — API ниже (раздел «API»), зафиксирован
в `docs/admin-web.md` первым коммитом W1; W2 работает против `DevStubServer` (отдаёт тот же API из JSON-фикстур и генерирует живые
метрики-синусоиды), который W1 коммитит в первый же день. Оба в волне 0 работают по контрактам раздела 12, в волне 1 W1 подключает
живые сервисы.

#### W1 «API, метрики, история» — Opus, `bigzet/admin-api`

1. **HTTPS и вход.** `AdminHttpServer` на `com.sun.net.httpserver.HttpsServer` (адрес и порт из конфига, по умолчанию `0.0.0.0:8787`).
   Самоподписанный сертификат при первом старте через `keytool` JDK (`ProcessBuilder`) в `config/mobtalk-admin.p12`, отпечаток в консоль
   и в `/mobtalk admin web`; на VPS по желанию за Caddy с Let's Encrypt (профиль `with-caddy` в `docker/` трека E). Пароль: `/mobtalk admin web
   password` или сгенерированный при первом старте (печатается один раз); PBKDF2-хэш в `mobtalk-server.json`; cookie сессии HttpOnly+Secure
   на 12 часов; 5 попыток в минуту с IP; журнал входов. Без пароля админка не стартует. `CSP` без внешних источников, `X-Frame-Options: DENY`,
   без CORS. Все `/api/*` требуют сессию.
2. **Метрики** (`server/web/Metrics`): сбор раз в 10 с через `ServerTickEvents`: MSPT (среднее, p95, макс за окно), TPS, память JVM
   (used/max), игроки онлайн, загруженные чанки и сущности по мирам, число жителей с профилем Mob Talk, активных диалогов, глубина очереди
   LLM, запросов в полёте, число борщевика в загруженных чанках (из `rusflora`, если есть, через entrypoint по тому же JSON-протоколу),
   живых боссов и активных проклятий (из Божественного блока через `mobtalk-admin` `{"op":"metrics"}`). Задержки по видам (STT, LLM
   первый токен, LLM целиком, TTS) — гистограммы p50/p95/p99 за окно. Хранение без базы: кольцо 10-секундных точек за 48 часов + 5-минутные
   свёртки за 90 дней в `data/mobtalk/metrics/<день>.bin` (компактный бинарный формат, свой `MetricsFile` с тестом), чтение диапазона
   с прореживанием до запрошенного числа точек.
3. **История** (`server/web/History`): append-only JSONL по дням в `data/mobtalk/history/<вид>/<день>.jsonl` с индексом по времени и игроку,
   срок хранения — настройка (по умолчанию 90 дней). Виды: `dialogue` (игрок, моб, вопрос, ответ, действия, стоимость, токены, задержки
   по шагам, модель, путь распознавания, ошибка), `witness`, `village_talk`, `gift`, `trade`, `relation_change` (кто, с кем, было → стало,
   причина), `rumor`, `verdict` (бог: просящий, молитва, вердикт, кара/благо, сила, ярость), `ban`, `boss`, `player_join/leave`,
   `config_change` (кто — ник или «консоль» или «админка», что, старое → новое), `error` (исключение, стек, контекст, счётчик повторов),
   `command` (кто, команда, результат), `admin_login`. Поиск: по игроку, мобу, виду, тексту (подстрока), диапазону времени, с пагинацией.
   Секреты, ключи и координаты в историю не пишутся (фильтр `Redact` с тестом).
4. **Трассировка диалога**: у каждой записи `dialogue` — шаги с временем (принят → бюджет → контекст → промпт собран → LLM первый токен
   → LLM конец → разбор → действия → TTS → доставлено) и размеры (символы промпта, токены, байты аудио). Полный промпт и сырой ответ
   модели сохраняются при включённом флаге `debug.keepPrompts` (по умолчанию выключен, чтобы история не раздувалась).
5. **API** (все JSON, все за сессией):
   - Настройки: `GET /api/sources`, `GET/PATCH /api/sources/{id}/values`, `POST /api/sources/{id}/actions/{action}`, `GET /api/config/history`,
     `POST /api/config/rollback/{id}` (откат одного изменения).
   - Метрики: `GET /api/metrics?from=&to=&step=&series=mspt,tps,mem,online,...` (прореженный ряд), `GET /api/metrics/now` (последняя точка),
     `GET /api/latency?kind=llm&from=&to=` (гистограммы).
   - История: `GET /api/history?kind=&player=&mob=&q=&from=&to=&cursor=&limit=`, `GET /api/history/{id}` (запись целиком с трассой),
     `GET /api/history/export?...` (CSV или JSONL).
   - Игроки: `GET /api/players` (онлайн и известные: ник, UUID, уровень, стример, онлайн-время, расходы сегодня/всего, диалогов, бан,
     whitelist, личные настройки в режиме чтения), `POST /api/players/{uuid}/level|kick|ban|unban|whitelist`, `POST /api/streamer`.
   - Мобы и деревни: `GET /api/mobs?village=&q=` (профили из `ConversationStore`: имя, вид, характер, отношения по игрокам, сердечки,
     последние события), `GET /api/mobs/{uuid}`, `POST /api/mobs/{uuid}/card` (правка имени/характера/голоса/памяти как `editSelectedCard`),
     `POST /api/mobs/{uuid}/reset|clear-history`, `GET /api/villages` (хроника, слухи, ранги игроков), `POST /api/villages/{id}/forgive/{player}`.
   - Бог: `GET /api/divine/souls` (души: грехи, лестница кар, проклятия, бан), `GET /api/divine/active` (проклятия, боссы), действия через
     источник `divineblock`.
   - Сервер: `GET /api/server` (версия, моды и версии, аптайм, мир, seed скрыт), `POST /api/server/command` (выполнить команду от имени
     консоли, результат и лог — только ADMIN, подтверждение на клиенте), `GET /api/logs?lines=&filter=` (хвост `latest.log` с фильтром
     по модам), `GET /api/errors` (сгруппированные исключения с счётчиками и последним стеком), `POST /api/server/backup` (снимок мира в
     `backups/<время>.zip` без остановки, через `save-off`/`save-all`/`save-on`), `GET /api/backups`, `POST /api/server/reload-config`.
   - События: `GET /api/events` (SSE): новые записи истории всех видов, точка метрик каждые 10 с, вход/выход, изменения настроек, ошибки.
   - Поток живого диалога: `GET /api/live/dialogues` (SSE) — текущие диалоги со статусом шага, для вкладки «Сейчас».
6. **Подключение сервисов** (волна 1): `ServerConfig.update` с записью `config_change`, `PlayerBudget.report`, `Permissions`,
   `DialogueService` (события и трасса), `ConversationStore` (мобы, деревни), источник Божественного блока (`journal`, `metrics`, `actions`),
   `Banishment`, whitelist через `server.getPlayerManager().getWhitelist()`.
7. **Тесты**: `AdminApiTest` (вход, 401/429, каждый эндпоинт на фикстурах, SSE), `MetricsFileTest` (запись/чтение/прореживание/свёртка,
   переживает перезапуск, повреждённый файл не роняет сервер), `HistoryStoreTest` (поиск по всем фильтрам, пагинация, срок хранения,
   экспорт), `RedactTest` (ключи, токены, координаты не попадают в историю), `DialogueTraceTest` (шаги и времена собираются полностью),
   `BackupTest` (zip содержит мир, сервер не остановлен), `CommandEndpointTest` (только ADMIN, результат возвращается).

**Владеет (W1).** `server/web/**` кроме `assets`, `docs/admin-web.md`, `DevStubServer`, тесты API. В `MobTalkMod` — одна строка запуска.

#### W2 «Интерфейс» — Opus, `bigzet/admin-ui`

1. **Основа.** `assets/mobtalk/web/`: `index.html`, `app.css`, `app.js` (модули ES без сборки), `uplot.min.js` + `uplot.min.css`
   (вендорено, версия и лицензия в `credits`). Системный шрифт. Тёмная и светлая тема по системе и переключателем. **Сначала телефон**
   (нижнее меню, крупные элементы, слайдеры с ±), затем планшет и компьютер (боковая панель, две колонки). Клавиатура, фокус, aria.
   Все тексты по-русски. Состояние страницы (выбранная вкладка, фильтры, диапазон времени) в URL-хэше, чтобы ссылку можно было отправить.
2. **Разделы.**
   - **Обзор.** Карточки: онлайн, TPS/MSPT с мини-графиком, память, активные диалоги и очередь, расходы сегодня, ошибки за час.
     Графики за выбранный период (1 ч / 6 ч / 24 ч / 7 д / 30 д): TPS и MSPT, память, онлайн, диалоги в час, расходы по видам,
     задержки p50/p95 по STT/LLM/TTS. Лента последних событий.
   - **Сейчас.** Живые диалоги по SSE: игрок, моб, текущий шаг с таймером, кнопка «отменить». Онлайн-игроки с тем, что они делают
     (говорит, торгует, молится).
   - **Настройки.** Источники (Mob Talk, Божественный блок, другие), группы как в F7/F9, поиск, виджеты по типу, метка «изменено»,
     «Применить»/«Сбросить» на группу, подтверждение опасных. Вкладка **История изменений** с откатом одной кнопкой.
   - **Игроки.** Таблица с сортировкой и поиском; карточка игрока: уровень прав, стример, whitelist, бан, онлайн-время, расходы по дням
     (график), диалоги (переход в историю), отношения с мобами (список с сердечками), личные настройки (чтение). Действия: уровень,
     стример, кик, бан/разбан, whitelist.
   - **Мобы и деревни.** Поиск мобов по имени/виду/деревне, карточка моба: характер, состояние, отношения по игрокам, память, события;
     правка карточки, сброс, очистка истории. Деревни: хроника, слухи, ранги, кнопка «простить игрока».
   - **История.** Единый поиск по всем видам с фильтрами (вид, игрок, моб, текст, период), бесконечная прокрутка, карточка записи
     с трассой диалога (шаги на временной шкале, промпт/ответ при включённом флаге), экспорт CSV/JSONL.
   - **Бог.** Души: грехи, лестница кар, проклятия с таймерами, бан; вердикты с молитвой и карой; живые боссы; действия: снять проклятия,
     разбан, сбросить лестницу; статистика кар по видам за период (график).
   - **Расходы.** По дням, игрокам, видам (STT/LLM/TTS/Jev) и моделям; таблица и графики; лимиты (включение и значения) рядом.
   - **Отладка.** Ошибки (сгруппированные, счётчик, последний стек, кнопка «скопировать»), хвост лога с фильтром по модам и автообновлением,
     задержки по видам (гистограммы), очередь LLM, версии модов, аптайм.
   - **Сервер.** Консоль команд с историей и автодополнением корней, результат в ленте; резервные копии (создать, список, размер);
     перечитать конфиг; информация о сборке (`pack-manifest.txt`).
3. **Качество.** Загрузка страницы ≤ 200 КБ без графиков данных; графики не блокируют интерфейс (uPlot); при обрыве SSE —
   переподключение с индикатором; ошибки API показываются понятно; все опасные действия — подтверждение с текстом последствий.
4. **Тесты.** `AdminWebSmokeTest` (страница отдаётся, все `fetch` только на известные `/api/*`, нет внешних URL); Playwright MCP для
   снимков всех разделов в двух темах и трёх размерах (телефон 390, планшет 820, компьютер 1440) в `docs/admin-web/` — ревью глазами
   Дмитрия в треде W2 до слияния; проверка клавиатурной навигации.

**Владеет (W2).** `assets/mobtalk/web/**`, `docs/admin-web/*.png`, `credits` (строка про uPlot).

**Приёмка W.** Волна 0: W2 работает на `DevStubServer` со всеми разделами, снимки одобрены Дмитрием; W1 — тесты зелёные, API задокументирован.
Волна 1: на локальном сервере графики показывают настоящие TPS и расходы, история содержит диалог с трассой, изменение настройки
действует сразу и видно в истории изменений, команда из консоли выполняется, резервная копия создаётся.

### A «Диалог по сети» — Fable, волна 1, ветка `bigzet/server-dialogue`

**Цель.** Диалог, очевидцы, подарки, торг, состояние и подписи мобов работают на выделенном сервере для нескольких игроков; клиент
только ввод и показ. Самая сложная часть: разрез `MobTalkClient`.

**Шаги.**
1. `DialogueService` (сервер): перенести из `MobTalkClient` методы `submit`, `request`, `ensureEngine`, `deliver`, `showEarly`, `finishDelivery`,
   `queueWitnesses`, `startWitness`, `startSpontaneous`, `playNextWitness`, `keepAttention`, `runDeferredAction`, `reportFailure`, `whisperContext`,
   `endConversation` с заменой «один игрок» на `Session` по UUID игрока: у каждого игрока своя текущая цель, своё поколение запроса, своя
   очередь очевидцев. Общий `ConversationEngine` на моба с блокировкой: два игрока говорят с одним мобом по очереди, второй получает
   `TalkStatusS2C("Житель занят разговором с Димой")`. Приоритеты пула: ответ собеседнику > подарок/реакция > очевидцы > болтовня деревни.
2. Вход: приёмники `TalkStartC2S`, `TalkTextC2S`, `TalkAudioC2S` (→ `TranscriptionService` трека B через интерфейс, пока заглушка —
   отказ), `TalkCancelC2S`, `GiftC2S` (→ `MobGameBridge.giftFromHand`), `MobActionC2S`, `ScreenRequestC2S`. Перед работой — `PlayerBudget.allow`.
3. Выход: `MobSpeechS2C` всем игрокам в `voiceSilentRange` от моба (с `toYou` для собеседника), `SpeechClipS2C` через `SpeechService`
   (заглушка — без звука, клиент играет «тихий тон той же длины» как в самотестах), `MobStateS2C` раз в секунду для мобов в поле зрения
   игрока, которые он знает (как сейчас `showHud`/`MobLabels`, но решает сервер), `ScreenDataS2C` для info/village/deal.
4. Контекст: `MobContextCapture`/`PlaceCapture` на сервере по позиции игрока; снимок экрана — из `TalkTextC2S.picture`, если серверная
   настройка `pictures` включена (по умолчанию выключена на выделенном сервере, включена в одиночке).
5. Торг и сделки: мост уже серверный (`VillagerBargaining`, миксины `MerchantScreenHandler`); проверить «сделка словами» и индикатор торга
   через `ScreenDataS2C("deal")`; окно сделок (`B`) на клиенте читает данные из пакета.
6. Клиент: `MobTalkClient` оставляет микрофон, выбор цели (`aimedMob`, `select`), отправку пакетов, приём и показ; `MobLabels`, HUD,
   `InfoScreen`, `MerchantSession` переводятся на данные из пакетов; все вызовы `client.getServer()` исчезают из клиента, кроме `DatasetHarvester`.
   Одиночка проходит тем же путём через встроенный сервер.
7. Инициатива мобов, болтовня деревни (`InitiativeController`, `VillageTalkController`) — на сервере, адресат — игроки рядом;
   отключаемы серверной настройкой. Ревность, совместный сон, свадьба, семья, особая ночь — **выключены** серверным флагом `features.family=false`
   (код не удалять, трек второй версии).
8. Тесты: `DialogueServiceTest` с фейковым мостом (две сессии, занятый моб, бюджет, отмена при смене мира, отмена при гибели моба),
   старые тесты зелёные. Прогон: локальный сервер (скрипт E) + два клиента (копии инстанса): диалог текстом с одним жителем по очереди,
   второй игрок видит подпись и реплику; подарок по G; торг.

**Владеет.** `MobTalkClient`, `server/DialogueService*`, `server/Sessions*`, `MobLabels`, `InfoScreen`, `MerchantSession`, `InitiativeVoice`,
`VillageTalkVoice`, клиентские приёмники пакетов. Интерфейсы раздела 12 — вправе уточнять с записью в документ.

**Приёмка.** Два клиента на локальном сервере разговаривают с жителем текстом, HUD и подписи у обоих, подарок и торг работают,
одиночка не сломана (самотест `-Only names` и `-Only pack`).

### B «Голос» — Opus, волна 1, ветка `bigzet/server-voice`

**Цель.** Голосовой ввод тремя путями и общая позиционная озвучка на сервере; Божественный блок на тех же путях.

**Шаги.**
1. Клиент: `MicrophoneRecorder` даёт WAV 16 кГц моно; по личной настройке `sttPath` (`local` | `server` | `text`): `local` — как сейчас
   через `WhisperProcess` → `TalkTextC2S(fromVoice=true)`; `server` — сжать в MP3 (`jlayer` уже в jar только декодер; взять кодер по
   рекомендации F или слать WAV, если влезает) → `TalkAudioC2S`; `text` — окно ввода. Разрешённые пути — из `ServerFeatures`;
   если выбранного нет — показать подсказку в F7. Кнопка «Проверить микрофон» (`MicrophoneTestScreen`) работает для всех путей.
2. Сервер `OpenRouterTranscription implements TranscriptionService`: `POST /api/v1/audio/transcriptions`, модель по рекомендации F
   (настройка `sttModel`), подсказки мира (`hints`) в prompt если модель принимает, таймаут `requestTimeoutSeconds`, учёт в `PlayerBudget`
   (`TRANSCRIPTION`, секунды → доллары по цене модели из `/api/v1/models`). Ошибки — понятным текстом в `TalkStatusS2C`.
3. Сервер `OpenRouterSpeech`/`ElevenLabsSpeech implements SpeechService`: вынести из `OpenRouterSpeechClient` и клиентского воспроизведения;
   кэш LRU 200 клипов по (voiceId, text) в памяти + на диске `data/mobtalk/speech-cache`; `broadcast` режет на `SpeechClipS2C` ≤ 512 КБ
   всем в радиусе. Голоса по полу и профили — серверные настройки.
4. Клиент: сборка чанков по `clipId`, воспроизведение от позиции моба (`MobSpeechSound`, `PcmSpeechAudioStream` как сейчас, но источник —
   байты из сети), затухание `voiceFullRange`/`voiceSilentRange`, остановка при гибели моба или выходе из радиуса, личная громкость.
   «Тихий тон той же длины» при пустом клипе остаётся для самотестов.
5. Божественный блок: `LocalWhisper` допускает три пути по той же личной настройке (читает её из `ServerFeatures`/клиентского конфига
   Mob Talk, если Mob Talk есть; иначе как сейчас), `VoicePrayer` для пути `server` шлёт аудио своим пакетом на серверную часть бога,
   которая зовёт тот же `TranscriptionService` Mob Talk через entrypoint `mobtalk-stt` (`Function<byte[], CompletableFuture<String>>`,
   без зависимости от jar). Если Mob Talk нет — локальный путь.
6. Тесты: `OpenRouterTranscriptionTest` (разбор ответа, ошибки, учёт бюджета, фейковый HTTP), `SpeechCacheTest`, `ClipChunkingTest`,
   живая проба `MOBTALK_LIVE=1` на 3 фразах из `selftest-fixtures`. Прогон: два клиента, один `local`, другой `server`; оба слышат озвучку
   (с `-WithTts` один раз, остальное без TTS).

**Владеет.** `server/Transcription*`, `server/Speech*`, `MicrophoneRecorder`, `MicrophoneTestScreen`, `WhisperProcess`, `MobSpeechSound`,
`PcmSpeechAudioStream`, `VoicePreviewService`, `core/OpenRouterSpeechClient`, в divine-block — `LocalWhisper`, `VoicePrayer` и серверный приёмник
молитвы. Вкладка «Голос» в F7 — совместно с C (B — поля, C — разделение слоёв).

**Приёмка.** Голосовой диалог с двух клиентов двумя разными путями, озвучку слышат оба, молитва голосом через сервер работает,
расходы на распознавание видны в `PlayerBudget.report`.

### C «Игроки, права и админ» — Opus, волна 1, ветка `bigzet/server-players`

**Цель.** Личные настройки по игроку, честные имена в слухах, бюджет и лимиты, три уровня прав с фильтром команд всех модов,
команды администратора, F7 с разделением слоёв.

**Шаги.**
1. `PlayerSettings` (record в `core`): обращения трёх списков, пол, `sttPath`, флаги HUD; клиент шлёт `PlayerSettingsC2S` при входе и при
   сохранении F7; сервер хранит в `PlayerSettingsStore`, диалог берёт имена оттуда (`PlayerIdentity.name(settings, nick)`), слухи и очевидцы
   пишут имя из `PlayerIdentity` (проверить `recordDamage`, `recordWitnessed`, `VillageRumors`, что везде идёт ник конкретного игрока).
2. `PlayerBudgetImpl`: учёт расходов по игрокам и видам (`Kind`) из ответов OpenRouter (`usage.cost`) и из настроек для Venice;
   хранение `data/mobtalk/budget.json`, сброс дня по часовому поясу сервера. Лимиты «в минуту» и «в день» на игрока и на сервер —
   **по умолчанию 0 = выключены** (решение Дмитрия), включаются в админке; при включении отказ — вежливая строка для `TalkStatusS2C`.
   Техническая защита всегда: не больше 2 одновременных диалогов на игрока и `maxConcurrentDialogues` на сервер. Админ не ограничен,
   стример — отдельный множитель (настройка).
3. `PermissionsImpl`: ADMIN — операторы и `ServerConfig.admins`; STREAMER — `ServerConfig.streamer`; остальные PLAYER. Фильтр команд:
   миксин в `CommandManager.execute` (или `ServerCommandSource` requirement-обёртка через `CommandRegistrationCallback` после всех модов):
   корни и подкоманды с уровнями из `ServerConfig.commandLevels` (по умолчанию: `mobtalk admin|web|budget` — ADMIN; `divineblock act|boss|unban|ult|clear|lift|cemetery|streamer` — ADMIN;
   `divineblock list|curses` — STREAMER; корни Viewer Parrots по отчёту F — STREAMER, их админские — ADMIN; `mobtalk` прочее — STREAMER).
   Ванильные команды не фильтруются. Отказ — «Эта команда доступна стримеру и администратору».
4. `/mobtalk admin`: `streamer <ник>|clear`, `admin add|remove <ник>`, `budget show|set <игрок|all> <вид> <в минуту> <в день>`,
   `model <id>`, `feature <имя> on|off`, `web` (адрес админки), `reload`; все через `ServerConfig.update` — админка видит изменения по SSE.
5. F7 (`MobTalkSettingsScreen`, `SettingsController`): вкладки строятся по `SettingsScope`: личные поля правятся; серверные показываются
   серо с подписью «серверная настройка, меняется админом», для ADMIN на выделенном сервере — правятся и уходят `/mobtalk admin set`
   (одна команда `set <поле> <значение>`); в одиночке всё как раньше. `ServerFeatures` показывает лимиты и расходы игрока за сегодня.
6. Тесты: `PlayerBudgetTest` (окна, сброс дня, стример-множитель), `PermissionsTest` (уровни, фильтр корней), `PlayerIdentityTest` на двух
   игроков с разными обращениями, `SettingsScopeTest` уже от S1. Прогон на локальном сервере: игрок без прав получает отказ на
   `/divineblock list`, стример — нет; второй игрок упирается в лимит «в минуту».

**Владеет.** `server/PlayerBudget*`, `server/Permissions*`, `server/AdminCommands*`, `server/PlayerSettings*`, `core/PlayerSettings`,
`core/PlayerIdentity`, `MobTalkCommands`, `MobTalkSettingsScreen`, `SettingsController`, `settings/*`, `assets/mobtalk/lang/ru_ru.json`
(новые ключи — добавлять в конец, чтобы не конфликтовать с W).

**Приёмка.** Оператор видит расходы и лимиты (`/mobtalk admin budget show`), игрок без прав получает отказ на команду мода, жители зовут
двух игроков разными именами, F7 показывает серверные настройки серыми.

### G «Самотест сервера» — Opus, волна 2, ветка `bigzet/server-selftest`

**Цель.** `run-selftest.ps1 -Only server` без рук: сервер + два клиента, сценарии, `ALL CHECKS PASSED`.

**Шаги.**
1. Этап `server` в `run-selftest.ps1`: поднять сервер скриптом E в одноразовой папке с копией мира сборки, два клиента через
   `new-test-client.ps1` (ники `Тест1`, `Тест2`), слот игр через `Enter-GameSlot`, Whisper не грузить (пути `text`), TTS выключен.
2. Клиентский `SelfTestServer` (как `SelfTestPack`): клиент 1 говорит с жителем текстом, ждёт `MobSpeechS2C`; клиент 2 проверяет, что
   получил ту же реплику и `MobStateS2C`; подарок по G у клиента 2 — реакция у обоих; торг; молитва текстом у алтаря — вердикт только
   просящему; команда `/divineblock list` у клиента без прав — отказ; админ-страница отвечает на `GET /api/sources` с токеном.
   Снимки `mobtalk-server-*.png` обоих окон.
3. Сторож зависаний и очистка копий как у остальных этапов; серверный лог в `%LOCALAPPDATA%\MobTalk\selftest\<копия>\server.log`.

**Владеет.** `scripts/run-selftest.ps1` (этап `server`), `client/SelfTestServer.java`, `docs/parallel-selftest.md` (раздел про сервер).

**Приёмка.** `[selftest] ALL CHECKS PASSED` на этапе `server`, время ≤ 10 минут.

### H «Интеграция и VPS» — Fable, волна 2, ветка `main`

**Цель.** Всё слито, собрано, проверено, развёрнуто.

**Шаги.** Слияние по порядку раздела 11 с тестами после каждого; `build-pack.ps1` и `build-server-pack.ps1`; самотесты `-Only pack`,
`-Only server`, полный; `HANDOFF.md` и `pack.md` обновлены. Развёртывание на Timeweb (решение Дмитрия 22 октября, раздел 9):
1. Репетиция на домашнем компьютере или дешёвом VPS без GPU: `deploy/timeweb.sh` от чистой Ubuntu до работающего сервера, замер времени
   (цель ≤ 15 минут), восстановление мира из бэкапа, `backup.sh` обратно.
2. Аренда сервера Дмитрием за 2–3 часа до игры: `deploy/timeweb.sh <IP>`, проверка `nvidia-smi` в контейнере Whisper, DNS A и SRV на домен,
   админка по `https://admin.<домен>`, ключи в `.env`, whitelist друзей, `online-mode=true`.
3. Дымовая проверка: Дмитрий и один друг с `mrpack`: голос через `server-local`, озвучка, подарок, молитва, админка показывает TPS и
   диалог в истории; расход GPU и MSPT при двух игроках записать в `docs/server.md`.
4. Игра. После игры — `backup.sh`, выгрузка истории и метрик админки (они внутри бэкапа), отключение сервера. Итог в `docs/server.md`:
   часы, рубли, доллары OpenRouter, ошибки.

**Приёмка.** Первая игра с друзьями на Timeweb прошла; мир и история сохранены бэкапом и восстановлены при следующей аренде;
расходы за вечер видны в админке; HANDOFF описывает серверную сборку и порядок аренды.

## 14. Назначение агентов и названия тредов

| Тред (название в проекте) | Модель | Ветка | Старт |
| --- | --- | --- | --- |
| Каркас сервера Mob Talk | Fable | `bigzet/server-skeleton` | сейчас |
| Бог в мультиплеере | Opus | divine-block `bigzet/multiplayer` | сейчас |
| Сборка сервера, Docker и mrpack | Opus | `bigzet/server-hosting` | сейчас |
| Разведка: Viewer Parrots, STT, видео, борщевик | Opus | `bigzet/server-research` | сейчас |
| Админка: API, метрики, история | Opus | `bigzet/admin-api` | сейчас, продолжает в волне 1 |
| Админка: интерфейс | Opus | `bigzet/admin-ui` | сейчас, против заглушки W1 |
| Диалог по сети | Fable | `bigzet/server-dialogue` | после слияния S1 |
| Голос на сервере | Opus | `bigzet/server-voice` | после слияния S1 и отчёта F по STT |
| Игроки, права и админ | Opus | `bigzet/server-players` | после слияния S1 и отчёта F по Viewer Parrots |
| Самотест сервера | Opus | `bigzet/server-selftest` | после слияния A, B, C, W |
| Интеграция и VPS | Fable | `main` | после G |

Бриф каждому треду — соответствующий подраздел раздела 13 плюс разделы 0, 2, 12 этого документа и правила 12.4. Если `start_session`
отвечает «turn runs an automated event», запускать через `Agent` (Opus, в фоне), агент сам делает `git worktree add`.

## 15. Инструменты разработки (MCP) — согласовать и поставить до старта

Что уже есть у тредов: GitKraken (git), Desktop Commander (файлы, процессы), встроенный браузер, ripgrep, Gradle через консоль.
Индексации кода нет. Кандидаты, по убыванию пользы для этого плана:

| MCP | Зачем | Экономия токенов | Рекомендация |
| --- | --- | --- | --- |
| **Serena** (oraios/serena) | Языковой сервер Java (Eclipse JDT): найти символ, все вызовы, переименовать, прочитать одно тело метода вместо файла в 1400 строк. Критично для трека A (разрез `MobTalkClient`) и S1 (перенос пакетов). | Большая: чтение по символам вместо целых файлов, точечные правки | **Ставить.** `uv` есть. Запись в `.mcp.json` репозитория ниже; первый запуск качает JDT, 2–5 минут. Проект нужно один раз «активировать» (`onboarding`), результат ляжет в `.serena/` (добавить в `.gitignore`) |
| **Context7** (upstash) | Актуальная документация библиотек по запросу: Fabric API (`CustomPayload`, `PayloadTypeRegistry`, Loom), JDK `HttpsServer`, Docker-образ itzg. | Средняя: вместо поиска в браузере и чтения страниц целиком | Ставить, дёшево: `npx -y @upstash/context7-mcp`. Нужен Node (проверить) |
| **Playwright MCP** (microsoft/playwright-mcp) | Треку W: открыть админку, кликнуть, снять снимки в тёмной/светлой теме и на размере телефона, проверить консоль. | Средняя: снимки вместо описаний, меньше итераций «поправь — посмотри» | По желанию: встроенный браузер уже умеет почти то же; Playwright точнее на размерах экрана и автоматизируется в тестах |
| **GitHub MCP** (официальный) | Issue/PR, если проект начнёт жить на GitHub. Сейчас коммиты только локально. | Нет | Пока не нужен |
| Filesystem, Fetch, Memory, Sequential Thinking | Дублируют встроенные инструменты и память проекта. | Нет | Не ставить |

Для Gradle, Minecraft и Fabric зрелых MCP нет: сборка и тесты остаются в консоли. Экономят токены также правила: тред читает только
свой бриф и разделы 0, 2, 12; длинные логи сборки фильтруются (`| Where-Object { $_ -match 'error|FAIL' }`, как в `build-pack.ps1`);
самотесты пишут итог одной строкой.

`.mcp.json` в корне репозитория (решение Дмитрия: все три; файл закоммичен в `mob-talk` и `divine-block` 22 октября):

```json
{
  "mcpServers": {
    "serena": { "command": "uvx", "args": ["--from", "git+https://github.com/oraios/serena", "serena", "start-mcp-server", "--context", "claude-code", "--project", "."] },
    "context7": { "command": "npx", "args": ["-y", "@upstash/context7-mcp"] },
    "playwright": { "command": "npx", "args": ["-y", "@playwright/mcp@latest"] }
  }
}
```

Замечания: Claude Code при первом старте в папке спросит разрешение на MCP из `.mcp.json` проекта — ответить «да»; Serena
индексирует каждый worktree отдельно (`--project .`), первая активация 2–5 минут, её кэш `.serena/` в `.gitignore`; Playwright при
первом вызове скачает браузер (~150 МБ).

## 16. План тестирования

Принцип: серверная логика тестируется без Minecraft через `GameBridge`-фейк и фейковый HTTP; сеть — тестами кодеков и `DialogueService`
с двумя фейковыми сессиями; игра — самотестом с сервером и двумя клиентами. Каждый трек добавляет свои тесты и не ломает чужие;
полный прогон `gradlew test` после каждого слияния — обязанность интегратора. Пропуск теста допустим только с `@Disabled("причина, трек, дата")`.

### 16.1 Существующие тесты (313, `src/test/java/io/github/mobtalk`)

- Все остаются зелёными. После переноса `game/` в `main` тесты ядра не меняются (ядро без Minecraft).
- `MobTalkConfigTest`, `TunablesTest` дополняются проверкой `SettingsScope`: каждое поле отнесено к слою, личные поля не попадают в
  `ServerConfig`, серверные — в `PlayerSettings`.
- Живые пробы `LiveProbeTest`, `SocialLiveProbeTest` (`MOBTALK_LIVE=1`) — без изменений; добавляется `TranscriptionLiveProbeTest`.

### 16.2 Тесты по трекам

| Трек | Тест | Что проверяет |
| --- | --- | --- |
| S1 | `NetCodecTest` | каждый пакет 12.1: кодирование → раскодирование даёт равную запись; границы (пустой `picture`, аудио 900 КБ, `clipId=0`); отказ на пакете > 1 МБ |
| S1 | `SettingsScopeTest` | все поля `MobTalkConfig` классифицированы; личный список совпадает с разделом 12.2; `ServerConfig.get()` не содержит личных значений |
| S1 | `ServerConfigTest` | чтение/запись `mobtalk-server.json`, миграция из `mobtalk.json` одиночки, слушатели вызываются при `update`, неизменяемость снимка |
| S1 | `GameBridgeContractTest` | `FakeGameBridge` и `MobGameBridge` реализуют один интерфейс; фейк возвращает заданные `MobSnapshot`/`Outcome` |
| D | `MiracleTargetsTest` | для каждого деяния из каталога: личное бьёт только просящего (двое фейковых игроков), мировое — обоих; телепорт — на респавн просящего; бан — только просящий в `UserBanList` |
| D | `DeferredWrathTest` | просящий вышел до конца речи — личная кара ждёт его входа до 1 игрового дня, потом списывается с записью в журнал |
| D | `AdminBridgeTest` | `describe` перечисляет все 39 полей `DivineConfig` с типами и границами; `patch` применяет и отвергает выход за границы; `journal` отдаёт последние события; `actions` → `unban` снимает бан |
| D | `StreamerPromptTest` | промпт бога содержит ник стримера и признак «просящий — стример» только когда он назначен |
| E | `scripts/tests/build-server-pack.Tests.ps1` (Pester) | манифест сервера содержит те же коммиты, что `pack-manifest.txt`; админ-моды с ожидаемыми версиями и SHA; `server.properties` локальный/публичный; `-NoMobTalk` пропускает jar |
| E | `scripts/tests/export-mrpack.Tests.ps1` | `modrinth.index.json` валиден по схеме; CTOV и Lithostitched только ссылками с SHA-512; наши jar в `overrides/mods` |
| F | `docs/server-research.md` | не тесты, а протоколы проб: таблица STT (модель, WER на 8 фразах, задержка, цена), профиль Spark борщевика, лог WATERFrAMES на сервере |
| F | `rusflora` `HogweedTickBudgetTest` (если бюджет понадобился) | за тик не больше N блоков, очередь не теряет блоки, настройка в границах |
| W | `AdminApiTest` | без сессии 401 на все `/api/*`; вход с неверным паролем → 401, шестая попытка за минуту → 429; с сессией `sources/values/patch/journal/costs/players`; `PATCH` отвергает неизвестный ключ и выход за границы; SSE отдаёт событие после `patch`; админка не стартует без пароля; HTTPS с сгенерированным сертификатом, HTTP отвергается; cookie HttpOnly+Secure |
| W1 | `AdminSourceTest` | `MobTalkAdminSource.describe` = все серверные поля `Tunables` с русскими названиями из `ru_ru.json`, ни одного личного; порядок групп как в F7 |
| W1 | `MetricsFileTest` | запись/чтение/прореживание 10-секундных точек, 5-минутные свёртки, переживает перезапуск, повреждённый файл не роняет сервер |
| W1 | `HistoryStoreTest` | поиск по виду, игроку, мобу, тексту, периоду; пагинация курсором; срок хранения удаляет старые дни; экспорт CSV/JSONL |
| W1 | `RedactTest` | ключи, токены, координаты и пароли не попадают ни в одну запись истории и ни в один ответ API |
| W1 | `DialogueTraceTest` | трасса диалога содержит все шаги с временами и размерами; при `debug.keepPrompts=false` промпт не сохраняется |
| W1 | `BackupTest`, `CommandEndpointTest` | резервная копия содержит мир и не останавливает сервер; команда выполняется только у ADMIN, результат возвращается |
| W2 | `AdminWebSmokeTest` | страница отдаётся, в HTML нет ключей и путей, все `fetch` идут на известные `/api/*`, внешних URL нет |
| W2 | снимки Playwright | `docs/admin-web/*.png` всех десяти разделов в двух темах и трёх размерах экрана с заглушки — ревью глазами Дмитрия до слияния |
| A | `DialogueServiceTest` | две сессии с `FakeGameBridge` и фейковым `ConversationEngine`: ответ уходит собеседнику с `toYou=true` и соседу с `false`; второй игрок к занятому мобу получает «занят»; отмена при смене мира/гибели моба/выходе игрока; приоритеты очереди (ответ раньше очевидцев); бюджет отказал → `TalkStatusS2C` с причиной, LLM не вызван |
| A | `WitnessBroadcastTest` | очевидцы выбираются вокруг говорящего моба, реплика очевидца приходит всем в радиусе, cooldown по мобу |
| A | `MobStateSyncTest` | `MobStateS2C` уходит только игрокам, знающим моба, не чаще раза в секунду, при изменении состояния |
| A | `SinglePlayerPathTest` | в одиночке (встроенный сервер) путь через пакеты даёт тот же результат, что раньше: регрессия `ConversationDeliveryTest` |
| B | `OpenRouterTranscriptionTest` | фейковый HTTP: разбор `text` и `usage.cost`, таймаут, 4xx/5xx → понятная ошибка, учёт секунд в бюджете, подсказки мира попадают в запрос |
| B | `ServerLocalWhisperTest` | путь `server-local`: запуск/перезапуск helper, недоступен → переключение на следующий разрешённый путь |
| B | `SpeechCacheTest` | один синтез на (голос, текст), LRU 200, диск переживает перезапуск, промах при смене голоса |
| B | `ClipChunkingTest` | клип режется на чанки ≤ 512 КБ, собирается в исходные байты, потеря чанка → клип отброшен без краша |
| B | `SttPathSelectionTest` | личный `sttPath` ∩ разрешённые сервером → фактический путь; подсказка при несовпадении |
| B | `TranscriptionLiveProbeTest` (`MOBTALK_LIVE=1`) | 3 фразы из `selftest-fixtures` распознаются выбранной моделью с совпадением ключевых слов |
| C | `PlayerBudgetTest` | окна «в минуту»/«в день» на игрока и сервер, сброс по часовому поясу, множитель стримера, админ без лимита, персистентность `budget.json` |
| C | `PermissionsTest` | уровни по операторам/`admins`/`streamer`; таблица корней по умолчанию; ванильные команды не фильтруются; неизвестный корень мода — ADMIN |
| C | `CommandFilterTest` | фейковый `CommandDispatcher`: игрок → отказ с текстом, стример → проходит `divineblock list`, админ → всё |
| C | `PlayerIdentityTest` | два игрока с разными обращениями: слухи и очевидцы пишут ник конкретного игрока; промпт мобу — обращения этого игрока по тону |
| C | `PlayerSettingsStoreTest` | хранение по UUID, замена при повторном входе, устаревшие поля игнорируются |
| C | `AdminCommandsTest` | каждая подкоманда `/mobtalk admin` меняет `ServerConfig` и вызывает слушателей; `set` отвергает личные поля |
| G | этап самотеста `server` | см. 16.4 |

### 16.3 Интеграционные прогоны без полной игры

- `gradlew runServer` (Loom) с `-Dmobtalk.selftest.server=true`: сервер стартует, грузит мир-заглушку, `ServerLifecycleEvents` поднимают
  мост, `DialogueService` с `StubTranscription` отвечает на пакет от фейкового соединения — проверка миксинов и регистрации пакетов на
  реальном сервере без клиента. Запускает трек S1, дальше — интегратор после каждого слияния.
- Божественный блок: `run-pack-modtest.ps1 -Mod divine` в одноразовой копии (как сейчас).

### 16.4 Самотест в игре (трек G)

`run-selftest.ps1 -Only server`: сервер + клиенты `Тест1` (стример), `Тест2` (игрок), оператор — консоль сервера. Шаги и проверки:

| Шаг | Проверка |
| --- | --- |
| Старт | сервер и оба клиента в мире, `ServerFeaturesS2C` получен обоими (уровни STREAMER и PLAYER), админка отвечает `GET /api/sources` |
| Диалог | `Тест1` говорит с жителем текстом → `MobSpeechS2C` у обоих, `toYou` только у `Тест1`; подпись и HUD у обоих; журнал админки получил запись |
| Занятый моб | `Тест2` обращается к тому же жителю во время ответа → «занят» |
| Подарок | `Тест2` дарит по G → реакция у обоих, отношение выросло у `Тест2`, у `Тест1` без изменений |
| Торг | окно сделки у `Тест2`, индикатор торга, сделка словами |
| Имена | `Тест2` бьёт жителя → слух с ником `Тест2`; `Тест1` спрашивает «кто обижал» → в ответе `Тест2` |
| Лимит | 4 быстрых реплики `Тест2` при лимите 3 в минуту → отказ с причиной |
| Права | `/divineblock list` у `Тест2` — отказ, у `Тест1` — список; `/mobtalk admin` у обоих — отказ |
| Бог | молитва текстом `Тест2` у алтаря → вердикт и личная кара только у `Тест2`; мировая кара (`/divineblock act` оператором) — у обоих |
| Голос без Whisper | путь `text` у обоих, `TalkAudioC2S` с фикстурой → `StubTranscription`/OpenRouter (с `MOBTALK_LIVE`) → текст |
| Отмена | `Тест1` уходит в другой мир во время ответа → ответ не доставлен, статус сброшен |
| Выход | `Тест2` выходит с сервера во время диалога → сессия закрыта, сервер без ошибок, `Тест1` продолжает |
| Финал | снимки обоих окон, `server.log` без `ERROR` от наших модов, `ALL CHECKS PASSED` |

Остальные этапы самотеста (`pack`, `names`, `looks`, `ctov`, `voicefix`) продолжают работать в одиночке тем же серверным кодом; после
волны 1 известные FAIL разбираются на серверной версии (решение раздела 0).

### 16.5 Ручная проверка Дмитрием перед первой игрой

Голос с микрофона всеми путями; озвучка слышна соседу и затухает с расстоянием; админка меняет лимит без перезапуска; стример с
командами Viewer Parrots; бан и разбан; подключение друга снаружи по белому IP или к VPS.

## 17. Открытые вопросы к Дмитрию

Отвечено 22 октября: стример — любой игрок, Twitch через меню на его клиенте; хост — VPS или домашний компьютер по цене; распознавание —
локально у игрока или OpenRouter, GPU на сервере не предполагается; озвучка через OpenRouter по умолчанию, Venice — альтернатива;
админка — веб с авторизацией с любого устройства; whitelist по никам; MCP согласовать до старта.

Отвечено 22 октября, вечер: **денежные лимиты по умолчанию выключены** (Дмитрий платит и контролирует сам) — `PlayerBudget` считает
расходы и показывает их в админке, лимиты «в минуту» и «в день» равны 0 = без ограничения, включаются в админке; защита от
бесконечной очереди остаётся техническая (не больше 2 одновременных диалогов на игрока). Модель диалога по умолчанию `openai/gpt-6-luna`,
меняется в админке без перезапуска. MCP ставим все три: Serena, Context7, Playwright (`.mcp.json` в обоих репозиториях).

Открытых вопросов нет; старт волны 0 — по слову Дмитрия.

## 18. Риски

- Разрез `MobTalkClient` (трек A) — самый большой и последовательный кусок; его нельзя распараллелить, поэтому S1 должен лечь первым и быстро.
- Предел payload 1 МБ: без сжатия 15 с WAV проходит, но на границе; решение F по формату нужно до начала B.
- Качество OpenRouter STT на русском может оказаться ниже локального `large-v3` — тогда для стримера локальный helper, для друзей сервер.
- Viewer Parrots — чужой jar; если команды зрителей не доходят до выделенного сервера вовсе, фильтр не нужен, но попугаи стримера могут не работать — F выяснит.
- Два мода в одной JVM общаются только через entrypoint с JSON-строками (12.3) и `Function` (B п. 5): просто, но без типов — тесты на JSON обязательны.
- Админка на `0.0.0.0` без туннеля — риск; по умолчанию loopback, предупреждение в логе при смене.
