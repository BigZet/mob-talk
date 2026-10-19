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

## Услуги (задания), которые придумывает сам моб

Пользователь 19 октября 2026: «Задания должны генерироваться нейросетью и заключаться в том, что нужно что-то принести.
Зачем это мобу — придумывает нейросеть.» Готовых квестов в каталоге больше нет; код только проверяет предмет и количество.

- Ответ модели: `quest: {type: NONE|OFFER|COMPLETE, item: "minecraft:…", count: 1..64, title: "…", rewardDeal: id|"", rewardPercent: 0..50}`.
  OFFER — моб просит принести `count` предметов `item` (настоящий id предмета Minecraft 1.21.1; игровой слой подставляет
  проверку реестра через `Trading.itemChecker`), `title` — короткое название услуги (до 60 символов), причина — в самой реплике.
  `rewardDeal` — какую из своих сделок моб обещает за услугу: если она секретная и закрыта — откроется, если `rewardPercent` > 0 —
  цена снизится на этот процент (не ниже пола). Код всегда добавляет +25 к отношению и считает услугу в `favors`.
- Одна активная услуга на вид и игрока (общая для всех особей вида — деревня знает, что колокол уже несут/принесли).
  Сдать можно любой особи вида: при COMPLETE код проверяет инвентарь (`have ≥ count`), забирает предметы, применяет награду.
- Каталог: `species.<id>.ideas = [{item, count, why}]` — только подсказки для модели («можешь взять идею или придумать свою,
  в характере»); `deal.requiresFavor: true` — секретная сделка открывается только после хотя бы одной выполненной услуги.
- Состояние (`ConversationStore`, у каждого моба своё для каждого игрока, в `Relation`): `favor` (активная услуга), `favorsDone` (названия), открытые секреты — флаг `unlocked` в `TradeState`. Соседи по деревне получают `favor.neighbours` — строки-слухи о долгах игрока другим жителям (19 октября 2026: общие для вида услуги размывали личность жителей).
- Вид модели (`mobDetails.trading.quests`): `{active: {item, itemName, count, have, canComplete, title, reward}, done: [titles], favors: n, ideas: [...], canOffer: bool}`.
- Промпт: просить услугу только когда игрок сам предлагает помощь, спрашивает, что нужно, или добивается секретного; не чаще
  раза за разговор; никаких дежурных концовок («по рукам?»).

## Игровой слой (клиент/integrated server)

- `mobDetails.trading` = `Trading.view(...)` в `MobContextCapture` для каждого моба; `inventory` — счёт предметов игрока по id, `itemNames` — локализованные имена.
- Применение намерений (`MobGameBridge.applyWithRevision`): deal → `Trading.permitted` → `store.setDealPrice`; unlock → `Trading.canUnlock` → `store.unlockDeal`; quest OFFER → `store.startQuest`; quest COMPLETE → проверка инвентаря/прогресса → изъятие → `store.completeQuest`. Невалидное намерение — `IllegalStateException` с текстом, как у торга жителя (пользователь видит `[Mob Talk] …`).
- Покупка — только в **ванильном окне торговца** (клавиша по умолчанию `B`, `/mobtalk trade`; у жителя ПКМ по-прежнему открывает окно профессии, `B` — личные сделки). На сервере `MobMerchant` (реализация `Merchant` без сущности) строит `TradeOffer` из `Trading.priced`: цена игрока через `setSpecialPrice` (исходная зачёркнута), `maxUses` = дневной остаток, обмен делает ванильный `MerchantScreenHandler`, `trade()` → `store.recordPurchase`. Закрытые секретные — отключённые строки `???` (барьер) с подсказкой в lore, активный квест — отключённая строка-бумага «Задание: … (a из b)». Окно живёт, пока моб жив и в радиусе разговора. Торг возможен при открытом окне: `MobContextCapture` добавляет в `trading` поля `windowOpen` и `selectedDeal` (id выбранной строки), а принятый OFFER/unlock/квест тут же перестраивает строки (`updateOffers` + `sendTradeOffers`), как в `VillagerBargaining.refresh`.
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

