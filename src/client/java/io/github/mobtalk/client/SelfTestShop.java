package io.github.mobtalk.client;

import io.github.mobtalk.game.ShopContent;
import io.github.mobtalk.game.ShopController;
import java.util.*;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/** City supermarket ({@code -Only shop}): a small shop in the sky with every display, two tills, a self-checkout, a stack of
 * baskets, an anti-theft gate and the guard's desk. Checks that a cashier and a guard come, that the displays stock themselves,
 * the basket, paying at the till (emeralds out, marks off, the basket back), change from an emerald block, the self-checkout's
 * complaint, being short of money, the guard catching a thief, and a theft that gets away. Screenshots of the shop, the goods up
 * close and the staff. No LLM, no voice. */
final class SelfTestShop {
    private static final String TAG = "mobtalk_shop";
    private final SelfTest test;
    private final int fx, fy, fz;
    private final List<String> chat = Collections.synchronizedList(new ArrayList<>());
    private Runnable next = () -> { };
    /** Run every tick while a screenshot waits (the cart's: the body turned as the head, as after walking). */
    private Runnable steady = () -> { };
    private int mark;

    SelfTestShop(SelfTest test, int stageX, int stageY, int stageZ) {
        this.test = test; this.fx = stageX + 440; this.fy = stageY; this.fz = stageZ;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String line = (overlay ? "[bar] " : "") + Formatting.strip(message.getString());
            chat.add(line);
            test.log("chat: " + line);
        });
    }

    private String name() { return test.owner.client.player.getName().getString(); }
    private ServerWorld world() { return (ServerWorld) test.serverPlayer().getWorld(); }
    private ServerPlayerEntity player() { return test.serverPlayer(); }
    private String at(int dx, int dy, int dz) { return (fx + dx) + " " + (fy + dy) + " " + (fz + dz); }
    private BlockPos pos(int dx, int dy, int dz) { return new BlockPos(fx + dx, fy + dy, fz + dz); }
    private <T> T onServer(java.util.function.Supplier<T> task) { return test.server().isOnThread() ? task.get() : test.server().submit(task).join(); }
    private void mark() { mark = chat.size(); }
    private boolean heard(String part) { synchronized (chat) { return chat.subList(Math.min(mark, chat.size()), chat.size()).stream().anyMatch(l -> l.contains(part)); } }
    private Box area() { return new Box(pos(0, 0, 0)).expand(40, 10, 40); }
    private List<VillagerEntity> staff(boolean cashier) {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && (cashier ? ShopContent.cashier(v) : ShopContent.guard(v))));
    }
    private int count(net.minecraft.item.Item item) { return onServer(() -> player().getInventory().count(item)); }
    private int unpaid() { return onServer(() -> ShopController.carried(player()).size()); }
    private ShopContent.DisplayBlockEntity display(int dx, int dz) { return onServer(() -> world().getBlockEntity(pos(dx, 0, dz)) instanceof ShopContent.DisplayBlockEntity d ? d : null); }
    private void take(int dx, int dz, int lane) { test.server().execute(() -> ShopController.take(world(), display(dx, dz), lane, player())); }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        test.log("shop stages: staff, stocked displays, basket, till, change, self-checkout, short of money, caught, theft, market, deals");
        dealsWere = test.bridge().config.shopDeals;
        test.bridge().config.shopDeals = false; // the prices of the stages stay the catalogue's; the deals have their own stage
        test.command("time set 1000"); test.command("weather clear");
        test.command("gamerule doDaylightCycle false"); test.command("gamerule doMobSpawning false");
        test.command("gamemode survival " + name());
        test.command("effect give " + name() + " minecraft:slow_falling 900 1 true");
        test.command("effect give " + name() + " minecraft:resistance 900 255 true");
        test.command("effect give " + name() + " minecraft:saturation 900 1 true");
        test.command("tp " + name() + " " + at(0, 1, 6));
        test.await("pause: the stage chunks load", 3, () -> false, this::build);
    }

    /** The shop: a floor 17 x 13 with walls of glass, the displays in a row along the back and a gondola island in the middle. */
    private void build() {
        test.command("forceload add " + (fx - 10) + " " + (fz - 8) + " " + (fx + 10) + " " + (fz + 34)); // the street too, or its fill fails
        test.command("fill " + at(-8, -1, -6) + " " + at(8, -1, 6) + " minecraft:smooth_stone");
        test.command("fill " + at(-8, 4, -6) + " " + at(8, 4, 6) + " minecraft:white_concrete");
        test.command("fill " + at(-8, 0, -6) + " " + at(8, 3, -6) + " minecraft:light_blue_stained_glass");
        test.command("fill " + at(-8, 0, -6) + " " + at(-8, 3, 6) + " minecraft:light_blue_stained_glass");
        test.command("fill " + at(8, 0, -6) + " " + at(8, 3, 6) + " minecraft:light_blue_stained_glass");
        for (int x = -6; x <= 6; x += 3) test.command("setblock " + at(x, 3, 0) + " minecraft:light[level=15]");
        String[] wall = {"shop_fridge", "shop_fridge", "shop_bread_rack", "shop_bread_rack", "shop_freezer", "shop_freezer", "shop_produce", "shop_produce",
                "shop_fridge", "shop_bread_rack", "shop_freezer", "shop_produce", "shop_fridge"};
        for (int i = 0; i < wall.length; i++) test.command("setblock " + at(-6 + i, 0, -5) + " mobtalk:" + wall[i] + "[facing=south]");
        for (int x = -4; x <= 4; x++) {
            test.command("setblock " + at(x, 0, -1) + " mobtalk:shop_shelf[facing=south]");
            test.command("setblock " + at(x, 1, -1) + " mobtalk:shop_shelf[facing=south]");
        }
        test.command("setblock " + at(-6, 0, 3) + " mobtalk:cash_register[facing=south]");
        test.command("setblock " + at(-3, 0, 3) + " mobtalk:cash_register[facing=south]");
        test.command("setblock " + at(0, 0, 3) + " mobtalk:self_checkout[facing=south]");
        test.command("setblock " + at(3, 0, 5) + " mobtalk:theft_gate[facing=east]");
        test.command("setblock " + at(5, 0, 3) + " mobtalk:basket_stack[facing=south]");
        test.command("setblock " + at(6, 0, 1) + " mobtalk:shop_guard_desk[facing=south]");
        test.command("setblock " + at(7, 0, 4) + " mobtalk:cart_corral[facing=west]");
        test.command("fill " + at(-8, -1, 7) + " " + at(8, -1, 32) + " minecraft:grass_block"); // the street outside
        test.command("execute at " + name() + " run kill @e[type=minecraft:villager,distance=..60]");
        test.command("clear " + name());
        test.await("a cashier and a guard come", 30, () -> staff(true).size() >= 2 && !staff(false).isEmpty(), this::stocked);
    }

    private final Set<String> heardSounds = Collections.synchronizedSet(new HashSet<>());

    private void stocked() {
        // The shop's sounds are known to the client (sounds.json and the oggs), and which of them it plays.
        for (String id : List.of("beep", "drawer", "alarm", "chime", "hum", "cart", "music")) {
            var set = test.owner.client.getSoundManager().get(net.minecraft.util.Identifier.of("mobtalk", "shop." + id));
            test.check(set != null && set.getSound(net.minecraft.util.math.random.Random.create()) != null, "the shop sound " + id + " is there");
        }
        test.owner.client.getSoundManager().registerListener((sound, set, range) -> {
            if (sound.getId().getNamespace().equals("mobtalk")) heardSounds.add(sound.getId().getPath());
        });
        test.check(staff(true).size() >= 2, "a cashier came to each till (" + staff(true).size() + ")");
        test.check(!staff(false).isEmpty(), "a guard came to the guard's desk");
        test.await("the displays stock themselves", 15, () -> {
            for (int x = -6; x <= 6; x++) { ShopContent.DisplayBlockEntity d = display(x, -5); if (d == null || d.lanes[0].goods.isEmpty()) return false; }
            return true;
        }, () -> {
            ShopContent.DisplayBlockEntity fridge = display(-6, -5);
            test.check(fridge != null && fridge.lanes[0].stock == fridge.display().capacity(), "a display starts full (" + (fridge == null ? "-" : fridge.lanes[0].stock) + ")");
            test.command("tp " + name() + " " + at(0, 0, 5) + " 180 15");
            shot("mobtalk-shop-overview.png", () -> {
                test.command("tp " + name() + " " + at(-3, 0, -3) + " 180 25");
                shot("mobtalk-shop-wall.png", () -> {
                    test.command("tp " + name() + " " + at(2, 0, 1) + " 180 10");
                    shot("mobtalk-shop-gondola.png", this::basket);
                });
            });
        });
    }

    /** A basket from the stack; goods from three displays go into it; their stock goes down. */
    private void basket() {
        test.command("tp " + name() + " " + at(0, 0, 1) + " 180 0");
        test.server().execute(() -> ShopController.basketStack(world(), pos(5, 0, 3), player()));
        test.await("a basket in the inventory", 5, () -> count(ShopContent.BASKET) == 1, () -> {
            int before = display(-6, -5).lanes[0].stock;
            take(-6, -5, 0); take(-4, -5, 1); take(-2, -5, 0); take(0, -1, 2);
            test.await("goods in the basket", 5, () -> unpaid() >= 4, () -> {
                test.check(unpaid() >= 4, "four lots in the basket (" + unpaid() + ")");
                test.check(display(-6, -5).lanes[0].stock == before - 1, "the display has one lot fewer");
                pay();
            });
        });
    }

    private void pay() {
        int cost = onServer(() -> ShopController.cost(ShopController.carried(player())));
        test.command("give " + name() + " minecraft:emerald 20");
        test.await("emeralds given", 5, () -> count(Items.EMERALD) == 20, () -> {
            mark();
            test.server().execute(() -> ShopController.checkout(world(), pos(-6, 0, 3), player(), false));
            test.await("paid at the till", 5, () -> unpaid() == 0 && heard("изумр"), () -> {
                test.check(count(Items.EMERALD) == 20 - cost, "the till took " + cost + " emeralds (" + (20 - count(Items.EMERALD)) + ")");
                test.check(count(ShopContent.BASKET) == 0, "the basket stays at the till");
                test.check(heard("изумр"), "the cashier names the sum");
                test.log("shop sounds heard so far: " + heardSounds);
                change();
            });
        });
    }

    /** Paying with an emerald block: the change comes back in emeralds. */
    private void change() {
        test.command("clear " + name() + " minecraft:emerald");
        test.command("give " + name() + " minecraft:emerald_block 1");
        take(-4, -5, 0);
        test.await("one lot taken", 5, () -> unpaid() == 1, () -> {
            int cost = onServer(() -> ShopController.cost(ShopController.carried(player())));
            test.server().execute(() -> ShopController.checkout(world(), pos(-3, 0, 3), player(), false));
            test.await("paid with a block", 5, () -> unpaid() == 0, () -> {
                test.check(count(Items.EMERALD_BLOCK) == 0 && count(Items.EMERALD) == 9 - cost, "an emerald block pays and " + (9 - cost) + " come back (" + count(Items.EMERALD) + ")");
                cart();
            });
        });
    }

    /** The cart from the corral holds more than a basket; the shop's own groceries are on the shelves; paying leaves the cart
     * at the till; frozen pelmeni cook. Screenshots: the cart in hand from behind, the new groceries in the inventory. */
    private void cart() {
        test.check(onServer(() -> world().getRecipeManager().get(net.minecraft.util.Identifier.of("mobtalk", "cooked_pelmeni_from_smelting")).isPresent()),
                "frozen pelmeni cook in a furnace");
        int own = onServer(() -> {
            int n = 0;
            for (int x = -6; x <= 6; x++) {
                ShopContent.DisplayBlockEntity d = display(x, -5);
                if (d != null) for (ShopContent.DisplayBlockEntity.Lane lane : d.lanes)
                    if (!lane.goods.isEmpty() && net.minecraft.registry.Registries.ITEM.getId(lane.goods.getItem()).getNamespace().equals("mobtalk")) n++;
            }
            for (int x = -4; x <= 4; x++) {
                ShopContent.DisplayBlockEntity d = display(x, -1);
                if (d != null) for (ShopContent.DisplayBlockEntity.Lane lane : d.lanes)
                    if (!lane.goods.isEmpty() && net.minecraft.registry.Registries.ITEM.getId(lane.goods.getItem()).getNamespace().equals("mobtalk")) n++;
            }
            return n;
        });
        test.check(own > 0, "the shop's own groceries are on the shelves (" + own + " lanes)");
        test.command("clear " + name());
        test.server().execute(() -> ShopController.cartStack(world(), pos(7, 0, 4), player()));
        test.await("a cart in the inventory", 5, () -> count(ShopContent.CART) == 1, () -> {
            for (int x = -6; x <= 6; x++) { int dx = x; test.server().execute(() -> ShopController.take(world(), display(dx, -5), 0, player())); }
            for (int x = -6; x <= -4; x++) { int dx = x; test.server().execute(() -> ShopController.take(world(), display(dx, -5), 1, player())); }
            test.await("goods in the cart", 5, () -> cartLots() >= 14, () -> {
                test.check(cartLots() > ShopContent.BASKET_LOTS, "a cart holds more than a basket (" + cartLots() + " lots)");
                test.server().execute(() -> {
                    var inventory = player().getInventory();
                    for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(ShopContent.CART)) {
                        ItemStack cart = inventory.getStack(i);
                        inventory.setStack(i, inventory.getStack(inventory.selectedSlot));
                        inventory.setStack(inventory.selectedSlot, cart);
                    }
                });
                test.command("tp " + name() + " " + at(4, 0, 1) + " 90 40");
                steady = () -> { test.owner.client.player.setBodyYaw(90); test.owner.client.player.prevBodyYaw = 90; };
                test.owner.client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                shot("mobtalk-shop-cart.png", () -> {
                    test.owner.client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
                    shot("mobtalk-shop-cart-front.png", () -> {
                    test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
                    shot("mobtalk-shop-cart-first.png", () -> {
                    steady = () -> { };
                    test.command("give " + name() + " minecraft:emerald 64");
                    mark();
                    test.server().execute(() -> ShopController.checkout(world(), pos(-3, 0, 3), player(), false));
                    test.await("paid for the cart", 5, () -> unpaid() == 0 && heard("изумр"), () -> {
                        test.check(count(ShopContent.CART) == 0, "the cart stays at the till");
                        groceries();
                    });
                    });
                    });
                });
            });
        });
    }

    private int cartLots() {
        return onServer(() -> {
            var inventory = player().getInventory();
            for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(ShopContent.CART)) return ShopController.basketContents(inventory.getStack(i)).size();
            return 0;
        });
    }

    /** Every grocery of the shop's own in the inventory, shown on the inventory screen. */
    private void groceries() {
        test.command("clear " + name());
        for (String id : io.github.mobtalk.game.ShopFoods.ITEMS.keySet()) test.command("give " + name() + " mobtalk:" + id + " 4");
        test.command("give " + name() + " mobtalk:shopping_cart 1");
        test.command("give " + name() + " mobtalk:cart_corral 1");
        test.await("pause: the goods arrive", 2, () -> false, () -> {
            test.owner.client.execute(() -> test.owner.client.setScreen(new net.minecraft.client.gui.screen.ingame.InventoryScreen(test.owner.client.player)));
            shot("mobtalk-shop-groceries.png", () -> {
                test.owner.client.execute(() -> test.owner.client.setScreen(null));
                test.command("clear " + name());
                test.command("give " + name() + " minecraft:emerald 10");
                test.await("emeralds for the self-checkout", 5, () -> count(Items.EMERALD) == 10, this::selfCheckout);
            });
        });
    }

    private void selfCheckout() {
        int saved = test.bridge().config.shopCheckoutGlitchPercent;
        test.bridge().config.shopCheckoutGlitchPercent = 100;
        take(-2, -5, 1);
        test.await("one lot taken", 5, () -> unpaid() == 1, () -> {
            mark();
            test.server().execute(() -> ShopController.checkout(world(), pos(0, 0, 3), player(), true));
            test.await("the self-checkout complains", 5, () -> heard("Неопознанный") || heard("Unexpected"), () -> {
                test.check(unpaid() == 1, "a complaint takes no money");
                test.server().execute(() -> ShopController.checkout(world(), pos(0, 0, 3), player(), true));
                test.await("the second try goes through", 5, () -> unpaid() == 0, () -> {
                    test.bridge().config.shopCheckoutGlitchPercent = saved;
                    test.command("tp " + name() + " " + at(0, 0, 5) + " 180 30");
                    shot("mobtalk-shop-self-checkout.png", this::short_);
                });
            });
        });
    }

    private void short_() {
        test.command("clear " + name() + " minecraft:emerald");
        take(-6, -5, 1);
        test.await("one lot taken", 5, () -> unpaid() == 1, () -> {
            mark();
            test.server().execute(() -> ShopController.checkout(world(), pos(-6, 0, 3), player(), false));
            test.await("the cashier says it is short", 5, () -> heard("хват") || heard("short") || heard("меньше"), () -> {
                test.check(unpaid() == 1, "short of money: the goods stay unpaid");
                caught();
            });
        });
    }

    /** Out of the door with the unpaid lot: the guard runs up and takes it back. */
    private void caught() {
        mark();
        // The guard back behind the desk first (wandering the aisles they can be stuck behind a gondola: then it is a theft).
        test.server().execute(() -> { for (VillagerEntity guard : world().getEntitiesByClass(VillagerEntity.class, area(), v -> v.isAlive() && ShopContent.guard(v)))
            guard.refreshPositionAndAngles(fx + 6.5, fy, fz + 0.5, 180, 0); });
        test.command("tp " + name() + " " + at(0, 0, 14) + " 180 10");
        int[] polls = {0};
        test.await("the guard catches the player", 15, () -> {
            if (polls[0]++ % 20 == 0) for (VillagerEntity guard : staff(false))
                test.log("guard at " + guard.getBlockPos().toShortString() + " player at " + player().getBlockPos().toShortString() + " nav " + guard.getNavigation().isIdle());
            return unpaid() == 0 && heard("отобрал");
        }, () -> {
            test.check(heard("Охран") || heard("Стоять") || heard("платить"), "someone shouts after the thief");
            test.check(unpaid() == 0 && heard("отобрал"), "the guard took the goods back");
            shot("mobtalk-shop-caught.png", this::theft);
        });
    }

    /** No guard on duty: out with the goods, and after a few seconds it is a theft; the goods are the player's. */
    private void theft() {
        test.server().execute(() -> { for (VillagerEntity v : staff(false)) v.discard(); });
        test.command("tp " + name() + " " + at(0, 0, 1) + " 180 0");
        int savedSeconds = test.bridge().config.shopTheftSeconds;
        test.bridge().config.shopTheftSeconds = 3;
        take(-4, -5, 2);
        test.await("one lot taken", 5, () -> unpaid() == 1, () -> {
            mark();
            test.command("tp " + name() + " " + at(0, 0, 30) + " 180 10");
            test.await("the theft counts", 20, () -> heard("кража") || heard("theft"), () -> {
                test.check(unpaid() == 0, "stolen goods lose the unpaid mark");
                test.bridge().config.shopTheftSeconds = savedSeconds;
                looks();
            });
        });
    }

    /** The cashier and the guard up close, in the human look and the vanilla one. */
    private void looks() {
        test.command("summon minecraft:villager " + at(-1, 0, 6) + " {PersistenceRequired:1b,NoAI:1b,Rotation:[0f,0f],Tags:[\"" + TAG + "\"],VillagerData:{profession:\"mobtalk:cashier\",type:\"minecraft:plains\",level:1}}");
        test.command("summon minecraft:villager " + at(1, 0, 6) + " {PersistenceRequired:1b,NoAI:1b,Rotation:[0f,0f],Tags:[\"" + TAG + "\"],VillagerData:{profession:\"mobtalk:shop_guard\",type:\"minecraft:plains\",level:1}}");
        test.command("tp " + name() + " " + at(0, 0, 9) + " 180 5");
        test.owner.config().humanVillagers = true;
        shot("mobtalk-shop-staff-human.png", () -> {
            test.owner.config().humanVillagers = false;
            io.github.mobtalk.client.human.HumanLooks.enabled = false;
            shot("mobtalk-shop-staff-vanilla.png", () -> {
                test.owner.config().humanVillagers = true;
                io.github.mobtalk.client.human.HumanLooks.enabled = true;
                market();
            });
        });
    }

    /** {@code -Dmobtalk.selftest.shopAt=x,y,z}: a till of a generated city's supermarket — its staff and goods, looked at from
     * either side of the till row. */
    // ---- The market (рынок) ----

    /** Template corner of the placed market, and a block of it by the template's own coordinates (ground G = 4). */
    private int mx, my, mz;
    private BlockPos mpos(int x, int z) { return new BlockPos(mx + x, my + 5, mz + z); }
    private String mat(int x, int y, int z) { return (mx + x) + " " + (my + 5 + y) + " " + (mz + z); }
    private List<VillagerEntity> vendors() {
        return onServer(() -> world().getEntitiesByClass(VillagerEntity.class, new Box(mpos(0, 0)).expand(0).union(new Box(mpos(24, 21))).expand(6), v -> v.isAlive() && ShopContent.vendor(v)));
    }

    /** The market template set down in the sky: vendors come to the scales and the old women's tables; buying from a stall
     * takes the money at once (no unpaid mark); haggling with the odds at 100%; the kvass barrel pours two bottles. */
    private void market() {
        mx = fx + 40; my = fy - 5; mz = fz - 10;
        test.command("forceload add " + (mx - 4) + " " + (mz - 4) + " " + (mx + 30) + " " + (mz + 26));
        test.command("gamemode survival " + name());
        test.command("clear " + name());
        test.command("tp " + name() + " " + mat(1, 0, 10) + " -90 15");
        test.await("pause: the market's chunks load", 3, () -> false, () -> {
            test.command("place template mobtalk:city/ours/market/rynok " + mx + " " + my + " " + mz);
            test.await("vendors come to the market", 40, () -> vendors().size() >= 8, () -> {
                test.check(vendors().size() >= 8, "vendors came to the market (" + vendors().size() + ")");
                test.command("tp " + name() + " " + mat(1, 0, 10) + " -90 12");
                shot("mobtalk-market-entrance.png", () -> {
                    test.command("tp " + name() + " " + mat(8, 0, 6) + " -40 25");
                    shot("mobtalk-market-row.png", () -> {
                        test.command("tp " + name() + " " + mat(12, 1, 7) + " 180 28");
                        shot("mobtalk-market-grannies.png", () -> {
                            test.command("gamemode spectator " + name());
                            test.command("tp " + name() + " " + mat(12, 12, -7) + " 0 42");
                            shot("mobtalk-market-overview.png", () -> { test.command("gamemode survival " + name()); marketBuy(); });
                        });
                    });
                });
            });
        });
    }

    private ShopContent.DisplayBlockEntity stall;
    private boolean dealsWere;

    /** Deals: every lane of two emeralds or more on «акция» (30% off, the old price kept), and one «товар дня» in the shop at half. */
    private void deals() {
        int promo = test.bridge().config.shopPromoPercent;
        test.bridge().config.shopDeals = true;
        test.bridge().config.shopPromoPercent = 100;
        int[] seen = onServer(() -> {
            List<ShopContent.DisplayBlockEntity> all = new java.util.ArrayList<>();
            for (int x = -6; x <= 6; x++) if (display(x, -5) != null) all.add(display(x, -5));
            for (int x = -4; x <= 4; x++) if (display(x, -1) != null) all.add(display(x, -1));
            for (int round = 0; round < 2; round++) for (ShopContent.DisplayBlockEntity d : all) io.github.mobtalk.game.MarketController.stock(world(), d);
            int day = 0, promoted = 0, wrong = 0;
            for (ShopContent.DisplayBlockEntity d : all) for (ShopContent.DisplayBlockEntity.Lane l : d.lanes) {
                if (l.goods.isEmpty()) continue;
                if (l.deal == 2) { day++; if (l.price != Math.max(1, Math.round(l.was * 0.5f))) wrong++; }
                else if (l.deal == 1) { promoted++; if (l.price != Math.max(1, Math.round(l.was * 0.7f))) wrong++; }
                else if (l.was >= 2) wrong++;
            }
            return new int[] {day, promoted, wrong};
        });
        test.check(seen[0] == 1, "one «товар дня» in the shop (" + seen[0] + ")");
        test.check(seen[1] > 0 && seen[2] == 0, "the other lanes are on «акция» at 70% (" + seen[1] + " lanes, " + seen[2] + " wrong)");
        test.command("gamemode spectator " + name());
        test.command("tp " + name() + " " + at(-3, 0, -3) + " 180 25");
        test.owner.client.inGameHud.getChatHud().clear(false);
        shot("mobtalk-shop-deals-wall.png", () -> {
            test.command("tp " + name() + " " + at(0, 0, 1) + " 180 10");
            shot("mobtalk-shop-deals-gondola.png", () -> {
                test.bridge().config.shopPromoPercent = promo;
                test.bridge().config.shopDeals = dealsWere;
                test.command("gamemode survival " + name());
                kiosks();
            });
        });
    }

    private int kx, kz;
    private BlockPos kpos(int x, int z) { return new BlockPos(kx + x, my + 5, kz + z); }
    private String kat(int x, int y, int z) { return (kx + x) + " " + (my + 5 + y) + " " + (kz + z); }

    /** The bus stop with its kiosks: a vendor comes to each window, the shawarma is paid on the spot, the newspaper reads. */
    private void kiosks() {
        kx = fx + 40; kz = fz + 16;
        if (my == 0) my = fy - 5;
        test.command("forceload add " + (kx - 4) + " " + (kz - 4) + " " + (kx + 30) + " " + (kz + 26));
        test.command("tp " + name() + " " + kat(1, 0, 10) + " -90 15");
        test.await("pause: the kiosks' chunks load", 3, () -> false, () -> {
            test.command("place template mobtalk:city/ours/kiosk/ostanovka " + kx + " " + my + " " + kz);
            int[] windows = {2, 15, 18};
            test.await("a vendor comes to each kiosk", 40, () -> onServer(() -> {
                for (int z : windows) if (!io.github.mobtalk.game.MarketController.vendorNear(world(), kpos(5, z))) return false;
                return true;
            }), () -> {
                test.check(onServer(() -> {
                    for (int z : windows) if (!io.github.mobtalk.game.MarketController.vendorNear(world(), kpos(5, z))) return false;
                    return true;
                }), "a vendor came to each kiosk window");
                test.command("tp " + name() + " " + kat(1, 0, 5) + " -110 10");
                shot("mobtalk-kiosks.png", () -> {
                    test.command("tp " + name() + " " + kat(3, 0, 15) + " -90 30");
                    shot("mobtalk-kiosk-shawarma.png", () -> {
                        test.command("tp " + name() + " " + kat(3, 0, 2) + " -90 30");
                        shot("mobtalk-kiosk-press.png", () -> {
                            test.command("tp " + name() + " " + kat(3, 0, 18) + " -90 30");
                            shot("mobtalk-kiosk-beer.png", this::kioskBuy);
                        });
                    });
                });
            });
        });
    }

    private void kioskBuy() {
        ShopContent.DisplayBlockEntity window = onServer(() -> world().getBlockEntity(kpos(5, 15)) instanceof ShopContent.DisplayBlockEntity d ? d : null);
        test.check(window != null && window.display() == ShopContent.Display.KIOSK_SHAWARMA, "the shawarma window is a display");
        if (window == null) { realShop(); return; }
        onServer(() -> { io.github.mobtalk.game.MarketController.stock(world(), window); return true; });
        test.command("clear " + name());
        test.command("give " + name() + " minecraft:emerald 10");
        test.await("emeralds for the kiosk", 5, () -> count(Items.EMERALD) == 10, () -> {
            int price = window.lanes[0].price, lot = window.lanes[0].lot;
            net.minecraft.item.Item item = window.lanes[0].goods.getItem();
            test.server().execute(() -> ShopController.take(world(), window, 0, player()));
            test.await("bought at the kiosk", 5, () -> count(item) > 0, () -> {
                test.check(count(item) == lot && count(Items.EMERALD) == 10 - price && unpaid() == 0,
                        "the kiosk sells on the spot (" + count(item) + " " + item + " for " + (10 - count(Items.EMERALD)) + ")");
                test.command("give " + name() + " mobtalk:newspaper 1");
                test.await("a newspaper", 5, () -> count(io.github.mobtalk.game.ShopFoods.ITEMS.get("newspaper")) == 1, () -> {
                    int lines = chat.size();
                    test.server().execute(() -> io.github.mobtalk.game.ShopFoods.ITEMS.get("newspaper").use(world(), player(), net.minecraft.util.Hand.MAIN_HAND));
                    test.await("the newspaper is read", 5, () -> chat.size() > lines, () -> {
                        test.check(chat.size() > lines, "the newspaper gives a headline (" + chat.get(chat.size() - 1) + ")");
                        test.command("forceload remove " + (kx - 4) + " " + (kz - 4) + " " + (kx + 30) + " " + (kz + 26));
                        realShop();
                    });
                });
            });
        });
    }

    private void marketBuy() {
        stall = onServer(() -> {
            for (int z : new int[] {9, 4, 12, 17}) for (int x = 6; x <= 20; x++)
                if (world().getBlockEntity(mpos(x, z)) instanceof ShopContent.DisplayBlockEntity d && d.display().market()
                        && io.github.mobtalk.game.MarketController.vendorNear(world(), d.getPos())) {
                    io.github.mobtalk.game.MarketController.stock(world(), d);
                    if (!d.lanes[0].goods.isEmpty() && d.lanes[0].stock > 0) return d;
                }
            return null;
        });
        test.check(stall != null, "a stall with its vendor and goods");
        if (stall == null) { deals(); return; }
        test.command("give " + name() + " minecraft:emerald 10");
        test.await("emeralds for the market", 5, () -> count(Items.EMERALD) == 10, () -> {
            int price = stall.lanes[0].price, lot = stall.lanes[0].lot;
            net.minecraft.item.Item item = stall.lanes[0].goods.getItem();
            int before = count(item);
            mark();
            test.server().execute(() -> ShopController.take(world(), stall, 0, player()));
            test.await("bought at the stall", 5, () -> count(item) > before, () -> {
                test.check(count(Items.EMERALD) == 10 - price, "the vendor took " + price + " emeralds on the spot (" + (10 - count(Items.EMERALD)) + ")");
                test.check(count(item) - before == lot && unpaid() == 0, "the goods are ours, no unpaid mark (" + (count(item) - before) + ")");
                int saved = test.bridge().config.marketHagglePercent;
                test.bridge().config.marketHagglePercent = 100;
                int emeralds = count(Items.EMERALD), had = count(item);
                mark();
                test.server().execute(() -> { player().setSneaking(true); ShopController.take(world(), stall, 0, player()); player().setSneaking(false); });
                test.await("haggled", 5, () -> count(item) > had, () -> {
                    test.bridge().config.marketHagglePercent = saved;
                    boolean cheaper = emeralds - count(Items.EMERALD) == price - 1 && price > 1;
                    boolean extra = price == 1 && count(item) - had > lot;
                    test.check(cheaper || extra, "haggling gets an emerald off or a little extra (paid " + (emeralds - count(Items.EMERALD)) + ", got " + (count(item) - had) + ")");
                    kvass();
                });
            });
        });
    }

    private void kvass() {
        net.minecraft.item.Item kvass = io.github.mobtalk.game.ShopFoods.ITEMS.get("kvass");
        test.server().execute(() -> io.github.mobtalk.game.MarketController.kvass(world(), mpos(5, 2), player()));
        test.await("kvass poured", 5, () -> count(kvass) == 2, () -> {
            test.check(count(kvass) == 2, "the kvass barrel pours two bottles");
            test.command("tp " + name() + " " + mat(8, 1, 6) + " 125 25");
            shot("mobtalk-market-kvass.png", () -> {
                test.command("forceload remove " + (mx - 4) + " " + (mz - 4) + " " + (mx + 30) + " " + (mz + 26));
                deals();
            });
        });
    }

    private void realShop() {
        String spot = System.getProperty("mobtalk.selftest.shopAt", "");
        if (spot.isBlank()) { finish(); return; }
        String[] p = spot.split(",");
        int x = Integer.parseInt(p[0].trim()), y = Integer.parseInt(p[1].trim()), z = Integer.parseInt(p[2].trim());
        test.command("gamemode spectator " + name());
        test.command("tp " + name() + " " + (x + 2) + " " + (y + 1) + " " + z + " -90 20");
        test.await("pause: the city's staff come and the goods are stocked", 12, () -> false, () -> shot("mobtalk-shop-city-east.png", () -> {
            test.command("tp " + name() + " " + (x + 2) + " " + (y + 1) + " " + z + " 90 20");
            shot("mobtalk-shop-city-west.png", () -> {
                test.command("tp " + name() + " " + (x - 2) + " " + (y + 1) + " " + z + " -90 20");
                shot("mobtalk-shop-city-east2.png", () -> {
                    test.command("tp " + name() + " " + (x - 2) + " " + (y + 1) + " " + z + " 90 20");
                    shot("mobtalk-shop-city-west2.png", () -> {
                        test.check(!staff(true).isEmpty() || onServer(() -> !world().getEntitiesByClass(VillagerEntity.class, new Box(x, y, z, x + 1, y + 1, z + 1).expand(30), v -> ShopContent.cashier(v)).isEmpty()),
                                "the city supermarket got its cashiers");
                        test.command("gamemode survival " + name());
                        finish();
                    });
                });
            });
        }));
    }

    private void shot(String file, Runnable then) {
        test.await("pause: the camera settles", 2, () -> { steady.run(); return false; }, () -> {
            ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
            test.await("pause: after the screenshot", 1, () -> false, then);
        });
    }

    private void finish() {
        test.log("shop sounds heard: " + heardSounds);
        if (heardSounds.isEmpty()) test.log("(no sound device here: the sounds were not played, only checked)");
        else test.check(heardSounds.contains("shop.beep") && heardSounds.contains("shop.drawer"), "the till beeps and rings (" + heardSounds + ")");
        test.command("forceload remove " + (fx - 10) + " " + (fz - 8) + " " + (fx + 10) + " " + (fz + 34));
        test.command("gamerule doDaylightCycle true"); test.command("gamerule doMobSpawning true");
        test.await("pause: shop stage done", 2, () -> false, next);
    }
}
