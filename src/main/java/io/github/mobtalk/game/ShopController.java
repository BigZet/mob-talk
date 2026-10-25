package io.github.mobtalk.game;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.ShopCatalog;
import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.VillagerData;
import net.minecraft.village.VillagerType;
import net.minecraft.world.poi.PointOfInterestStorage;

/** How a city supermarket works: goods are taken from a display (into the basket when the player has one) marked unpaid, put back
 * on their display, paid in emeralds at a staffed till or a self-checkout; the till keeps a cashier, the guard's desk a guard, each
 * a newcomer when the post stands empty. Unpaid goods carried past the anti-theft gate or out of the shop set the guard after the
 * player: caught, the goods go back; got away, the goods are the player's and the city raises its alarm against them. Server
 * thread only; nothing kept but the displays' stock (in their block entities) and the marks on the items. */
public final class ShopController {
    private ShopController() { }

    static final String UNPAID = "mobtalk_shop", BASKET = "mobtalk_basket";
    /** How far from a till its cashier may stand and still serve, and how far the theft watch looks for shop blocks around a player. */
    static final double SERVE_REACH = 4;
    /** How far the till's own cashier may have strayed and still be called back to serve. */
    static final double CALL_REACH = 16;
    static final int SHOP_REACH = 6, STAFF_RADIUS = 48;
    static final long STAFF_PERIOD = 100, WATCH_PERIOD = 10, STAFF_RETRY_TICKS = 2400;
    /** A caught thief is let go after this long beside the guard; a guard gives up a chase this far from the desk. */
    static final double CATCH_REACH = 2.2, CHASE_LIMIT = 40;
    private static final MobTalkConfig DEFAULTS = new MobTalkConfig();
    private static final Random RANDOM = new Random();

