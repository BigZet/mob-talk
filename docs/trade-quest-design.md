# Mob Talk 0.3: живые личности, торговля с любым мобом, секретные сделки, квесты

Цель (слова пользователя): «мобы ведут себя как роботы, чья задача торговать. Нужна глобальная доработка:
торговаться можно с любым мобом, и то, что он продаёт, зависит от самого моба. В результате диалога можно открыть
секретные сделки, которые моб не показывает в начале. Если моб снизил цену — она не возвращается после первой сделки,
и дальше её ещё можно понизить. Отдельно система квестов: выполнил квест — улучшил отношения, открыл новые сделки или
снизил текущую цену.»

## Архитектура (контракты уже в main, не менять без согласования)

Core (`src/main/java/io/github/mobtalk/core`, чистая Java, без Minecraft):

- `TradeDeal` — сделка вида: `id, name, sell{id,name,count}, price{id,name,count}, secret, hint, unlockRelationship, unlockQuest, maxPerDay`; `floorPrice()` = max(1, price.count/10).
- `QuestDefinition` — квест вида: `id, title, kind (BRING|KILL), target (item id | entity id), count, pitch, reward{relationship, unlockDeal, discountDeal, discountPercent}`.
- `TradeCatalog` — загрузчик `assets/mobtalk/trades.json`: `deals(type)`, `quests(type)`, `deal(type,id)`, `quest(type,id)`, `tradingSpecies()`. Валидирует ссылки unlockQuest/unlockDeal/discountDeal, уникальность id, формат item id.
- `TradeState(price, unlocked, rounds, sessionStartMillis, boughtToday, boughtDay)` — состояние игрока по сделке; `QuestState(status ACTIVE|DONE, progress, startedMillis)`.
- `DealIntent(type NONE|OFFER|DECLINE, dealId, price)`, `QuestIntent(type NONE|OFFER|COMPLETE, questId)`, `unlock: String` (id секретной сделки или "") — намерения модели. `BargainIntent` остаётся для ванильного окна жителя.
- `Trading` — чистые правила и `view(...)` (форма `mobDetails.trading` описана в javadoc класса; это общий контракт LLM-клиента и игрового слоя). Сейчас заглушки `UnsupportedOperationException` — реализует core-агент.
- `ConversationStore` — новые методы-заглушки (`tradeState, tradeStates, setDealPrice, unlockDeal, recordPurchase, quests, startQuest, recordKill, completeQuest`) — реализует core-агент; сигнатуры фиксированы.
- `OpenRouterConversationClient.Answer` и `ConversationEngine.Reply` уже несут `deal, unlock, quest` (по умолчанию NONE/"").
- `SpeciesCatalog.Species` получил `quirks, desire, secret` (необязательные строки из species.json).

## Правила цен (одинаковы для всех видов)

- Один OFFER снижает цену не больше чем вдвое от текущей (половина округляется вниз — в пользу покупателя).
- Все шаги вместе — не ниже `floorPrice` (10% исходного количества).
- Цена, на которую моб согласился, **постоянна** для этого игрока и этого моба: не возвращается после покупки, не истекает, может только снижаться дальше.
- Сессия торга: 3 OFFER подряд, затем 20 минут (реального времени, `Trading.SESSION_MILLIS`) до новой сессии.
- Отношение ≤ −40 (`Trading.HOSTILE_RELATIONSHIP`) — уступок нет.
- `maxPerDay` > 0 — столько обменов в игровой день (`worldDay` = время мира / 24000), 0 — без ограничений.

## Секретные сделки

- `secret:true` — не показывается в экране сделок и не входит в `deals` для модели, пока не открыта.
- Модель видит её как `secret[]` с `hint` и условиями (`unlockRelationship`, `unlockQuest`) и `canUnlockNow`. Моб может намекать, набивать цену, требовать услугу.
- Поле ответа `unlock` = id сделки открывает её, когда `canUnlockNow` (отношение ≥ порога и квест выполнен, если задан). Код проверяет; необоснованный unlock отбрасывается с исправлением модели.
- Награда квеста `unlockDeal` открывает сделку напрямую.

## Квесты

- Один ACTIVE квест на моба и игрока. Модель предлагает `quest{type:OFFER,id}` из `quests.available` — квест сразу становится ACTIVE (игрок согласился словами или моб навязал).
- `quest{type:COMPLETE,id}` — моб считает, что игрок выполнил. Код проверяет: BRING — у игрока в инвентаре ≥ count предметов `target`, они изымаются; KILL — `progress ≥ count` (прогресс считает `recordKill` по смерти сущностей от руки игрока). После проверки `completeQuest` применяет награду.
- Награда: `relationship` (+N к отношению, clamp как обычно), `unlockDeal`, `discountDeal` + `discountPercent` (снижает текущую цену сделки на процент, не ниже floorPrice; постоянно).