## Трек C: торг и отношение (19 октября 2026)

Три дополнения поверх существующей системы сделок; новая логика в новых классах, в общих файлах только точки подключения.

### Сделка словами — настоящая строка в окне жителя

- Раньше торг с жителем шёл только при открытом окне (`VillagerBargaining`, overlay цены на выбранной строке). Теперь при закрытом окне `VillagerBargaining.capture` добавляет в `mobDetails.bargaining` блок `spoken` (строит `TradeBridge.capture`): `available`, `offers[] = {index, key, firstBuy{…count=currentPrice}, secondBuy, sell, currentPrice, allowedPrices, minimumPrice, canOffer, promised}`, `active = {offerIndex, price, rounds, minutesLeft}` (если обещание уже стоит), `allowedTypes` (NONE/OFFER/DECLINE; OFFER только когда есть строка с canOffer), `reason`.
- Правила — `core/SpokenOffer` (чистая Java): цена ниже текущей для игрока (постоянная уступка `Bargaining.currentPrice` учитывается), за шаг не больше чем вдвое, не ниже `Bargaining.floorPrice`; одно обещание на жителя, до трёх понижений одного обещания, другая строка заменяет его; срок `LIFETIME_TICKS` = 12 000 тиков (10 игровых минут) от последнего OFFER; `validate` превращает ответ модели в `BargainIntent(OFFER, index, discount)`, где `discount` считается от `currentPrice` строки; `honestPrices` — числа, которые житель может назвать вслух; `summary` — сводка для пользовательского сообщения.
- Промпт: `MobPrompt.bargainingRules` выбирает `PromptTexts.SPOKEN_BARGAINING_RULES`, `villagerSummary` — `SpokenOffer.summary`; `TradingView.permittedBargainTypes` берёт `spoken.allowedTypes`, так что схема ответа сужается сама; `AnswerValidator.validateBargain` при закрытом окне делегирует `SpokenOffer.validate`, подсказки исправлений — `SpokenOffer.rejection`.
- Игра (`client/game/TradeBridge`, только серверный поток): `apply` сверяет строку с тем, что видела модель (key, currentPrice), строит `TradeOffer(TradedItem(тот же предмет, price), secondBuy, sell.copy(), uses 0, maxUses 1, опыт строки, multiplier 0, demand 0)` и добавляет в `villager.getOffers()` последней строкой; `tick` снимает по истечении/смерти/выключении и держит строку последней (после level-up ванильные строки дописываются после неё — ванильные индексы сессий `Bargaining` не сдвигаются); `beforeSave/afterSave` (миксин `SpokenOfferSaveMixin` на `writeCustomDataToNbt`) убирают строку на время записи NBT — в мир она не попадает; `unload/close` — при выгрузке и остановке сервера. `MerchantTradeMixin` → `TradeBridge.traded`: покупка по нашей строке → `ConversationStore.recordTradeDone` (+5 с кулдауном минута, событие PURCHASE в состоянии, запись «купил по договорённости …»), строка снимается следующим тиком. `MobGameBridge.applyWithRevision` ветвит по `TradeBridge.spoken(observed)`: снимок с закрытым окном → `trades.apply`, иначе `bargaining.apply`.
- Не сделано: вариант «до конца разговора + 2 минуты» (сервер не знает о конце разговора — срок фиксированный); при превращении жителя в зомби обещанная строка может уйти в NBT зомби-жителя (снимается тиком при смерти, но конверсия в тот же тик — не проверено); для не-жителей (коровы и т.п.) остаётся прежний механизм `DealIntent` + окно `MobMerchant`.

### Индикатор хода торга в HUD

