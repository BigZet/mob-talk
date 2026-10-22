# Трек B «Голос»: что сделано и как подключать

Ветки: `bigzet/server-voice` в mob-talk и в divine-block. Не запушены. План — `docs/server-plan.md`, разделы 3, 12 и 13 (бриф B).

## Распознавание

- Клиент выбирает путь через `VoiceInput.route(config)`. Это личный `sttPath` (`auto | local | server | text`), пересечённый
  с `ServerFeatures.sttPaths` и проверкой, установлен ли Whisper (`SttRoute`). В режиме `auto` порядок `local → server → text`.
  Если выбранный путь недоступен, `Choice.hint` объясняет, что взято вместо него.
- Путь `server`: `VoiceInput.sendAudio(mobId, wav)` шлёт `TalkAudioC2S` с WAV 16 кГц одним пакетом (15 с = 480 КБ).
  Формат взят по разведке F, кодировщик не нужен.
- Сервер: `VoiceIntake.receive(player, payload, onText)`. Проверяет запись, пропускает только одну запись игрока за раз,
  шлёт статус «Сервер распознаёт речь…», затем `MobTalkMod.transcription()`. Ошибка уходит игроку в `TalkStatusS2C.error`.
  Готовый текст возвращается в `onText` на потоке сервера. `mobId < 0` означает проверку микрофона: ответ приходит
  в `ScreenDataS2C("stt-test")`.
- `Transcriptions`: при `sttServerLocal` сначала работает Whisper самого сервера (`ServerWhisperTranscription`, общий
  `core/WhisperHelper`). Если он не сработал, а `sttServer` включён, запрос уходит в OpenRouter.
- `OpenRouterTranscription`: `PlayerBudget.allow` перед запросом и `charge(TRANSCRIPTION, usage.cost, секунды)` после.
  Модель по умолчанию `mistralai/voxtral-mini-transcribe-3-0`, запасная `openai/whisper-large-v3`. Ожидание —
  `sttTimeoutSeconds` (8 с), потом один повтор на запасной модели. Ключ, деньги и 403 не повторяются.
- Серверные настройки: `sttModel`, `sttFallbackModel`, `sttServer` (вкл.), `sttServerLocal` (выкл.), `sttTimeoutSeconds`.
  Личная: `sttPath`.
- Подсказки мира (`hints`) в OpenRouter не уходят: поле `prompt` не проверено. Whisper сервера получает `whisperHotwords`.

## Озвучка

- `SpeechService.stream(world, source, range, SpeechStream)` → `clipId`. Это растущая озвучка из `ConversationEngine`
  (`Reply.speech()`, реплики очевидцев). Сервер шлёт чанки по мере скачивания (`ClipSlicer`, от 0,2 с), поэтому задержка почти
  как в одиночке. Чанк с `count = 0` значит «будут ещё», последний чанк несёт настоящее `count`.
- `synthesize(voiceId, text)` озвучивает целиком через `SpeechVoicer`. Кэш `SpeechCache`: LRU 200 клипов плюс
  `data/mobtalk/speech-cache` мира. `broadcast(world, source, range, clip)` шлёт клип всем в радиусе.
- Формат — сырой PCM 24 кГц `pcm24k`, около 48 КБ/с на слушателя. Слушатели — игроки в `range` в момент начала.
  Если моб пропал, скачивание закрывается.
- Клиент: `SpeechClips.take(clipId)` возвращает `SpeechStream`, который можно сразу играть. Чанки могут прийти до или после
  `MobSpeechS2C`. Потерянный чанк обрывает клип без исключений.
- Голос мобов играет в звуковой категории «Голос/речь» (VOICE), а не NEUTRAL. Так личная громкость — это обычный ползунок
  в настройках звука игры.

## Точки подключения для трека A (диалог)

1. Приёмник `TalkAudioC2S` в `MobTalkMod.registerReceivers`: вместо `notYet` в `onText` вызвать тот же путь, что
   `TalkTextC2S(fromVoice = true)`.
2. Выдача реплики на сервере: `int clip = MobTalkMod.speech().stream(world, mob, config.voiceSilentRange, reply.speech())`,
   затем `MobSpeechS2C(..., clipId = clip, ...)` всем в радиусе. Для очевидцев и болтовни деревни — то же самое.
3. Приёмник `MobSpeechS2C` на клиенте: `owner.startVoice(payload.mob(), SpeechClips.take(payload.clipId()), !payload.toYou())`.
4. `MobTalkClient.finishRecording`: `VoiceInput.route(config)`.
   - `server` → `VoiceInput.sendAudio(mobId, wav)`, статус придёт с сервера.
   - `local` → как сейчас.
   - `text` → окно ввода.
   - Показать `hint`, если он не пустой.
5. В приёмнике `ScreenDataS2C` (`ClientNet`) оставить первую строку `if (VoiceInput.screenData(payload)) return;`.
6. `ServerFeatures.dialogue` трек A ставит сам: поля `transcription`, `speech`, `sttPaths` уже заполняет `VoiceServices`.

## Для трека C и интегратора

- Вкладка «Голос» в F7: поле `sttPath` (циклическая кнопка из `SttRoute.CLIENT_PATHS` плюс `auto`, ключи
  `mobtalk.voice.path.*` уже есть в lang) и подсказка из `VoiceInput.route(config).hint()`. Серверные поля `stt*` — по
  разделению слоёв C.
- Расход озвучки (`PlayerBudget.Kind.SPEECH`) не списывается: ответ TTS не содержит цену, а `SpeechService` не знает игрока.
- В `fabric.mod.json` объявлены `mobtalk-stt` (`Function<byte[], CompletableFuture<String>>`) и `mobtalk-stt-player`
  (`BiFunction<UUID, byte[], …>`). При слиянии с W1 рядом встанет `mobtalk-admin`.
- Lang-ключи трека B добавлены в конец `ru_ru.json` и `en_us.json`.

## Божественный блок

- `PrayerVoicePath` читает `sttPath` из `config/mobtalk.json`. Сервер принимает молитву голосом, только если у клиента
  `canSend(PrayAudioPayload)`. Приёмник регистрируется лишь при наличии entrypoint Mob Talk.
- `ServerPrayerVoice` распознаёт молитву через `mobtalk-stt-player` и передаёт текст в `Prayers.handle`. Без Mob Talk
  всё работает как раньше, локально.
- Проверка микрофона в настройках бога тоже идёт через сервер; ответ приходит строкой над панелью.

## Проверено

- `gradlew test build` в mob-talk: 415 тестов, 0 упавших, 3 пропущены (живые пробы).
- `gradlew test build` в divine-block: 42 теста, 0 упавших.
- Живая проба `MOBTALK_LIVE=1 TranscriptionLiveProbeTest`: 3 фразы, voxtral, $0,00087, слова совпали.
- Выделенный сервер без клиента (`runServer -PmobtalkSelftest=true`, порт 25611, потому что 25599 был занят другим треком):
  ALL CHECKS PASSED, обе службы голоса подключены.
- В игре и с микрофоном не проверено: два клиента с путями `local` и `server` — после трека A (самотест G).

## Не сделано

- Клятва на свадьбе (до 120 с, 3,8 МБ) не влезает в один пакет: путь `server` отказывает, если запись длиннее 15 с.
  Нужны чанки или Opus (Concentus). Это вторая версия.
- Голос из F7 («Прослушать») на выделенном сервере по-прежнему идёт с ключом клиента (`VoicePreviewService`).
