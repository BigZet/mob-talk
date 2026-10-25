# Казино в деревне

Задел на будущее: здание, мебель, охрана и посетители. Из игр пока работает только 21 (ветка «Очко»), остальное — заглушки.

## Здание
- Шаблоны `data/mobtalk/structure/casino/{plains,desert,savanna,snowy,taiga}.nbt`, генерирует `tools/casino/building.py`
  (`python3 -I tools/casino/building.py plan OUT.png` — план сверху).
- `CasinoWorldgen` при старте сервера добавляет казино в пулы `minecraft:village/<вид>/houses` с весом `casinoWeight`
  (у обычных домов 2–4). Джигсо `building_entrance` у двери, как у ванильных домов; при постройке превращается в красный ковёр.
- Внутри: 5 автоматов, рулетка, стол для 21 с местом дилера, карточный стол, бар с полкой и стульями, люстры,
  два столбика с бархатным канатом у входа. Стены из материалов деревни.
- Лимит «одно казино на деревню»: подключить `UniqueVillagePieces.register(...)` из ветки баров, когда она вольётся.

## Мебель
`tools/casino/decor.py` генерирует текстуры, модели, blockstate, лут и названия (`preview OUT.png` — рендер моделей).
Модели свои (подходящих MIT/GPL моделей казино не нашлось). Большие столы занимают несколько клеток:
невидимые блоки `casino_part` передают клик и поломку главному блоку.

| Блок | Что делает |
|---|---|
| `casino_blackjack_table` | 21: житель рядом садится сдавать, иначе зовётся ближайший; открывает `/mobtalk cards open`, если игра есть в сборке |
| `casino_roulette_table`, `casino_poker_table` | пока «закрыто» |
| `casino_slot_machine` | крутит барабаны в строке действия, играет на интерес |
| `casino_chair`, `casino_bar_stool` | на них садятся игрок и жители |
| `casino_rope` | рабочее место вышибалы (POI) |

## Вышибалы
Профессия `mobtalk:casino_bouncer` (чёрный костюм, тёмные очки, наушник; у людей MCA — одежда
`human/clothing/neutral/casino_bouncer`). Здоровье `bouncerHealth`, урон `bouncerDamage`, броня, не паникуют
(`CasinoBouncerPanicMixin`). Бьют монстров рядом и выгоняют игрока, который ударил жителя или ломает казино (2 минуты).

## Посетители
Днём и вечером жители садятся за столы и к автоматам (`casinoPlayersPerTable`), сидят 1–3 минуты и уходят.

## Настройки (F7, группа «Казино»)
`casino`, `casinoBouncers`, `casinoVisitors`, `casinoWeight`, `bouncerHealth`, `bouncerDamage`, `casinoPlayersPerTable`.

## Самотест
`scripts\run-selftest.ps1 -Only casino` (`-Dmobtalk.selftest.only=casino`): ставит пять зданий, проверяет план, пулы,
вышибал, посетителя, автомат, стол 21 и драку; скриншоты 01–14.
