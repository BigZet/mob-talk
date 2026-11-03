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

    /** How a product is taken: eaten, drunk, or drunk and it settles the stomach (milk clears every effect, kefir nausea and poison);
     * hot tea warms (a little regeneration); beer makes bold and dizzy for a while. */
    enum Kind { EAT, DRINK, MILK, KEFIR, TEA, BEER }

    public static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    /** The kiosk's reading and how many lines each has (mobtalk.kiosk.read.<id>.<n>, from tools/city/kiosk.py). */
    private static final Map<String, Integer> READS = new LinkedHashMap<>();
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
        // the kiosks at the bus stop
        Item shawarma = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "shawarma"), new Item(new Item.Settings()
                .food(new FoodComponent.Builder().nutrition(9).saturationModifier(0.8f)
                        .statusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 200, 0), 0.08f).build())));
        ITEMS.put("shawarma", shawarma);
        food("cheburek", 6, 0.6f, Kind.EAT);
        Item belyash = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "belyash"), new Item(new Item.Settings()
                .food(new FoodComponent.Builder().nutrition(5).saturationModifier(0.6f)
                        .statusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 300, 0), 0.1f).build())));
        ITEMS.put("belyash", belyash);
        food("tea_cup", 1, 0.2f, Kind.TEA);
        food("beer", 2, 0.2f, Kind.BEER);
        food("sukhariki", 2, 0.2f, Kind.EAT, true);
        food("vobla", 3, 0.5f, Kind.EAT);
        food("gum", 1, 0.1f, Kind.EAT, true);
        READS.put("newspaper", 12); READS.put("krokodil", 6); READS.put("crossword", 6);
        for (Map.Entry<String, Integer> read : READS.entrySet())
            ITEMS.put(read.getKey(), Registry.register(Registries.ITEM, Identifier.of("mobtalk", read.getKey()), new Readable(new Item.Settings().maxCount(16), read.getKey(), read.getValue())));
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
                if (kind == Kind.TEA) user.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 100, 0));
                if (kind == Kind.BEER) {
                    user.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 600, 0));
                    user.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 160, 0));
                }
            }
            return super.finishUsing(stack, world, user);
        }
    }

    /** A newspaper, «Крокодил» or a crossword: read (used) for a line of it in the chat; a page turns. Not used up. */
    static final class Readable extends Item {
        private final String id; private final int lines;
        Readable(Settings settings, String id, int lines) { super(settings); this.id = id; this.lines = lines; }

        @Override public net.minecraft.util.TypedActionResult<ItemStack> use(World world, net.minecraft.entity.player.PlayerEntity user, net.minecraft.util.Hand hand) {
            ItemStack stack = user.getStackInHand(hand);
            if (!world.isClient) {
                user.sendMessage(net.minecraft.text.Text.translatable("mobtalk.kiosk.read." + id + "." + world.random.nextInt(lines))
                        .formatted(net.minecraft.util.Formatting.ITALIC, net.minecraft.util.Formatting.GRAY), false);
                world.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOOK_PAGE_TURN, net.minecraft.sound.SoundCategory.PLAYERS, 0.8f, 1f);
                user.getItemCooldownManager().set(this, 30);
            }
            return net.minecraft.util.TypedActionResult.success(stack, world.isClient);
        }
    }
}
