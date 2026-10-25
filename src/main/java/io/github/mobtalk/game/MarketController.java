package io.github.mobtalk.game;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.ShopCatalog;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** The city market (рынок): no tills, no baskets. A lot taken from a stall is paid to its vendor there and then; sneaking while
 * taking is haggling (the vendor may knock an emerald off, or add a little extra; or not); vendors call out to passers-by; the
 * kvass barrel pours two bottles for an emerald. The vendors come and stand at their scales like the shop's staff
 * ({@link ShopController#staff}). Off in the settings: the stalls are just decoration. */
public final class MarketController {
    private MarketController() { }

    /** How far from a stall its vendor may stand; how long a vendor remembers being haggled with (ticks). */
    static final double VENDOR_REACH = 5;
    static final long HAGGLE_MEMORY = 1200;
    private static final Random RANDOM = new Random();
    private static final Map<UUID, Long> HAGGLED = new HashMap<>(), CALLED = new HashMap<>(), HEARD = new HashMap<>();

    static VillagerEntity vendorAt(ServerWorld world, BlockPos stall) {
        Vec3d at = Vec3d.ofCenter(stall);
        VillagerEntity best = null;
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, new Box(stall).expand(VENDOR_REACH), v -> v.isAlive() && !v.isSleeping() && ShopContent.vendor(v)))
            if (v.squaredDistanceTo(at) <= VENDOR_REACH * VENDOR_REACH && (best == null || v.squaredDistanceTo(at) < best.squaredDistanceTo(at))) best = v;
        return best;
    }

    /** For the self-test: is there a vendor by this stall; stock it now. */
    public static boolean vendorNear(ServerWorld world, BlockPos stall) { return vendorAt(world, stall) != null; }
    public static void stock(ServerWorld world, ShopContent.DisplayBlockEntity display) { ShopController.tickDisplay(world, display); }

    public static void buy(ServerWorld world, ShopContent.DisplayBlockEntity display, int index, ServerPlayerEntity player) {
        MobTalkConfig config = ShopController.config(world);
        if (!config.cityMarkets) return;
        if (!display.stocked) ShopController.tickDisplay(world, display);
        ShopContent.DisplayBlockEntity.Lane lane = display.lanes[Math.min(index, display.lanes.length - 1)];
        if (lane.goods.isEmpty()) return;
        VillagerEntity vendor = vendorAt(world, display.getPos());
        if (vendor == null) { player.sendMessage(Text.translatable("mobtalk.market.no_vendor").formatted(Formatting.YELLOW), true); return; }
        vendor.getLookControl().lookAt(player);
        if (lane.stock <= 0) { say(world, player, vendor, "mobtalk.market.say.sold_out", lane.goods.getName()); return; }
        int price = lane.price, count = lane.lot;
        if (player.isSneaking()) { // haggling
            long now = world.getTime();
            Long last = HAGGLED.get(vendor.getUuid());
            if (last != null && now - last < HAGGLE_MEMORY) say(world, player, vendor, "mobtalk.market.say.haggle_again");
            else {
                HAGGLED.put(vendor.getUuid(), now);
                if (RANDOM.nextInt(100) < config.marketHagglePercent) {
                    if (price > 1) { price--; say(world, player, vendor, "mobtalk.market.say.haggle_cheaper", price); }
                    else { count += Math.max(1, lane.lot / 2); say(world, player, vendor, "mobtalk.market.say.haggle_extra"); }
                } else say(world, player, vendor, "mobtalk.market.say.haggle_no");
            }
        }
        PlayerInventory inventory = player.getInventory();
        ShopCatalog.Payment payment = ShopCatalog.pay(price, inventory.count(Items.EMERALD), inventory.count(Items.EMERALD_BLOCK));
        if (payment == null) { say(world, player, vendor, "mobtalk.market.say.short", price); return; }
        ShopController.remove(inventory, Items.EMERALD, payment.emeralds());
        ShopController.remove(inventory, Items.EMERALD_BLOCK, payment.blocks());
        if (payment.change() > 0) inventory.offerOrDrop(new ItemStack(Items.EMERALD, payment.change()));
        inventory.offerOrDrop(lane.goods.copyWithCount(count));
        lane.stock--;
        display.changed();
        world.playSound(null, display.getPos(), SoundEvents.BLOCK_WOODEN_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.6f, 0.8f); // the scales
        world.playSound(null, display.getPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.1f);
        player.sendMessage(Text.translatable("mobtalk.market.bought", lane.goods.getName(), count, price).formatted(Formatting.GREEN), true);
        if (RANDOM.nextInt(3) == 0) say(world, player, vendor, "mobtalk.market.say.thanks");
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge != null && bridge.memory.known(vendor.getUuid()))
            bridge.event(vendor, "Продала на рынке " + MobGameBridge.playerName(player) + " " + lane.goods.getName().getString() + " за " + price + " изумр.");
    }

    /** The yellow barrel: two bottles of kvass for an emerald, poured by whoever minds it (or by the player, if nobody does). */
    public static void kvass(ServerWorld world, BlockPos barrel, ServerPlayerEntity player) {
        if (!ShopController.config(world).cityMarkets) return;
        PlayerInventory inventory = player.getInventory();
        ShopCatalog.Payment payment = ShopCatalog.pay(1, inventory.count(Items.EMERALD), inventory.count(Items.EMERALD_BLOCK));
        if (payment == null) { player.sendMessage(Text.translatable("mobtalk.market.kvass.short").formatted(Formatting.YELLOW), true); return; }
        ShopController.remove(inventory, Items.EMERALD, payment.emeralds());
        ShopController.remove(inventory, Items.EMERALD_BLOCK, payment.blocks());
        if (payment.change() > 0) inventory.offerOrDrop(new ItemStack(Items.EMERALD, payment.change()));
        inventory.offerOrDrop(new ItemStack(ShopFoods.ITEMS.get("kvass"), 2));
        world.playSound(null, barrel, SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8f, 0.8f);
        world.playSound(null, barrel, SoundEvents.ITEM_BOTTLE_FILL, SoundCategory.BLOCKS, 0.8f, 0.7f);
        player.sendMessage(Text.translatable("mobtalk.market.kvass.poured").formatted(Formatting.GOLD), true);
    }

    /** Now and then a vendor at the stall calls out to a player walking by. */
    static void calls(ServerWorld world, VillagerEntity vendor, long now) {
        if (!ShopController.config(world).marketCalls || vendor.isSleeping()) return;
        Long next = CALLED.get(vendor.getUuid());
        if (next != null && now < next) return;
        ServerPlayerEntity near = null;
        for (ServerPlayerEntity p : world.getPlayers())
            if (!p.isSpectator() && p.squaredDistanceTo(vendor) <= 8 * 8) { near = p; break; }
        if (near == null || RANDOM.nextInt(3) != 0) return;
        Long quiet = HEARD.get(near.getUuid()); // one call at a time for a player, not the whole row at once
        if (quiet != null && now < quiet) return;
        HEARD.put(near.getUuid(), now + 300 + RANDOM.nextInt(300));
        CALLED.put(vendor.getUuid(), now + 600 + RANDOM.nextInt(1200));
        vendor.getLookControl().lookAt(near);
        Text line = ShopController.name(world, vendor).append(Text.translatable("mobtalk.market.call." + RANDOM.nextInt(8)));
        for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(vendor) <= 16 * 16) p.sendMessage(line, false);
    }

    private static void say(ServerWorld world, ServerPlayerEntity player, VillagerEntity vendor, String key, Object... args) {
        player.sendMessage(ShopController.name(world, vendor).append(Text.translatable(key + "." + RANDOM.nextInt(3), args)), false);
    }
}
