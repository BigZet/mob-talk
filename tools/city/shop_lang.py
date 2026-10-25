"""The supermarket's texts (ru, en) merged into the mod's lang files: python3 -I tools/city/shop_lang.py"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "mobtalk", "lang")

T = {
    "block.mobtalk.shop_shelf": ("Стеллаж-горка", "Gondola shelf"),
    "block.mobtalk.shop_bread_rack": ("Хлебный стеллаж", "Bread rack"),
    "block.mobtalk.shop_fridge": ("Холодильная горка", "Refrigerated case"),
    "block.mobtalk.shop_freezer": ("Морозильный ларь", "Chest freezer"),
    "block.mobtalk.shop_produce": ("Овощной прилавок", "Produce stand"),
    "block.mobtalk.cash_register": ("Касса", "Till"),
    "block.mobtalk.self_checkout": ("Касса самообслуживания", "Self-checkout"),
    "block.mobtalk.basket_stack": ("Стопка корзин", "Stack of baskets"),
    "block.mobtalk.theft_gate": ("Антикражная рамка", "Anti-theft gate"),
    "block.mobtalk.shop_guard_desk": ("Пост охраны", "Guard's desk"),
    "item.mobtalk.shopping_basket": ("Корзина покупателя", "Shopping basket"),
    "entity.minecraft.villager.mobtalk.cashier": ("Кассир", "Cashier"),
    "entity.minecraft.villager.mobtalk.shop_guard": ("Охранник", "Shop guard"),
    "mobtalk.settings.group.shops": ("Магазины", "Shops"),
    "mobtalk.settings.cityShops": ("Супермаркеты работают", "Supermarkets work"),
    "mobtalk.settings.cityShops.tooltip": ("Товар на витринах универсамов можно брать и оплачивать изумрудами на кассе или на кассе самообслуживания. Выключено: витрины просто декор.",
                                           "Goods on the supermarket displays can be taken and paid for in emeralds at a till or the self-checkout. Off: the displays are just decoration."),
    "mobtalk.settings.shopStaff": ("Кассиры и охрана", "Cashiers and guards"),
    "mobtalk.settings.shopStaff.tooltip": ("На пустую кассу приходит кассир, на пост охраны — охранник (новый житель, если старого нет).",
                                           "A cashier comes to an empty till and a guard to the guard's desk (a newcomer if the old one is gone)."),
    "mobtalk.settings.shopTheftAlarm": ("Тревога при краже", "Alarm on theft"),
    "mobtalk.settings.shopTheftAlarm.tooltip": ("Если унести неоплаченный товар и охранник не поймал, город поднимает тревогу против игрока, свидетели это запомнят.",
                                                "If unpaid goods are carried off and the guard did not catch the player, the city raises its alarm against them and witnesses remember it."),
    "mobtalk.tune.shopRestockMinutes": ("Завоз товара: раз в %s мин", "Delivery: every %s min"),
    "mobtalk.tune.shopRestockMinutes.tooltip": ("Через столько минут опустевшие полки снова полны.", "Emptied displays are full again after this many minutes."),
    "mobtalk.tune.shopPricePercent": ("Цены: %s%%", "Prices: %s%%"),
    "mobtalk.tune.shopPricePercent.tooltip": ("Цены в магазинах от каталога, в процентах. Дешевле одного изумруда товар не бывает.",
                                              "Shop prices against the catalogue, percent. Nothing costs less than one emerald."),
    "mobtalk.tune.shopTheftSeconds": ("Кража засчитывается через: %s с", "Theft counts after: %s s"),
    "mobtalk.tune.shopTheftSeconds.tooltip": ("Сколько секунд игрок с неоплаченным товаром должен пробыть вне магазина, чтобы это стало кражей. Охранник может поймать раньше.",
                                              "Seconds out of the shop with unpaid goods before it is a theft. The guard may catch the player first."),
    "mobtalk.tune.shopCheckoutGlitchPercent": ("«Неопознанный товар»: %s%%", "\"Unexpected item\": %s%%"),
    "mobtalk.tune.shopCheckoutGlitchPercent.tooltip": ("Как часто касса самообслуживания жалуется на неопознанный товар в зоне упаковки (повторная попытка проходит).",
                                                       "How often the self-checkout complains of an unexpected item in the bagging area (the next try goes through)."),
    "mobtalk.shop.unpaid": ("Не оплачено: %s изумр. за %s шт.", "Unpaid: %s emeralds for %s"),
    "mobtalk.shop.tag": ("%s за %s шт", "%s for %s"),
    "mobtalk.shop.took": ("%s ×%s — %s изумр.", "%s ×%s — %s emeralds"),
    "mobtalk.shop.took.basket": (" · в корзине: %s", " · in the basket: %s"),
    "mobtalk.shop.empty": ("%s закончился. Завоз через %s мин.", "%s is sold out. Delivery in %s min."),
    "mobtalk.shop.put_back": ("%s — вернули на полку", "%s — put back"),
    "mobtalk.shop.assigned": ("На полке теперь: %s", "This lane now sells: %s"),
    "mobtalk.shop.closed": ("Касса закрыта: кассира нет на месте. Попробуйте другую кассу или самообслуживание.", "The till is closed: no cashier. Try another till or the self-checkout."),
    "mobtalk.shop.screen": ("[Касса самообслуживания] ", "[Self-checkout] "),
    "mobtalk.shop.glitch": ("[Касса самообслуживания] Неопознанный товар в зоне упаковки. Дождитесь сотрудника... (попробуйте ещё раз)",
                            "[Self-checkout] Unexpected item in the bagging area. Please wait for assistance... (try again)"),
    "mobtalk.shop.change": ("Сдача: %s изумр.", "Change: %s emeralds"),
    "mobtalk.shop.gate": ("Пи-пи-пи! Сработала антикражная рамка!", "Beep-beep! The anti-theft gate went off!"),
    "mobtalk.shop.caught": ("Охранник отобрал неоплаченный товар", "The guard took the unpaid goods back"),
    "mobtalk.shop.stolen": ("Вы унесли неоплаченный товар на %s изумр. Это кража: город запомнит.", "You carried off unpaid goods worth %s emeralds. That is theft: the city will remember."),
    "mobtalk.shop.basket.empty": ("Пустая", "Empty"),
    "mobtalk.shop.basket.hint": ("Товар с полок кладётся в корзину. Присесть + ПКМ — выложить в инвентарь.", "Goods from the shelves go into the basket. Sneak + use: empty it into the inventory."),
    "mobtalk.shop.basket.took": ("Взяли корзину", "Took a basket"),
    "mobtalk.shop.basket.have": ("Корзина у вас уже есть", "You already have a basket"),
    "mobtalk.shop.basket.back": ("Вернули корзину", "Put the basket back"),
    "mobtalk.shop.basket.full_back": ("Сначала оплатите или выложите товар", "Pay for or take out the goods first"),
    "mobtalk.shop.basket.left": ("Пустую корзину оставили в магазине", "Left the empty basket in the shop"),
    "mobtalk.shop.say.paid.0": ("С вас %s изумр. Спасибо за покупку!", "That's %s emeralds. Thank you!"),
    "mobtalk.shop.say.paid.1": ("Итого %s изумр. Пакет не нужен? Приходите ещё!", "%s emeralds in all. No bag? Come again!"),
    "mobtalk.shop.say.paid.2": ("%s изумр., пожалуйста. Мелочи не будет? Ладно, спасибо.", "%s emeralds, please. No small change? Fine, thanks."),
    "mobtalk.shop.say.paid.self": ("Оплачено: %s изумр. за %s шт. Не забудьте покупки!", "Paid: %s emeralds for %s items. Don't forget your shopping!"),
    "mobtalk.shop.say.nothing.0": ("Что брать будем? Товар на полках.", "What will it be? The goods are on the shelves."),
    "mobtalk.shop.say.nothing.1": ("Следующий!", "Next!"),
    "mobtalk.shop.say.nothing.2": ("Корзинку возьмите у входа, удобнее будет.", "Take a basket at the entrance, it's easier."),
    "mobtalk.shop.say.nothing.self": ("Отсканируйте товар.", "Please scan an item."),
    "mobtalk.shop.say.basket_back.0": ("Корзинку оставьте, спасибо.", "Leave the basket here, thanks."),
    "mobtalk.shop.say.basket_back.1": ("Пустую корзинку — сюда.", "Empty basket goes here."),
    "mobtalk.shop.say.basket_back.2": ("Ничего не выбрали? Корзинку заберу.", "Nothing caught your eye? I'll take the basket."),
    "mobtalk.shop.say.basket_back.self": ("Корзина возвращена.", "Basket returned."),
    "mobtalk.shop.say.short.0": ("С вас %s изумр., а не хватает %s. Лишнее верните на полку.", "That's %s emeralds and you're %s short. Put something back."),
    "mobtalk.shop.say.short.1": ("Не хватает %2$s изумр. Отменяю? Позовите старшего!", "You're %2$s emeralds short. Shall I cancel? Call the manager!"),
    "mobtalk.shop.say.short.2": ("Итого %s, а у вас меньше на %s. Может, без торта обойдёмся?", "It's %s and you're %s short. Maybe skip the cake?"),
    "mobtalk.shop.say.short.self": ("Недостаточно средств: нужно %s изумр., не хватает %s.", "Insufficient funds: %s emeralds needed, %s short."),
    "mobtalk.shop.say.stop.0": ("Молодой человек! А платить кто будет?!", "Hey you! Who's going to pay for that?!"),
    "mobtalk.shop.say.stop.1": ("Стоять! Товар не оплачен!", "Stop! Those goods aren't paid for!"),
    "mobtalk.shop.say.stop.2": ("Охрана! Он без оплаты уходит!", "Security! He's leaving without paying!"),
    "mobtalk.shop.say.caught.0": ("Пройдёмте. Товар верните, и чтобы я вас тут больше не видел.", "This way, please. Hand it back, and don't let me see you here again."),
    "mobtalk.shop.say.caught.1": ("Попался! Неоплаченное — на стол.", "Got you! Unpaid goods on the table."),
    "mobtalk.shop.say.caught.2": ("Ну что, на камеру улыбнёмся? Товар сюда.", "Smile for the camera. Goods over here."),
}


def main():
    for lang, i in (("ru_ru", 0), ("en_us", 1)):
        p = os.path.join(LANG, lang + ".json")
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        for k, v in T.items():
            d[k] = v[i]
        with open(p, "w", encoding="utf-8") as f:
            json.dump(d, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print("shop lang:", len(T), "keys")


if __name__ == "__main__":
    main()
