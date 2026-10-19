# Дообучение Whisper под голос игрока

Конвейер превращает записи стримов (OBS, `~/Videos/*.mp4`) в датасет «голос игрока → текст»,
дообучает Whisper (LoRA) и кладёт модель CTranslate2 туда, где её найдёт `whisper_server.py`.
Результаты, цифры и как включить модель в игре — в [docs/whisper-finetune.md](../../docs/whisper-finetune.md).

Аудио, датасет и чекпойнты лежат в рабочей папке (`%LOCALAPPDATA%\MobTalk\train` или
`MOBTALK_TRAIN_DIR`) и в git не попадают. Обучение идёт в отдельном venv
`%LOCALAPPDATA%\MobTalk\venv-train`; venv и сервер Whisper мода не трогаются.

## Запуск целиком

```powershell
pwsh scripts/train-whisper.ps1
```

Части: `-From train -To convert`, другая база: `-Base openai/whisper-medium -Name mobtalk-medium-ru`,
без установки в мод: `-NoInstall`.

## Шаги

| Шаг | Скрипт | Что делает |
|---|---|---|
| extract | `extract.py` | mp4 → WAV 16 кГц моно; только оригиналы (без `tg`/`telegram`/`highlights`) |
| segment | `segment.py` | Silero VAD → фрагменты 1–15 с (`clips.jsonl`) |
| label | `label.py` | псевдоразметка large-v3 (beam 5, подсказка-глоссарий имён и терминов) |
| speaker | `speaker.py` | теги по чат-логу Minecraft («Вы: …» — игрок, реплики мобов — TTS) + ECAPA-эмбеддинги + логистическая регрессия: оценка «это голос игрока» |
| dataset | `build_dataset.py` | фильтры (голос, TTS-текст, уверенность, галлюцинации, темп речи), нормализация имён, train/val 90/10 блоками по 2 мин |
| train | `train.py` | LoRA r=32 на q/k/v/out/fc1/fc2 энкодера и декодера, lr 3e-4, 4 эпохи, batch 8×2, fp16, gradient checkpointing, WER до/после |
| convert | `convert.py` | слияние LoRA → CTranslate2 `int8_float16` → `models/<name>` мода |
| evaluate | `evaluate.py` | WER/CER/имена/задержка так, как распознаёт сервер (beam 5, VAD, hotwords); `--server URL` — через HTTP |

## Проверка через второй сервер (не трогая рабочий)

CUDA-библиотеки в venv-train лежат внутри torch, поэтому серверу нужен `torch\lib` в `PATH`:

```powershell
$venv = "$env:LOCALAPPDATA\MobTalk\venv-train"
$env:PATH = "$venv\Lib\site-packages\torch\lib;$env:PATH"
& "$venv\Scripts\python.exe" tools/whisper_server.py --port 63920 --model mobtalk-small-ru --model-dir <папка models мода>
# в другом окне: переключает сервер через POST /configure (как мод) и меряет каждую модель
& "$venv\Scripts\python.exe" tools/whisper-train/evaluate.py --server http://127.0.0.1:63920 --configure mobtalk-small-ru large-v3 large-v3-turbo small
```

Hotwords отправляйте только так (UTF-8 из Python): `curl` из Git Bash портит кириллицу в `?????`, и WER резко растёт.
