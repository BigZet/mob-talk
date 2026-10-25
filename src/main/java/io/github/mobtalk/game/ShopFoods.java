package io.github.mobtalk.game;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

/** The supermarket's own groceries: Russian everyday food (a loaf, kefir, pelmeni, condensed milk…) as plain food items with
 * pixel-art sprites from tools/city/shop_goods.py. Prices and displays are in io.github.mobtalk.core.ShopCatalog. Frozen pelmeni
 * are cooked in a furnace, smoker or on a campfire (data/mobtalk/recipe). */
public final class ShopFoods {
    private ShopFoods() { }

    /** How a product is taken: eaten, drunk, or drunk and it settles the stomach (milk clears every effect, kefir nausea and poison). */
    enum Kind { EAT, DRINK, MILK, KEFIR }

    public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        food("baton", 5, 0.6f, Kind.EAT);
        food("borodinsky", 6, 0.7f, Kind.EAT);
        food("sushki", 2, 0.3f, Kind.EAT, true);
        food("pryanik", 3, 0.4f, Kind.EAT, true);
        food("milk_carton", 2, 0.4f, Kind.MILK);
        food("kefir", 3, 0.5f, Kind.KEFIR);
        food("smetana", 3, 0.6f, Kind.EAT);
        food("tvorog", 4, 0.8f, Kind.EAT);
        food("cheese", 4, 0.7f, Kind.EAT, true);
        food("doktorskaya", 6, 0.8f, Kind.EAT);
        food("sosiski", 5, 0.6f, Kind.EAT);
        Item pelmeni = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "pelmeni"), new Item(new Item.Settings()
                .food(new FoodComponent.Builder().nutrition(2).saturationModifier(0.1f)
                        .statusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 400, 0), 0.4f).build())));
        ITEMS.put("pelmeni", pelmeni);
        food("cooked_pelmeni", 8, 0.8f, Kind.EAT);
        food("plombir", 3, 0.4f, Kind.EAT, true);
        food("sgushchenka", 5, 0.5f, Kind.EAT);
        food("chocolate", 3, 0.3f, Kind.EAT, true);
        food("semechki", 1, 0.2f, Kind.EAT, true);
        food("pickles", 3, 0.4f, Kind.EAT);
        food("kvass", 2, 0.3f, Kind.DRINK);
        food("noodles", 4, 0.3f, Kind.EAT);
        food("cucumber", 2, 0.3f, Kind.EAT, true);
        food("tomato", 2, 0.3f, Kind.EAT, true);
        food("cabbage", 3, 0.4f, Kind.EAT);
        food("onion", 1, 0.2f, Kind.EAT, true);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK).register(entries -> ITEMS.values().forEach(entries::add));
    }

    private static void food(String id, int nutrition, float saturation, Kind kind) { food(id, nutrition, saturation, kind, false); }

    private static void food(String id, int nutrition, float saturation, Kind kind, boolean snack) {
        FoodComponent.Builder food = new FoodComponent.Builder().nutrition(nutrition).saturationModifier(saturation);
        if (snack) food.snack();
        if (kind != Kind.EAT) food.alwaysEdible();
        Item.Settings settings = new Item.Settings().food(food.build());
        ITEMS.put(id, Registry.register(Registries.ITEM, Identifier.of("mobtalk", id), kind == Kind.EAT ? new Item(settings) : new Drink(settings, kind)));
    }

    /** A carton or a bottle: drunk, not eaten; milk and kefir settle the stomach. */
    static final class Drink extends Item {
        private final Kind kind;
        Drink(Settings settings, Kind kind) { super(settings); this.kind = kind; }

        @Override public UseAction getUseAction(ItemStack stack) { return UseAction.DRINK; }
        @Override public SoundEvent getDrinkSound() { return SoundEvents.ENTITY_GENERIC_DRINK; }
        @Override public SoundEvent getEatSound() { return SoundEvents.ENTITY_GENERIC_DRINK; }

        @Override public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
            if (!world.isClient) {
                if (kind == Kind.MILK) user.clearStatusEffects();
                if (kind == Kind.KEFIR) { user.removeStatusEffect(StatusEffects.NAUSEA); user.removeStatusEffect(StatusEffects.POISON); }
            }
            return super.finishUsing(stack, world, user);
        }
    }
}