    /** A player watched for unpaid goods: since when they are outside the shop, whether the guard is after them, the shop they left. */
    private static final class Watch { long outsideSince = -1; boolean alerted; UUID guard; BlockPos shop; long lastWarn = Long.MIN_VALUE; long glitchUntil; }
    private static final Map<UUID, Watch> WATCHES = new HashMap<>();
    /** Posts left empty: when a newcomer may come. */
    private static final Map<BlockPos, Long> VACANT = new HashMap<>();
    private static final Set<Block> SHOP = new HashSet<>();
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        SHOP.addAll(ShopContent.SHOP_BLOCKS);
        ServerTickEvents.END_WORLD_TICK.register(ShopController::tick);
    }

    static MobTalkConfig config(ServerWorld world) {
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        MobTalkConfig config = bridge != null ? bridge.config : ServerConfig.get();
        return config == null ? DEFAULTS : config;
    }

    // ---- The unpaid mark ----

    public static boolean unpaid(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (ShopContent.carrier(stack)) return !basketContents(stack).isEmpty();
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data != null && data.contains(UNPAID);
    }

    private static NbtCompound mark(ItemStack stack) {
        NbtComponent data = stack.get(DataComponentTypes.CUSTOM_DATA);
        return data == null ? null : data.copyNbt().getCompound(UNPAID);
    }

    static ItemStack unpaidStack(ItemStack goods, int count, int price, int lot, BlockPos shop) {
        ItemStack stack = goods.copyWithCount(count);
        NbtCompound mark = new NbtCompound();
        mark.putInt("price", price); mark.putInt("lot", lot); mark.putLong("at", shop.asLong());
        NbtCompound root = new NbtCompound();
        root.put(UNPAID, mark);
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root);
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(Text.translatable("mobtalk.shop.unpaid", price, lot)
                .styled(s -> s.withItalic(false).withColor(Formatting.RED)))));
        return stack;
    }

    /** The same goods with the mark gone. */
    static ItemStack paid(ItemStack stack) {
        ItemStack out = stack.copy();
        NbtComponent data = out.get(DataComponentTypes.CUSTOM_DATA);
        if (data != null) {
            NbtCompound nbt = data.copyNbt();
            nbt.remove(UNPAID);
            if (nbt.isEmpty()) out.remove(DataComponentTypes.CUSTOM_DATA); else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, out, nbt);
        }
        out.remove(DataComponentTypes.LORE);
        return out;
    }

    // ---- The basket ----

    /** The goods in a basket, each with its unpaid mark. */
    public static List<ItemStack> basketContents(ItemStack basket) {
        List<ItemStack> out = new ArrayList<>();
        NbtComponent data = basket.get(DataComponentTypes.CUSTOM_DATA);
        if (data == null || !data.contains(BASKET)) return out;
        NbtList list = data.copyNbt().getList(BASKET, NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound one = list.getCompound(i);
            var item = Registries.ITEM.get(Identifier.tryParse(one.getString("item")));
            if (item == Items.AIR || one.getInt("count") <= 0) continue;
            out.add(unpaidStack(new ItemStack(item), one.getInt("count"), one.getInt("price"), one.getInt("lot"), BlockPos.fromLong(one.getLong("at"))));
        }
        return out;
    }

    private static void setBasket(ItemStack basket, List<ItemStack> goods) {
        NbtList list = new NbtList();
        for (ItemStack stack : goods) {
            NbtCompound mark = mark(stack);
            if (mark == null) continue;
            NbtCompound one = new NbtCompound();
            one.putString("item", Registries.ITEM.getId(stack.getItem()).toString());
            one.putInt("count", stack.getCount());
            one.putInt("price", mark.getInt("price")); one.putInt("lot", mark.getInt("lot")); one.putLong("at", mark.getLong("at"));
            list.add(one);
        }
        NbtComponent data = basket.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound nbt = data == null ? new NbtCompound() : data.copyNbt();
        if (list.isEmpty()) nbt.remove(BASKET); else nbt.put(BASKET, list);
        if (nbt.isEmpty()) basket.remove(DataComponentTypes.CUSTOM_DATA); else NbtComponent.set(DataComponentTypes.CUSTOM_DATA, basket, nbt);
    }

    /** Puts goods into the basket, merging with the same goods from the same display; false when the basket is full. */
    private static boolean addToBasket(ItemStack basket, ItemStack goods) {
        List<ItemStack> inside = basketContents(basket);
        for (ItemStack stack : inside)
            if (ItemStack.areItemsAndComponentsEqual(stack, goods) && stack.getCount() + goods.getCount() <= stack.getMaxCount()) {
                stack.increment(goods.getCount());
                setBasket(basket, inside);
                return true;
            }
        if (inside.size() >= ShopContent.lots(basket)) return false;
        inside.add(goods);
        setBasket(basket, inside);
        return true;
    }

    /** Sneak-use: the basket's goods go to the inventory (still unpaid unless {@code pay}). */
    static void emptyBasket(ItemStack basket, PlayerEntity player, boolean pay) {
        for (ItemStack goods : basketContents(basket)) player.getInventory().offerOrDrop(pay ? paid(goods) : goods);
        setBasket(basket, List.of());
    }

    private static ItemStack basketOf(PlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        if (ShopContent.carrier(player.getOffHandStack())) return player.getOffHandStack();
        for (int i = 0; i < inventory.size(); i++) if (ShopContent.carrier(inventory.getStack(i))) return inventory.getStack(i);
        return ItemStack.EMPTY;
    }

    /** The stack of baskets: one basket to a player who has none; an empty one handed back goes on the stack. */
    public static void basketStack(ServerWorld world, BlockPos pos, ServerPlayerEntity player) { carrierStack(world, pos, player, ShopContent.BASKET, "basket"); }

    /** The cart corral by the entrance: a cart (more room than a basket) on the same terms. */
    public static void cartStack(ServerWorld world, BlockPos pos, ServerPlayerEntity player) { carrierStack(world, pos, player, ShopContent.CART, "cart"); }

    private static void carrierStack(ServerWorld world, BlockPos pos, ServerPlayerEntity player, net.minecraft.item.Item kind, String key) {
        if (!config(world).cityShops) return;
        ItemStack held = player.getMainHandStack();
        if (held.isOf(kind)) {
            if (!basketContents(held).isEmpty()) { player.sendMessage(Text.translatable("mobtalk.shop.basket.full_back").formatted(Formatting.YELLOW), true); return; }
            held.decrement(1);
            player.sendMessage(Text.translatable("mobtalk.shop." + key + ".back"), true);
            world.playSound(null, pos, kind == ShopContent.CART ? SoundEvents.BLOCK_CHAIN_PLACE : SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.6f, 1.3f);
            return;
        }
        if (!basketOf(player).isEmpty()) { player.sendMessage(Text.translatable("mobtalk.shop.basket.have"), true); return; }
        player.getInventory().offerOrDrop(new ItemStack(kind));
        player.sendMessage(Text.translatable("mobtalk.shop." + key + ".took"), true);
        world.playSound(null, pos, kind == ShopContent.CART ? SoundEvents.BLOCK_CHAIN_HIT : SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.6f, 1.4f);
    }

    // ---- The displays ----

    /** Every five seconds per display: first stocking, the delivery when it is due, the price after the settings. */
    static void tickDisplay(ServerWorld world, ShopContent.DisplayBlockEntity display) {
        MobTalkConfig config = config(world);
        boolean changed = false;
        if (!display.stocked) { display.stockDefaults(); changed = true; }
        long now = world.getTime(), every = Math.max(1, config.shopRestockMinutes) * 1200L;
        if (display.restocked == Long.MIN_VALUE) { display.restocked = now; changed = true; }
        if (now - display.restocked >= every) {
            display.restocked = now;
            for (ShopContent.DisplayBlockEntity.Lane lane : display.lanes)
                if (!lane.goods.isEmpty() && lane.stock < display.display().capacity()) { lane.stock = display.display().capacity(); changed = true; }
        }
        for (ShopContent.DisplayBlockEntity.Lane lane : display.lanes) {
            int price = ShopCatalog.scaled(lane.base, config.shopPricePercent);
            if (price != lane.price) { lane.price = price; changed = true; }
        }
        if (changed) display.changed();
        ShopContent.Display kind = display.display();
        if (config.shopSounds && (kind == ShopContent.Display.FRIDGE || kind == ShopContent.Display.FREEZER) && RANDOM.nextInt(3) == 0
                && world.getClosestPlayer(display.getPos().getX() + 0.5, display.getPos().getY(), display.getPos().getZ() + 0.5, 12, false) != null)
            world.playSound(null, display.getPos(), ShopContent.SOUND_HUM, SoundCategory.BLOCKS, 0.16f, 0.9f + RANDOM.nextFloat() * 0.2f);
    }

    // ---- Sounds ----

    /** A sound to play a few ticks from now (the scanner's beeps one after another). */
    private record Later(ServerWorld world, BlockPos pos, net.minecraft.sound.SoundEvent sound, float volume, float pitch, long at) { }
    private static final List<Later> LATER = new ArrayList<>();
    /** When the shop of this guard's desk may next play its music; where each player was half a second ago (their cart rattles). */
    private static final Map<BlockPos, Long> MUSIC = new HashMap<>();
    private static final Map<UUID, Vec3d> LAST_AT = new HashMap<>();

    private static void later(ServerWorld world, BlockPos pos, net.minecraft.sound.SoundEvent sound, float volume, float pitch, long at) {
        LATER.add(new Later(world, pos.toImmutable(), sound, volume, pitch, at));
    }

    /** The shop's air, played from the guard's desk (one to a shop): music now and then, and the PA chime. */
    private static void ambience(ServerWorld world, BlockPos desk, long now) {
        if (!config(world).shopSounds) return;
        BlockPos at = desk.up(2);
        Long next = MUSIC.get(desk);
        if (next == null) { MUSIC.put(desk.toImmutable(), now + 100 + RANDOM.nextInt(400)); return; }
        if (now >= next) {
            world.playSound(null, at, ShopContent.SOUND_MUSIC, SoundCategory.RECORDS, 0.45f, 1f);
            MUSIC.put(desk.toImmutable(), now + 360 + 400 + RANDOM.nextInt(800)); // the tune is 18 s; a pause of 20..60 s
        } else if (RANDOM.nextInt(18) == 0) {
            world.playSound(null, at, ShopContent.SOUND_CHIME, SoundCategory.BLOCKS, 0.55f, 1f);
        }
    }

    /** A cart pushed along rattles on the tiles. */
    private static void rattle(ServerWorld world, ServerPlayerEntity player) {
        boolean cart = player.getMainHandStack().isOf(ShopContent.CART) || player.getOffHandStack().isOf(ShopContent.CART);
        Vec3d here = player.getPos();
        Vec3d before = cart ? LAST_AT.put(player.getUuid(), here) : LAST_AT.remove(player.getUuid());
        if (!cart || before == null || !player.isOnGround()) return;
        double moved = here.subtract(before).horizontalLength();
        if (moved > 0.4) world.playSound(null, player.getBlockPos(), ShopContent.SOUND_CART, SoundCategory.PLAYERS, (float) Math.min(0.5, 0.2 + moved * 0.08), 0.9f + RANDOM.nextFloat() * 0.2f);
    }

    public static void take(ServerWorld world, ShopContent.DisplayBlockEntity display, int index, ServerPlayerEntity player) {
        if (!config(world).cityShops) return;
        if (!display.stocked) tickDisplay(world, display);
        ShopContent.DisplayBlockEntity.Lane lane = display.lanes[Math.min(index, display.lanes.length - 1)];
        if (lane.goods.isEmpty()) return;
        if (lane.stock <= 0) {
            long left = Math.max(0, Math.max(1, config(world).shopRestockMinutes) * 1200L - (world.getTime() - display.restocked));
            player.sendMessage(Text.translatable("mobtalk.shop.empty", lane.goods.getName(), Math.max(1, (left + 1199) / 1200)).formatted(Formatting.YELLOW), true);
            return;
        }
        ItemStack goods = unpaidStack(lane.goods, lane.lot, lane.price, lane.lot, display.getPos());
        ItemStack basket = basketOf(player);
        boolean inBasket = !basket.isEmpty() && addToBasket(basket, goods.copy());
        if (!inBasket) player.getInventory().offerOrDrop(goods);
        lane.stock--;
        display.changed();
        world.playSound(null, display.getPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.2f);
        MutableText line = Text.translatable("mobtalk.shop.took", lane.goods.getName(), lane.lot, lane.price);
        if (inBasket) line.append(Text.translatable("mobtalk.shop.took.basket", basketContents(basket).size()));
        player.sendMessage(line, true);
    }

    /** Unpaid goods from this display, used on it: the whole lots go back on its lane. */
    static boolean putBack(ServerWorld world, ShopContent.DisplayBlockEntity display, int index, ServerPlayerEntity player, ItemStack stack) {
        NbtCompound mark = mark(stack);
        if (mark == null || mark.getLong("at") != display.getPos().asLong()) return false;
        for (ShopContent.DisplayBlockEntity.Lane lane : display.lanes) {
            if (lane.goods.isEmpty() || !lane.goods.isOf(stack.getItem())) continue;
            int lots = stack.getCount() / Math.max(1, lane.lot);
            if (lots <= 0) return false;
            stack.decrement(lots * lane.lot);
            lane.stock = Math.min(display.display().capacity(), lane.stock + lots);
            display.changed();
            player.sendMessage(Text.translatable("mobtalk.shop.put_back", lane.goods.getName()), true);
            world.playSound(null, display.getPos(), SoundEvents.ENTITY_ITEM_FRAME_ADD_ITEM, SoundCategory.PLAYERS, 0.5f, 1f);
            return true;
        }
        return false;
    }

    // ---- The tills ----

    /** All unpaid goods a player carries, loose and in baskets. */
    public static List<ItemStack> carried(PlayerEntity player) {
        List<ItemStack> out = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShopContent.carrier(stack)) out.addAll(basketContents(stack));
            else if (unpaid(stack)) out.add(stack);
        }
        return out;
    }

    public static int cost(List<ItemStack> goods) {
        List<ShopCatalog.Line> lines = new ArrayList<>();
        for (ItemStack stack : goods) {
            NbtCompound mark = mark(stack);
            if (mark != null) lines.add(new ShopCatalog.Line(stack.getCount(), mark.getInt("lot"), mark.getInt("price")));
        }
        return ShopCatalog.total(lines);
    }

    public static void checkout(ServerWorld world, BlockPos till, ServerPlayerEntity player, boolean self) {
        MobTalkConfig config = config(world);
        if (!config.cityShops) return;
        VillagerEntity cashier = self ? null : cashierAt(world, till);
        if (!self && cashier == null) { player.sendMessage(Text.translatable("mobtalk.shop.closed").formatted(Formatting.YELLOW), false); return; }
        if (cashier != null) cashier.getLookControl().lookAt(player);
        List<ItemStack> goods = carried(player);
        if (goods.isEmpty()) {
            ItemStack basket = basketOf(player);
            if (!basket.isEmpty()) { basket.decrement(1); say(world, player, cashier, self, "mobtalk.shop.say.basket_back"); }
            else say(world, player, cashier, self, "mobtalk.shop.say.nothing");
            return;
        }
        Watch watch = WATCHES.computeIfAbsent(player.getUuid(), k -> new Watch());
        long now = world.getTime();
        // The self-checkout's famous complaint, now and then; a second try goes through.
        if (self && now >= watch.glitchUntil && RANDOM.nextInt(100) < config.shopCheckoutGlitchPercent) {
            watch.glitchUntil = now + 200;
            player.sendMessage(Text.translatable("mobtalk.shop.glitch").formatted(Formatting.GOLD), false);
            world.playSound(null, till, SoundEvents.BLOCK_NOTE_BLOCK_BIT.value(), SoundCategory.BLOCKS, 0.8f, 0.6f);
            return;
        }
        watch.glitchUntil = 0;
        int cost = cost(goods);
        PlayerInventory inventory = player.getInventory();
        int emeralds = inventory.count(Items.EMERALD), blocks = inventory.count(Items.EMERALD_BLOCK);
        ShopCatalog.Payment payment = ShopCatalog.pay(cost, emeralds, blocks);
        if (payment == null) {
            say(world, player, cashier, self, "mobtalk.shop.say.short", cost, cost - ShopCatalog.wealth(emeralds, blocks));
            return;
        }
        remove(inventory, Items.EMERALD, payment.emeralds());
        remove(inventory, Items.EMERALD_BLOCK, payment.blocks());
        List<ItemStack> bought = new ArrayList<>();
        int count = 0;
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShopContent.carrier(stack)) {
                for (ItemStack in : basketContents(stack)) { bought.add(paid(in)); count += in.getCount(); }
                inventory.setStack(i, ItemStack.EMPTY); // the basket stays at the till
            } else if (unpaid(stack)) {
                bought.add(paid(stack)); count += stack.getCount();
                inventory.setStack(i, ItemStack.EMPTY);
            }
        }
        for (ItemStack stack : bought) inventory.offerOrDrop(stack);
        if (payment.change() > 0) inventory.offerOrDrop(new ItemStack(Items.EMERALD, payment.change()));
        WATCHES.remove(player.getUuid());
        if (config.shopSounds) {
            // A beep for each lot through the scanner, then the drawer rings open for the money.
            int beeps = Math.min(12, bought.size());
            for (int i = 0; i < beeps; i++) later(world, till, ShopContent.SOUND_BEEP, 0.35f, 1f, now + i * 5L);
            later(world, till, ShopContent.SOUND_DRAWER, 0.6f, 1f, now + beeps * 5L + 4);
        } else {
            world.playSound(null, till, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.BLOCKS, 0.7f, 1.6f);
            world.playSound(null, till, SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.BLOCKS, 0.4f, 0.8f);
        }
        say(world, player, cashier, self, "mobtalk.shop.say.paid", cost, count);
        if (payment.change() > 0) player.sendMessage(Text.translatable("mobtalk.shop.change", payment.change()).formatted(Formatting.GREEN), true);
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (cashier != null && bridge != null && bridge.memory.known(cashier.getUuid()))
            bridge.event(cashier, "Пробила покупку игроку " + MobGameBridge.playerName(player) + " на " + cost + " изумр.");
    }

    private static void remove(PlayerInventory inventory, net.minecraft.item.Item item, int amount) {
        for (int i = 0; i < inventory.size() && amount > 0; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isOf(item) || unpaid(stack)) continue;
            int take = Math.min(amount, stack.getCount());
            stack.decrement(take);
            amount -= take;
        }
    }

    /** A line from the cashier (in the chat, with their name) or from the self-checkout's screen. */
    private static void say(ServerWorld world, ServerPlayerEntity player, VillagerEntity speaker, boolean self, String key, Object... args) {
        if (self) { player.sendMessage(Text.translatable("mobtalk.shop.screen").append(Text.translatable(key + ".self", args)).formatted(Formatting.AQUA), false); return; }
        String line = key + "." + RANDOM.nextInt(3);
        player.sendMessage(name(world, speaker).append(Text.translatable(line, args)), false);
    }

    /** «<Name> » before a line: the name the villager goes by in Mob Talk, else its trade (translated on the client). */
    private static MutableText name(ServerWorld world, VillagerEntity villager) {
        if (villager == null) return Text.literal("<?> ");
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        String name = bridge == null ? null : bridge.memory.displayName(villager.getUuid());
        return Text.literal("<").append(name == null || name.isBlank() ? villager.getName() : Text.literal(name)).append("> ");
    }

    /** Where the cashier stands: behind the till (the side away from its face). */
    static BlockPos stand(ServerWorld world, BlockPos post) {
        BlockState state = world.getBlockState(post);
        Direction facing = state.contains(HorizontalFacingBlock.FACING) ? state.get(HorizontalFacingBlock.FACING) : Direction.NORTH;
        return post.offset(facing.getOpposite());
    }

    static VillagerEntity cashierAt(ServerWorld world, BlockPos till) {
        Vec3d at = Vec3d.ofCenter(till);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(till).expand(SERVE_REACH), v -> v.isAlive() && !v.isSleeping() && ShopContent.cashier(v)))
            if (villager.squaredDistanceTo(at) <= SERVE_REACH * SERVE_REACH) return villager;
        // The till's own cashier wandered off a little: called back, serves from where they are.
        VillagerEntity own = worker(world, till, true);
        if (own != null && !own.isSleeping() && own.squaredDistanceTo(at) <= CALL_REACH * CALL_REACH) {
            walk(own, stand(world, till), 0.7f);
            return own;
        }
        return null;
    }

    // ---- Staff ----

    private static void tick(ServerWorld world) {
        if (ShopContent.CASHIER == null) return;
        long now = world.getTime();
        MobTalkConfig config = config(world);
        if (!config.cityShops) { WATCHES.clear(); return; }
        if (now % WATCH_PERIOD == 0) for (ServerPlayerEntity player : world.getPlayers()) watch(world, player, config, now);
        if (!LATER.isEmpty()) LATER.removeIf(sound -> {
            if (sound.world() != world || sound.at() > now) return false;
            world.playSound(null, sound.pos(), sound.sound(), SoundCategory.BLOCKS, sound.volume(), sound.pitch());
            return true;
        });
        if (now % WATCH_PERIOD == 5 && config.shopSounds) for (ServerPlayerEntity player : world.getPlayers()) rattle(world, player);
        if (now % STAFF_PERIOD == 37 && config.shopStaff) staff(world, now);
    }

    private static void staff(ServerWorld world, long now) {
        Set<BlockPos> seen = new HashSet<>();
        PointOfInterestStorage pois = world.getPointOfInterestStorage();
        for (ServerPlayerEntity player : world.getPlayers()) {
            pois.getInSquare(e -> e.matchesKey(ShopContent.CASH_REGISTER_POI) || e.matchesKey(ShopContent.GUARD_DESK_POI), player.getBlockPos(), STAFF_RADIUS,
                    PointOfInterestStorage.OccupationStatus.ANY).forEach(poi -> {
                BlockPos post = poi.getPos();
                if (!seen.add(post)) return;
                boolean till = world.getBlockState(post).isOf(ShopContent.CASH_REGISTER);
                if (!till) ambience(world, post, now);
                VillagerEntity worker = worker(world, post, till);
                if (worker == null) {
                    Long free = VACANT.get(post);
                    if (free == null) { VACANT.put(post, now); free = now; }
                    if (now >= free) {
                        worker = newcomer(world, post, till);
                        VACANT.put(post, now + STAFF_RETRY_TICKS);
                    }
                }
                if (worker == null) return;
                VACANT.remove(post);
                // Back to the post, unless the guard is after a thief.
                UUID id = worker.getUuid();
                boolean chasing = !till && WATCHES.values().stream().anyMatch(w -> w.alerted && id.equals(w.guard));
                BlockPos stand = stand(world, post);
                if (!chasing && !worker.isSleeping() && worker.squaredDistanceTo(Vec3d.ofBottomCenter(stand)) > 1.5 * 1.5) walk(worker, stand, 0.6f);
            });
        }
    }

    /** The villager whose job site is this post, or any of that trade standing by it. */
    private static VillagerEntity worker(ServerWorld world, BlockPos post, boolean till) {
        VillagerEntity near = null;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(post).expand(24), v -> v.isAlive() && (till ? ShopContent.cashier(v) : ShopContent.guard(v)))) {
            Optional<GlobalPos> site = MobActionController.registered(villager, MemoryModuleType.JOB_SITE) ? villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE) : Optional.empty();
            if (site.isPresent() && site.get().pos().equals(post)) return villager;
            if (near == null && villager.squaredDistanceTo(Vec3d.ofCenter(post)) <= 9) near = villager;
        }
        return near;
    }

    /** A grown villager of the local kind with the cashier's or the guard's trade, standing at the post, the post its job site. */
    private static VillagerEntity newcomer(ServerWorld world, BlockPos post, boolean till) {
        BlockPos feet = stand(world, post);
        if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() || !world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty()) {
            feet = null;
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos p = post.offset(side);
                if (world.getBlockState(p).getCollisionShape(world, p).isEmpty() && world.getBlockState(p.up()).getCollisionShape(world, p.up()).isEmpty()) { feet = p; break; }
            }
        }
        if (feet == null) return null;
        VillagerEntity worker = EntityType.VILLAGER.create(world);
        if (worker == null) return null;
        worker.refreshPositionAndAngles(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, world.random.nextFloat() * 360f, 0f);
        worker.initialize(world, world.getLocalDifficulty(feet), SpawnReason.MOB_SUMMONED, null);
        worker.setVillagerData(new VillagerData(VillagerType.forBiome(world.getBiome(post)), till ? ShopContent.CASHIER : ShopContent.SHOP_GUARD, 1));
        worker.setBreedingAge(0);
        worker.setPersistent();
        if (!till) {
            EntityAttributeInstance speed = worker.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(0.62); // a guard can run down a walking thief, not a sprinting one
        }
        world.spawnEntityAndPassengers(worker);
        var key = till ? ShopContent.CASH_REGISTER_POI : ShopContent.GUARD_DESK_POI;
        world.getPointOfInterestStorage().getPosition(entry -> entry.matchesKey(key), (entry, pos) -> pos.equals(post), post, 1);
        worker.reinitializeBrain(world);
        worker.getBrain().remember(MemoryModuleType.JOB_SITE, GlobalPos.create(world.getRegistryKey(), post));
        return worker;
    }

    private static void walk(VillagerEntity villager, BlockPos to, float speed) {
        if (to == null || !MobActionController.registered(villager, MemoryModuleType.WALK_TARGET)) return;
        villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, speed, 0), 40);
    }

    // ---- Theft ----

    /** Whether shop blocks stand around the player: the shop floor reaches this far from its displays, tills and gates. */
    static boolean inShop(ServerWorld world, BlockPos at) {
        for (BlockPos pos : BlockPos.iterate(at.add(-SHOP_REACH, -2, -SHOP_REACH), at.add(SHOP_REACH, 3, SHOP_REACH)))
            if (SHOP.contains(world.getBlockState(pos).getBlock())) return true;
        return false;
    }

    private static boolean atGate(ServerWorld world, BlockPos at) {
        for (BlockPos pos : BlockPos.iterate(at.add(-1, 0, -1), at.add(1, 1, 1)))
            if (world.getBlockState(pos).isOf(ShopContent.THEFT_GATE)) return true;
        return false;
    }

    private static void watch(ServerWorld world, ServerPlayerEntity player, MobTalkConfig config, long now) {
        if (player.isCreative() || player.isSpectator()) { WATCHES.remove(player.getUuid()); return; }
        List<ItemStack> goods = carried(player);
        boolean basket = !basketOf(player).isEmpty();
        if (goods.isEmpty() && !basket) { WATCHES.remove(player.getUuid()); return; }
        Watch watch = WATCHES.computeIfAbsent(player.getUuid(), k -> new Watch());
        if (goods.isEmpty()) {
            // An empty basket carried off is just forgotten: it goes back to the shop by itself.
            if (!inShop(world, player.getBlockPos()) && watch.outsideSince >= 0 && now - watch.outsideSince > 100) {
                ItemStack b = basketOf(player);
                b.decrement(1);
                player.sendMessage(Text.translatable("mobtalk.shop.basket.left").formatted(Formatting.GRAY), true);
            }
            if (!inShop(world, player.getBlockPos())) { if (watch.outsideSince < 0) watch.outsideSince = now; } else watch.outsideSince = -1;
            return;
        }
        NbtCompound first = mark(goods.get(0));
        watch.shop = first == null ? player.getBlockPos() : BlockPos.fromLong(first.getLong("at"));
        boolean inside = inShop(world, player.getBlockPos());
        boolean gate = atGate(world, player.getBlockPos());
        if (gate && now - watch.lastWarn > 40) {
            watch.lastWarn = now;
            if (config.shopSounds) world.playSound(null, player.getBlockPos(), ShopContent.SOUND_ALARM, SoundCategory.BLOCKS, 0.6f, 1f);
            else {
                world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.BLOCKS, 1f, 2f);
                world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.BLOCKS, 1f, 1.5f);
            }
            player.sendMessage(Text.translatable("mobtalk.shop.gate").formatted(Formatting.RED), true);
            alert(world, player, watch, now);
        }
        if (inside && !watch.alerted) { watch.outsideSince = -1; return; }
        if (!inside && watch.outsideSince < 0) { watch.outsideSince = now; alert(world, player, watch, now); }
        VillagerEntity guard = watch.guard == null ? null : world.getEntity(watch.guard) instanceof VillagerEntity v && v.isAlive() ? v : null;
        if (guard != null) {
            walk(guard, player.getBlockPos(), 0.9f);
            if (guard.getNavigation().isIdle()) guard.getNavigation().startMovingTo(player, 0.9); // the brain dropped the walk: on foot anyway
            guard.getLookControl().lookAt(player);
            if (guard.squaredDistanceTo(player) <= CATCH_REACH * CATCH_REACH) { caught(world, player, guard); return; }
            if (guard.squaredDistanceTo(Vec3d.ofCenter(watch.shop)) > CHASE_LIMIT * CHASE_LIMIT) guard = null;
        }
        if (inside) { watch.outsideSince = -1; return; } // back in the shop with the guard after them: they may still pay
        long outside = now - watch.outsideSince;
        boolean far = player.getBlockPos().getSquaredDistance(watch.shop) > 48 * 48;
        // A guard close behind keeps it from being a theft for a while yet (three times as long); one left far behind does not.
        boolean pursued = guard != null && guard.squaredDistanceTo(player) <= 16 * 16;
        if (outside >= Math.max(1, config.shopTheftSeconds) * 20L && (!pursued || far || outside >= config.shopTheftSeconds * 60L)) stolen(world, player, goods, watch);
    }

    /** The cashier shouts and the nearest guard of the shop sets off after the player. */
    private static void alert(ServerWorld world, ServerPlayerEntity player, Watch watch, long now) {
        if (watch.alerted) return;
        watch.alerted = true;
        VillagerEntity guard = null; double best = Double.MAX_VALUE;
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, new Box(watch.shop).expand(32), v -> v.isAlive() && !v.isSleeping() && ShopContent.guard(v))) {
            double d = v.squaredDistanceTo(player);
            if (d < best) { best = d; guard = v; }
        }
        VillagerEntity shouter = guard;
        if (shouter == null)
            for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, new Box(watch.shop).expand(24), v -> v.isAlive() && ShopContent.cashier(v))) { shouter = v; break; }
        if (guard != null) watch.guard = guard.getUuid();
        if (shouter != null) {
            Text line = name(world, shouter).append(Text.translatable("mobtalk.shop.say.stop." + RANDOM.nextInt(3)));
            for (ServerPlayerEntity p : world.getPlayers()) if (p.squaredDistanceTo(shouter) <= 32 * 32) p.sendMessage(line, false);
        }
    }

    /** The guard reached the thief: the unpaid goods are taken back to their displays, the basket too. */
    private static void caught(ServerWorld world, ServerPlayerEntity player, VillagerEntity guard) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShopContent.carrier(stack)) { for (ItemStack in : basketContents(stack)) restore(world, in); inventory.setStack(i, ItemStack.EMPTY); }
            else if (unpaid(stack)) { restore(world, stack); inventory.setStack(i, ItemStack.EMPTY); }
        }
        WATCHES.remove(player.getUuid());
        player.sendMessage(name(world, guard).append(Text.translatable("mobtalk.shop.say.caught." + RANDOM.nextInt(3))), false);
        player.sendMessage(Text.translatable("mobtalk.shop.caught").formatted(Formatting.RED), true);
        world.playSound(null, guard.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1f, 0.8f);
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge != null) {
            long millis = System.currentTimeMillis();
            String seen = "пытался вынести из магазина неоплаченный товар, охранник задержал";
            for (VillagerEntity witness : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(16), v -> v.isAlive() && bridge.memory.known(v.getUuid()) && v.canSee(player)))
                bridge.memory.recordWitnessed(witness.getUuid(), player.getUuid(), MobGameBridge.playerName(player), seen, -4, millis);
        }
    }

    /** Goods back on their display's lane (if it is loaded and still sells them). */
    private static void restore(ServerWorld world, ItemStack stack) {
        NbtCompound mark = mark(stack);
        if (mark == null) return;
        BlockPos at = BlockPos.fromLong(mark.getLong("at"));
        if (!world.isChunkLoaded(at) || !(world.getBlockEntity(at) instanceof ShopContent.DisplayBlockEntity display)) return;
        for (ShopContent.DisplayBlockEntity.Lane lane : display.lanes)
            if (!lane.goods.isEmpty() && lane.goods.isOf(stack.getItem())) {
                lane.stock = Math.min(display.display().capacity(), lane.stock + Math.max(1, stack.getCount() / Math.max(1, lane.lot)));
                display.changed();
                return;
            }
    }

    /** Got away: the goods are the player's now, the city knows who did it. */
    private static void stolen(ServerWorld world, ServerPlayerEntity player, List<ItemStack> goods, Watch watch) {
        int cost = cost(goods);
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> loot = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (ShopContent.carrier(stack)) { for (ItemStack in : basketContents(stack)) loot.add(paid(in)); setBasket(stack, List.of()); }
            else if (unpaid(stack)) { loot.add(paid(stack)); inventory.setStack(i, ItemStack.EMPTY); }
        }
        for (ItemStack stack : loot) inventory.offerOrDrop(stack);
        WATCHES.remove(player.getUuid());
        player.sendMessage(Text.translatable("mobtalk.shop.stolen", cost).formatted(Formatting.RED), false);
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge == null || !config(world).shopTheftAlarm) return;
        bridge.defense.theft(world, player, watch.shop);
        long millis = System.currentTimeMillis();
        String seen = "украл в магазине товар на " + cost + " изумр. и убежал";
        for (VillagerEntity witness : world.getEntitiesByClass(VillagerEntity.class, new Box(watch.shop).expand(24), v -> v.isAlive() && bridge.memory.known(v.getUuid())))
            bridge.memory.recordWitnessed(witness.getUuid(), player.getUuid(), MobGameBridge.playerName(player), seen, -8, millis);
    }

    static void clearRuntime() { WATCHES.clear(); VACANT.clear(); }
}
