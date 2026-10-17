package io.github.mobtalk.core;

/** A tiny trades.json for core tests: the bundled file is filled by the content branch, so tests never depend on it. */
final class TradeFixtures {
    static final String COW = """
        {"schemaVersion": 1, "species": {
          "minecraft:cow": {
            "deals": [
              {"id": "milk", "name": "Ведро молока", "sell": {"id": "minecraft:milk_bucket", "name": "Ведро молока", "count": 1},
               "price": {"id": "minecraft:wheat", "name": "Пшеница", "count": 6}},
              {"id": "hay", "name": "Сено", "sell": {"id": "minecraft:hay_block", "name": "Сноп сена", "count": 1},
               "price": {"id": "minecraft:wheat", "name": "Пшеница", "count": 1}},
              {"id": "bulk", "name": "Оптом", "sell": {"id": "minecraft:leather", "name": "Кожа", "count": 10},
               "price": {"id": "minecraft:wheat", "name": "Пшеница", "count": 60}},
              {"id": "leather", "name": "Кожа с подружки", "sell": {"id": "minecraft:leather", "name": "Кожа", "count": 3},
               "price": {"id": "minecraft:emerald", "name": "Изумруд", "count": 2}, "secret": true,
               "hint": "Знает, где лежит кожа пропавшей подруги", "unlockRelationship": 40, "unlockQuest": "fence", "maxPerDay": 1}
            ],
            "quests": [
              {"id": "fence", "title": "Почини загон", "kind": "BRING", "target": "minecraft:oak_fence", "count": 8,
               "pitch": "Крипер снёс половину загона", "reward": {"relationship": 30, "unlockDeal": "leather", "discountDeal": "milk", "discountPercent": 25}},
              {"id": "wolves", "title": "Прогони волков", "kind": "kill", "target": "minecraft:wolf", "count": 2,
               "pitch": "Волки ходят у загона", "reward": {"relationship": 10}}
            ]
          },
          "pig": {
            "deals": [
              {"id": "truffle", "name": "Трюфель", "sell": {"id": "minecraft:brown_mushroom", "name": "Гриб", "count": 4},
               "price": {"id": "minecraft:carrot", "name": "Морковь", "count": 3}, "secret": true, "hint": "Нюхом чует грибы", "unlockRelationship": 20}
            ]
          }
        }}
        """;
    private TradeFixtures() { }
    static void install() { TradeCatalog.install(TradeCatalog.parse(COW)); }
    static void reset() { TradeCatalog.install(null); }
}
