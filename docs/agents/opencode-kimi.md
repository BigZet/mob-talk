# Запуск Kimi через opencode на этом репозитории

## Один раз

1. Поставить opencode (`npm i -g opencode-ai` или установщик с https://opencode.ai) и войти в провайдера:
   `opencode auth login` → Moonshot AI (ключ platform.moonshot.ai) или OpenRouter (тот же ключ, что у мода).
2. Сделать Kimi отдельную рабочую папку, чтобы её `git checkout` не мешал Claude:
   ```powershell
   cd C:\Users\bigze\Documents\GH\mob-talk
   git fetch origin
   git worktree add ..\mob-talk-kimi -b kimi/<задача> origin/main
   ```
3. Запускать opencode в этой папке: `cd ..\mob-talk-kimi; opencode`. В `/models` выбрать Kimi (K2 или новее).

opencode сам читает AGENTS.md, а `opencode.json` в корне добавляет docs/agents/project-rules.md и
docs/agents/coordination.md, подключает MCP Context7 (документация Fabric/Minecraft API) и спрашивает
разрешения на `git push`, `rebase`, `reset --hard`, удаление папок и завершение процессов. Serena и
Playwright выключены (`"enabled": false`), включите при надобности.

## Каждая новая задача

```powershell
git -C C:\Users\bigze\Documents\GH\mob-talk-kimi fetch origin
git -C C:\Users\bigze\Documents\GH\mob-talk-kimi switch -c kimi/<новая-задача> origin/main
```

Первое сообщение агенту, например:

> Задача: <что сделать>. Работай только в ветке kimi/<задача>. Перед началом прочитай AGENTS.md и
> docs/agents/coordination.md, проверь, что задача не пересекается с ветками claude/*. Сделай, прогони
> `.\gradlew.bat test build`, закоммить. Не пушь, пока я не скажу; потом открой PR в main.

Когда работа готова: `git push -u origin kimi/<задача>` и PR в `main`. Слияние — после вашего ОК.