- `core/BargainMeter.read(Move, MobState.View, relationship)` → `Reading(Verdict LEANING|UNSURE|AGAINST, score)`. Очки: ход моба OFFER/ACCEPT +35, DECLINE −35; щедрость (доля пути от текущей цены к минимальной) до +15; число игрока: ниже минимума −20, моб его встретил +15, в пределах +5; доверие (trust−50)·0.5, настроение (mood−50)·0.2, отношение ·0.25, щедрое состояние +10. Порог ±25 включительно. `refusesDeals()` или отношение ≤ HATRED — всегда AGAINST.
- `BargainMeter.move(bargain, deal, snapshot, playerText)` читает ход из ответа и снимка: строка открытого окна (vanillaCount/minimumPrice, учтена постоянная цена), строка `spoken`, своя сделка (`currentPrice/minPrice`); число игрока — последнее число в распознанной речи (`AnswerValidator.spokenNumbers`).
- Клиент: `MobTalkClient.finishDelivery` сохраняет `Move` на 2400 тиков; `showHud` пересчитывает слово при каждом показе (состояние дрейфует) и выводит «торг: склоняется/не уверен/против» зелёным/жёлтым/красным; смена собеседника сбрасывает. Настройка `MobTalkConfig.showBargainHud` (по умолчанию включено), переключатель в F7 → «Общее», lang-ключи `mobtalk.bargain.*`, `mobtalk.settings.bargainHud`.

### Подозрительность за проступки без ударов

- `core/Mischief`: `Kind` {CONTAINER −3, CROP −3, WORKSTATION −8, DOOR −5, BED −5} с текстами памяти («открыл чужой сундук в деревне») и для чата игрока («открыли …»); `Ledger` — поставленные игроком блоки за сессию (`placed/ownBlock`, свой и соседний блок — для второй половины двери/кровати) и кулдаун `COOLDOWN_MILLIS` = 20 с на (игрок, вид).
- `MobState.Event.MISCHIEF(-5, -10, +10, -15, не медленное)`: подозрение, не страх. `ConversationStore.recordWitnessed(mob, type, name, player, playerName, seen, delta, felt, now)` — перегрузка с созданием профиля (деревня помнит и незнакомца) и явным событием; старая сигнатура делегирует.
- `client/game/MischiefWatch` (серверный поток): `UseBlockCallback` (ПКМ основной рукой по `ChestBlock`/`BarrelBlock`, не крадучись с предметом) и `PlayerBlockBreakEvents.AFTER` (рабочий блок — по памяти `JOB_SITE` жителей в 48 блоках либо по набору блоков профессий; кровать — `BlockTags.BEDS` + хозяин по `HOME`; дверь — `BlockTags.DOORS`; урожай — `BlockTags.CROPS`/`CropBlock`). Очевидцы — жители и железные големы в 16 блоках от блока, видящие игрока; хозяин рабочего блока/кровати — всегда. Деревня: `ServerWorld.isNearOccupiedPointOfInterest(pos, 2)` (две секции от занятого POI), кроме случая с хозяином. Миксин `BlockPlacementMixin` на `BlockItem.place` отмечает поставленные блоки (семена тоже). Игроку в чат серая строка «[Mob Talk] Марфа видел, как вы открыли чужой сундук в деревне».
- Промпт: предложение в `PromptTexts.RULES` — проступок повод для подозрения и колкости, не для войны.
- Ограничения: учёт своих блоков живёт только в памяти сессии (после перезахода свой сундук в деревне снова «чужой»); наковальня не блок профессии и не считается; свидетели — только жители и големы.

## Где что лежит после рефакторинга (19 октября 2026)

- Промпт: `MobPrompt` (сборка), `PromptTexts` (тексты правил и исправлений), `TradingView` (чтение данных снимка).
- Ответ: `ReplySchema` (схема), `AnswerValidator` (проверки и подсказки для исправлений), `OpenRouterConversationClient` (транспорт и цикл исправлений).
- Истории: `BiographyWriter`; память: `ConversationStore` (операции), `WorldMemoryFile` (файл), `ProfileDefaults` (рождение профиля), `MemoryNotes` (факты).
