#!/bin/bash
# Установщик Mob Talk для игрока (macOS 12+): ставит Prism Launcher в ~/Applications, если его нет, и импортирует нашу
# сборку (файл .mrpack рядом со скриптом, иначе последний из релизов github.com/BigZet/mob-talk). Сервер уже в списке
# «Сетевая игра». Пароль администратора не нужен. Уже установленный Prism используется как есть.
# Запуск: двойной щелчок по install-macos.command (при первом разе: правый щелчок -> Открыть),
# или в Терминале: bash install-macos.command [файл.mrpack]
set -euo pipefail

PRISM_VERSION="11.1.1"
PRISM_FILE="PrismLauncher-macOS-${PRISM_VERSION}.zip"
PRISM_SHA256="15126a7df57acb4f37fe581dad68cb03c7bb60b70eb82345f8bb3246235600c6"
PRISM_URL="https://github.com/PrismLauncher/PrismLauncher/releases/download/${PRISM_VERSION}/${PRISM_FILE}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d -t mobtalk-install)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo; echo "Ошибка: $*"; echo "Нажмите Enter, чтобы закрыть."; read -r _ || true; exit 1; }

# Скачивает $1 в $2 и сверяет SHA-256 с $3 (пустой $3 — без проверки).
fetch() {
  echo "Скачиваю $1"
  curl -fL --retry 3 -A "BigZet/mob-talk installer" -o "$2" "$1" || fail "не удалось скачать $1"
  if [ -n "${3:-}" ]; then
    local got
    got="$(shasum -a 256 "$2" | cut -d' ' -f1)"
    [ "$got" = "$3" ] || fail "файл $1 пришёл повреждённым (не та контрольная сумма), попробуйте ещё раз"
  fi
}

# 1. Prism: уже установленный или новый в ~/Applications.
APP=""
for candidate in "/Applications/Prism Launcher.app" "$HOME/Applications/Prism Launcher.app"; do
  if [ -d "$candidate" ]; then APP="$candidate"; break; fi
done
if [ -n "$APP" ]; then
  echo "Prism Launcher уже есть: $APP"
else
  fetch "$PRISM_URL" "$WORK/$PRISM_FILE" "$PRISM_SHA256"
  mkdir -p "$HOME/Applications"
  ditto -x -k "$WORK/$PRISM_FILE" "$WORK/prism" || fail "не удалось распаковать Prism"
  FOUND="$(find "$WORK/prism" -maxdepth 2 -name 'Prism Launcher.app' -type d | head -n 1)"
  [ -n "$FOUND" ] || fail "в архиве Prism нет Prism Launcher.app"
  APP="$HOME/Applications/Prism Launcher.app"
  ditto "$FOUND" "$APP"
  echo "Prism Launcher $PRISM_VERSION установлен: $APP"
fi

# 2. Сборка: аргумент, .mrpack рядом со скриптом или последний релиз с GitHub.
PACK="${1:-}"
if [ -z "$PACK" ]; then
  PACK="$(ls -t "$HERE"/*.mrpack 2>/dev/null | head -n 1 || true)"
fi
if [ -z "$PACK" ]; then
  API="https://api.github.com/repos/BigZet/mob-talk/releases/latest"
  JSON="$(curl -fsSL -A "BigZet/mob-talk installer" "$API")" || fail "не удалось узнать последний релиз Mob Talk"
  # Первая ссылка на .mrpack и её SHA-256 (поле digest), без jq и python: JavaScript для автоматизации есть на любом Mac.
  echo "$JSON" > "$WORK/release.json"
  read -r NAME URL SHA < <(osascript -l JavaScript -e '
    function run(argv) {
      var text = $.NSString.stringWithContentsOfFileEncodingError(argv[0], $.NSUTF8StringEncoding, null).js;
      var a = JSON.parse(text).assets.filter(function (x) { return /\.mrpack$/.test(x.name); })[0];
      return a ? [a.name, a.browser_download_url, (a.digest || "").replace(/^sha256:/, "")].join(" ") : "";
    }' "$WORK/release.json") || true
  PACK="$WORK/${NAME:-none}"
  [ -n "$URL" ] || fail "в последнем релизе Mob Talk нет файла .mrpack: попросите его у Дмитрия и положите рядом с установщиком"
  # Временная папка удаляется при выходе, а Prism читает файл позже: кладём сборку в Загрузки.
  fetch "$URL" "$PACK" "$SHA"
  mkdir -p "$HOME/Downloads"
  mv -f "$PACK" "$HOME/Downloads/"
  PACK="$HOME/Downloads/$(basename "$PACK")"
fi
[ -f "$PACK" ] || fail "нет файла сборки $PACK"
echo "Сборка: $PACK"

# 3. Импорт: Prism откроет окно добавления сборки с этим файлом, остаётся нажать OK.
# nohup: Prism не закроется вместе с окном Терминала.
nohup "$APP/Contents/MacOS/prismlauncher" --import "$PACK" >/dev/null 2>&1 &
disown || true
cat <<'TEXT'

Prism Launcher открывается. Дальше:
  1. В окне импорта нажмите OK — Prism сам скачает Minecraft, Fabric и моды.
  2. Справа вверху: Аккаунты -> Управление аккаунтами -> Добавить Microsoft, войдите своим аккаунтом Minecraft.
  3. Выберите сборку Mob Talk -> Запустить. Если Prism спросит про Java, согласитесь скачать (Java 21).
  4. Сетевая игра -> сервер Mob Talk уже в списке. Ваш ник должен быть в списке сервера: напишите его Дмитрию.
  Микрофон: macOS спросит разрешение для Java при первом разговоре — разрешите.

Это окно можно закрыть.
TEXT