## Игровой слой (клиент/integrated server)

- `mobDetails.trading` = `Trading.view(...)` в `MobContextCapture` для каждого моба; `inventory` — счёт предметов игрока по id, `itemNames` — локализованные имена.
- Применение намерений (`MobGameBridge.applyWithRevision`): deal → `Trading.permitted` → `store.setDealPrice`; unlock → `Trading.canUnlock` → `store.unlockDeal`; quest OFFER → `store.startQuest`; quest COMPLETE → проверка инвентаря/прогресса → изъятие → `store.completeQuest`. Невалидное намерение — `IllegalStateException` с текстом, как у торга жителя (пользователь видит `[Mob Talk] …`).
- Покупка — только явным кликом в экране сделок `MobTradeScreen` (клавиша по умолчанию `B`, `/mobtalk trade`), на сервере: проверка и изъятие `price`, выдача `sell`, `store.recordPurchase`. Экран показывает открытые сделки с текущей ценой, заблокированные секретные — как «???» с подсказкой-тизером, и активный квест с прогрессом.
- Житель: ванильное окно и `Bargaining`/`VillagerBargaining` сохраняются, но уступка становится постоянной (без истечения, без CONSUMED после оплаты, дальнейшее снижение возможно в новой сессии до floor).
- `recordKill` вызывается из `AFTER_DEATH`, когда атакующий — игрок.

## Личность («душа»)

- species.json: у каждого вида `quirks` (речевые привычки, фирменные словечки, что его бесит/смешит), `desire` (чего он хочет от жизни — личная цель, которую он преследует и о которой говорит), `secret` (что скрывает и выдаёт только другу при отношении ≥ 60).
- Промпт: личность и собственная жизнь — в начале; торговля — только когда игрок о ней заговорил или мобу выгодно. Настроение дня: код выводит из состояния (здоровье, погода, время, голод игрока, недавние события) короткую строку «сейчас ты…».
- Запрещено звучать как продавец: без «чем могу помочь», без перечисления ассортимента без повода.

## Формат trades.json

```json
{
  "schemaVersion": 1,
  "species": {
    "minecraft:cow": {
      "deals": [
        {"id": "milk", "name": "Ведро молока", "sell": {"id": "minecraft:milk_bucket", "name": "Ведро молока", "count": 1},
         "price": {"id": "minecraft:wheat", "name": "Пшеница", "count": 6}, "secret": false, "hint": "",
         "unlockRelationship": 0, "unlockQuest": "", "maxPerDay": 0},
        {"id": "leather", "name": "Кожа с подружки", "sell": {"id": "minecraft:leather", "name": "Кожа", "count": 3},
         "price": {"id": "minecraft:emerald", "name": "Изумруд", "count": 2}, "secret": true,
         "hint": "Знает, где лежит кожа пропавшей подруги, но говорит об этом только своим", "unlockRelationship": 40,
         "unlockQuest": "fence", "maxPerDay": 1}
      ],
      "quests": [
        {"id": "fence", "title": "Почини загон", "kind": "BRING", "target": "minecraft:oak_fence", "count": 8,
         "pitch": "Крипер снёс половину загона, принеси восемь секций забора — иначе волки доберутся до телят",
         "reward": {"relationship": 30, "unlockDeal": "leather", "discountDeal": "milk", "discountPercent": 25}}
      ]
    }
  }
}
```

Правила контента: 2–4 сделки на вид (1–2 из них секретные), 1–2 квеста; товар и цена — из того, что реально связано с
мобом в Minecraft 1.21.1 (дроп, среда, профессия, лор); валюта не обязательно изумруды (пиглин — золото, зомби — гнилая плоть,
пчела — цветы). Только существующие vanilla id предметов и сущностей. Имена по-русски. `name` сделки — как моб её называет.

## Владение файлами (параллельная работа)

| Агент | Файлы |
|---|---|
| core | `core/Trading.java`, новые методы `core/ConversationStore.java` (+ поля Profile/Relation, копирование, миграция), `core/TradeCatalog*`, новые тесты `TradingTest`, `TradeCatalogTest`, дополнения `ConversationStoreTest` |
| llm | `core/OpenRouterConversationClient.java`, `core/ConversationEngine.java`, `test/ConversationEngineTest.java` |
| game | `client/game/*` (кроме mixin-accessor'ов без нужды), `client/MobTalkClient.java`, новый `client/MobTradeScreen.java`, `core/Bargaining.java` + `BargainingTest`, lang-файлы, `mobtalk.client.mixins.json` |
| content | `assets/mobtalk/trades.json`, `assets/mobtalk/species.json` (поля quirks/desire/secret), `SpeciesCatalogTest` (проверка новых полей) |
