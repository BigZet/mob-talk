package io.github.mobtalk.game;

import com.google.common.collect.ImmutableSet;
import com.mojang.serialization.MapCodec;
import io.github.mobtalk.core.ShopCatalog;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.world.poi.PointOfInterestHelper;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterestType;

/** The city supermarket's own content (tools/city/shop.py draws the models and textures and furnishes the «Универсам»):
 * displays that hold goods in lanes (the gondola, the bread rack, the refrigerated case, the chest freezer, the produce stand),
 * the staffed till and the self-checkout, the stack of baskets and the basket, the anti-theft gate and the guard's post; the
 * cashier (job site: the till) and the shop guard (job site: the post). What they do lives in {@link ShopController}.
 * Registered before any world loads, on both sides. */
public final class ShopContent {
    private ShopContent() { }

    /** A kind of display: its block id, how many lanes of goods it holds, which goods by default, and whether both its long sides
     * show goods (a gondola in the middle of the floor). */
    public enum Display {
        SHELF("shop_shelf", 3, ShopCatalog.Place.SHELF, true),
        BREAD("shop_bread_rack", 3, ShopCatalog.Place.BREAD, false),
        FRIDGE("shop_fridge", 3, ShopCatalog.Place.FRIDGE, false),
        FREEZER("shop_freezer", 2, ShopCatalog.Place.FREEZER, false),
        PRODUCE("shop_produce", 3, ShopCatalog.Place.PRODUCE, false),
        // the market (рынок): stalls under awnings and the old women's tables; paid to the vendor on the spot (MarketController)
        VEG_STALL("market_veg_stall", 3, ShopCatalog.Place.PRODUCE, false),
        MEAT_STALL("market_meat_stall", 3, ShopCatalog.Place.FREEZER, false),
        DAIRY_STALL("market_dairy_stall", 3, ShopCatalog.Place.FRIDGE, false),
        GRANNY_TABLE("market_granny_table", 2, ShopCatalog.Place.HOMEMADE, false);
        public final String id; public final int lanes; public final ShopCatalog.Place place; public final boolean twoSided;
        Display(String id, int lanes, ShopCatalog.Place place, boolean twoSided) { this.id = id; this.lanes = lanes; this.place = place; this.twoSided = twoSided; }

        /** A market display: no till, the vendor takes the money as the goods are handed over. */
        public boolean market() { return id.startsWith("market_"); }

        /** The lane under a point of the block in the north-facing model's frame (0..1 each way): tiers go by height, the freezer's
         * halves and the produce stand's crates go along the front. */
        public int lane(double x, double y) {
            return switch (this) {
                case FREEZER, GRANNY_TABLE -> x < 0.5 ? 0 : 1;
                case PRODUCE, VEG_STALL, MEAT_STALL, DAIRY_STALL -> Math.min(2, (int) (x * 3));
                default -> y < 5 / 16.0 ? 0 : y < 10 / 16.0 ? 1 : 2;
            };
        }
        /** The most lots a lane holds when full. */
        public int capacity() { return this == FREEZER ? 10 : this == GRANNY_TABLE ? 6 : 8; }
    }

    public static final Map<Display, Block> DISPLAYS = new EnumMap<>(Display.class);
    public static BlockEntityType<DisplayBlockEntity> DISPLAY_ENTITY;
    public static Block CASH_REGISTER, SELF_CHECKOUT, BASKET_STACK, CART_CORRAL, THEFT_GATE, GUARD_DESK;
    /** The market's fittings: the vendor's scales (their job site), the yellow kvass barrel, sacks of potatoes. */
    public static Block MARKET_SCALES, KVASS_BARREL, POTATO_SACKS;
    public static Item BASKET, CART;
    public static final Identifier CASH_REGISTER_ID = Identifier.of("mobtalk", "cash_register"), GUARD_DESK_ID = Identifier.of("mobtalk", "shop_guard_desk");
    public static final RegistryKey<PointOfInterestType> CASH_REGISTER_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, CASH_REGISTER_ID);
    public static final RegistryKey<PointOfInterestType> GUARD_DESK_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, GUARD_DESK_ID);
    public static VillagerProfession CASHIER, SHOP_GUARD, VENDOR;
    public static final Identifier VENDOR_ID = Identifier.of("mobtalk", "market_vendor");
    public static final RegistryKey<PointOfInterestType> VENDOR_POI = RegistryKey.of(RegistryKeys.POINT_OF_INTEREST_TYPE, VENDOR_ID);
    /** The shop's own sounds (tools/city/shop_sounds.py): scanner beep, till drawer, gate alarm, PA chime, fridge hum, cart, music. */
    public static net.minecraft.sound.SoundEvent SOUND_BEEP, SOUND_DRAWER, SOUND_ALARM, SOUND_CHIME, SOUND_HUM, SOUND_CART, SOUND_MUSIC;
    /** Every block of a shop, for «is the player still inside the shop». */
    public static final List<Block> SHOP_BLOCKS = new ArrayList<>();
    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    private static boolean registered;

    /** Outlines in the north-facing model (pixel boxes), kept in step with tools/city/shop.py. */
    static final Map<String, double[][]> OUTLINES = Map.ofEntries(
            Map.entry("shop_shelf", new double[][] {{0, 0, 1, 16, 16, 15}}),
            Map.entry("shop_bread_rack", new double[][] {{0, 0, 2, 16, 16, 15}}),
            Map.entry("shop_fridge", new double[][] {{0, 0, 1, 16, 16, 16}}),
            Map.entry("shop_freezer", new double[][] {{0, 0, 1, 16, 11, 15}}),
            Map.entry("shop_produce", new double[][] {{0, 0, 0, 16, 12, 16}}),
            Map.entry("cash_register", new double[][] {{0, 0, 1, 16, 12, 15}, {3, 12, 4, 13, 16, 12}}),
            Map.entry("self_checkout", new double[][] {{1, 0, 2, 15, 16, 14}}),
            Map.entry("basket_stack", new double[][] {{2, 0, 3, 14, 12, 13}}),
            Map.entry("cart_corral", new double[][] {{1, 0, 0, 15, 15, 16}}),
            Map.entry("theft_gate", new double[][] {{6, 0, 3, 10, 16, 13}}),
            Map.entry("shop_guard_desk", new double[][] {{0, 0, 2, 16, 12, 14}}),
            Map.entry("market_veg_stall", new double[][] {{0, 0, 1, 16, 13, 16}}),
            Map.entry("market_meat_stall", new double[][] {{0, 0, 1, 16, 12, 16}}),
            Map.entry("market_dairy_stall", new double[][] {{0, 0, 1, 16, 12, 16}}),
            Map.entry("market_granny_table", new double[][] {{1, 0, 2, 15, 10, 14}}),
            Map.entry("market_scales", new double[][] {{0, 0, 1, 16, 15, 16}}),
            Map.entry("kvass_barrel", new double[][] {{0, 0, 1, 16, 16, 15}}),
            Map.entry("potato_sacks", new double[][] {{1, 0, 1, 15, 12, 15}}));

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        for (Display display : Display.values()) {
            Block block = new DisplayBlock(settings(MapColor.WHITE, BlockSoundGroup.METAL, display == Display.FRIDGE ? 9 : 0), display);
            DISPLAYS.put(display, block(display.id, block, items));
        }
        CASH_REGISTER = block("cash_register", new ShopBlock(settings(MapColor.LIGHT_GRAY, BlockSoundGroup.METAL, 0), "cash_register"), items);
        SELF_CHECKOUT = block("self_checkout", new ShopBlock(settings(MapColor.LIGHT_GRAY, BlockSoundGroup.METAL, 4), "self_checkout"), items);
        BASKET_STACK = block("basket_stack", new ShopBlock(settings(MapColor.RED, BlockSoundGroup.WOOD, 0), "basket_stack"), items);
        CART_CORRAL = block("cart_corral", new ShopBlock(settings(MapColor.IRON_GRAY, BlockSoundGroup.CHAIN, 0), "cart_corral"), items);
        THEFT_GATE = block("theft_gate", new ShopBlock(settings(MapColor.GRAY, BlockSoundGroup.METAL, 0).noCollision(), "theft_gate"), items);
        GUARD_DESK = block("shop_guard_desk", new ShopBlock(settings(MapColor.BROWN, BlockSoundGroup.WOOD, 0), "shop_guard_desk"), items);
        MARKET_SCALES = block("market_scales", new ShopBlock(settings(MapColor.OAK_TAN, BlockSoundGroup.WOOD, 0), "market_scales"), items);
        KVASS_BARREL = block("kvass_barrel", new ShopBlock(settings(MapColor.YELLOW, BlockSoundGroup.METAL, 0), "kvass_barrel"), items);
        POTATO_SACKS = block("potato_sacks", new ShopBlock(settings(MapColor.BROWN, BlockSoundGroup.WOOL, 0), "potato_sacks"), items);
        DISPLAY_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of("mobtalk", "shop_display"),
                BlockEntityType.Builder.create(DisplayBlockEntity::new, DISPLAYS.values().toArray(Block[]::new)).build(null));
        BASKET = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "shopping_basket"), new BasketItem(new Item.Settings().maxCount(1), BASKET_LOTS));
        CART = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "shopping_cart"), new BasketItem(new Item.Settings().maxCount(1), CART_LOTS));
        items.add(BASKET);
        items.add(CART);
        ShopFoods.register();
        SOUND_BEEP = sound("shop.beep"); SOUND_DRAWER = sound("shop.drawer"); SOUND_ALARM = sound("shop.alarm"); SOUND_CHIME = sound("shop.chime");
        SOUND_HUM = sound("shop.hum"); SOUND_CART = sound("shop.cart"); SOUND_MUSIC = sound("shop.music");
        PointOfInterestHelper.register(CASH_REGISTER_ID, 1, 1, CASH_REGISTER);
        PointOfInterestHelper.register(GUARD_DESK_ID, 1, 1, GUARD_DESK);
        PointOfInterestHelper.register(VENDOR_ID, 1, 1, MARKET_SCALES, DISPLAYS.get(Display.GRANNY_TABLE));
        CASHIER = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "cashier"),
                new VillagerProfession("cashier", entry -> entry.matchesKey(CASH_REGISTER_POI), entry -> entry.matchesKey(CASH_REGISTER_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_LIBRARIAN));
        SHOP_GUARD = Registry.register(Registries.VILLAGER_PROFESSION, Identifier.of("mobtalk", "shop_guard"),
                new VillagerProfession("shop_guard", entry -> entry.matchesKey(GUARD_DESK_POI), entry -> entry.matchesKey(GUARD_DESK_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_ARMORER));
        VENDOR = Registry.register(Registries.VILLAGER_PROFESSION, VENDOR_ID,
                new VillagerProfession("market_vendor", entry -> entry.matchesKey(VENDOR_POI), entry -> entry.matchesKey(VENDOR_POI),
                        ImmutableSet.of(), ImmutableSet.of(), SoundEvents.ENTITY_VILLAGER_WORK_FARMER));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
    }

    private static net.minecraft.sound.SoundEvent sound(String id) {
        Identifier key = Identifier.of("mobtalk", id);
        return Registry.register(Registries.SOUND_EVENT, key, net.minecraft.sound.SoundEvent.of(key));
    }

    private static AbstractBlock.Settings settings(MapColor color, BlockSoundGroup sound, int light) {
        return AbstractBlock.Settings.create().mapColor(color).strength(2f, 6f).sounds(sound).nonOpaque().luminance(state -> light);
    }

    private static Block block(String id, Block block, List<Item> items) {
        Identifier key = Identifier.of("mobtalk", id);
        Registry.register(Registries.BLOCK, key, block);
        items.add(Registry.register(Registries.ITEM, key, new BlockItem(block, new Item.Settings())));
        SHOP_BLOCKS.add(block);
        BLOCKS.put(id, block);
        return block;
    }

    public static boolean cashier(net.minecraft.entity.passive.VillagerEntity villager) { return CASHIER != null && villager.getVillagerData().getProfession() == CASHIER; }
    public static boolean vendor(net.minecraft.entity.passive.VillagerEntity villager) { return VENDOR != null && villager.getVillagerData().getProfession() == VENDOR; }
    public static boolean guard(net.minecraft.entity.passive.VillagerEntity villager) { return SHOP_GUARD != null && villager.getVillagerData().getProfession() == SHOP_GUARD; }

    /** A point of a block's hit in the north-facing model's frame (x along the front, y up, z from the front back), 0..1. */
    static Vec3d local(BlockPos pos, Direction facing, Vec3d hit) {
        double wx = hit.x - pos.getX(), wy = hit.y - pos.getY(), wz = hit.z - pos.getZ();
        return switch (facing) {
            case SOUTH -> new Vec3d(1 - wx, wy, 1 - wz);
            case EAST -> new Vec3d(wz, wy, 1 - wx);
            case WEST -> new Vec3d(1 - wz, wy, wx);
            default -> new Vec3d(wx, wy, wz);
        };
    }

    /** A shop block that faces one side and does its work in {@link ShopController} when used. */
    public static class ShopBlock extends HorizontalFacingBlock {
        final String id;
        private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

        ShopBlock(AbstractBlock.Settings settings, String id) {
            super(settings);
            this.id = id;
            for (Direction d : Direction.Type.HORIZONTAL) shapes.put(d, CityContent.shape(OUTLINES.get(id), d));
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new ShopBlock(s, id)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shapes.get(state.get(FACING)); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()); }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) return id.equals("theft_gate") ? ActionResult.PASS : ActionResult.SUCCESS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;
            return switch (id) {
                case "cash_register" -> { ShopController.checkout((ServerWorld) world, pos, serverPlayer, false); yield ActionResult.CONSUME; }
                case "self_checkout" -> { ShopController.checkout((ServerWorld) world, pos, serverPlayer, true); yield ActionResult.CONSUME; }
                case "basket_stack" -> { ShopController.basketStack((ServerWorld) world, pos, serverPlayer); yield ActionResult.CONSUME; }
                case "kvass_barrel" -> { MarketController.kvass((ServerWorld) world, pos, serverPlayer); yield ActionResult.CONSUME; }
                case "cart_corral" -> { ShopController.cartStack((ServerWorld) world, pos, serverPlayer); yield ActionResult.CONSUME; }
                default -> ActionResult.PASS;
            };
        }
    }

    /** A display: its goods live in a {@link DisplayBlockEntity}, drawn by the client's renderer on the model's tiers. */
    public static class DisplayBlock extends BlockWithEntity {
        public static final net.minecraft.state.property.DirectionProperty FACING = HorizontalFacingBlock.FACING;
        public final Display display;
        private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

        DisplayBlock(AbstractBlock.Settings settings, Display display) {
            super(settings);
            this.display = display;
            for (Direction d : Direction.Type.HORIZONTAL) shapes.put(d, CityContent.shape(OUTLINES.get(display.id), d));
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }

        @Override protected MapCodec<? extends BlockWithEntity> getCodec() { return createCodec(s -> new DisplayBlock(s, display)); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shapes.get(state.get(FACING)); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()); }
        @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new DisplayBlockEntity(pos, state); }

        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
            return world.isClient ? null : validateTicker(type, DISPLAY_ENTITY, (w, p, s, be) -> be.serverTick((ServerWorld) w));
        }

        int lane(BlockState state, BlockPos pos, Vec3d hit) {
            Vec3d at = local(pos, state.get(FACING), hit);
            double x = at.x;
            if (display.twoSided && at.z > 0.5) x = 1 - x; // the gondola's far side mirrors the near one
            return display.lane(Math.max(0, Math.min(0.999, x)), Math.max(0, Math.min(0.999, at.y)));
        }

        @Override protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
            if (world.isClient) return ItemActionResult.SUCCESS;
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world.getBlockEntity(pos) instanceof DisplayBlockEntity shelf)) return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            int lane = lane(state, pos, hit.getPos());
            if (player.isCreative() && player.isSneaking() && !stack.isEmpty() && !ShopController.unpaid(stack)) {
                shelf.assign(lane, stack, (ServerWorld) world);
                player.sendMessage(Text.translatable("mobtalk.shop.assigned", stack.getName()).formatted(Formatting.YELLOW), true);
                return ItemActionResult.CONSUME;
            }
            if (ShopController.unpaid(stack) && ShopController.putBack((ServerWorld) world, shelf, lane, serverPlayer, stack)) return ItemActionResult.CONSUME;
            if (stack.isOf(BASKET) || stack.isEmpty() || hand == Hand.MAIN_HAND) {
                ShopController.take((ServerWorld) world, shelf, lane, serverPlayer);
                return ItemActionResult.CONSUME;
            }
            return ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) return ActionResult.SUCCESS;
            if (player instanceof ServerPlayerEntity serverPlayer && world.getBlockEntity(pos) instanceof DisplayBlockEntity shelf)
                ShopController.take((ServerWorld) world, shelf, lane(state, pos, hit.getPos()), serverPlayer);
            return ActionResult.CONSUME;
        }
    }

    /** The goods of one display: per lane the item, the lot (items for the price), the catalogue price, the price now (after the
     * settings' percentage) and how many lots are left. Stocked from the catalogue the first time it ticks (steady for its spot),
     * unless the structure or an admin named the goods; restocked to full every {@code shopRestockMinutes}. */
    public static class DisplayBlockEntity extends BlockEntity {
        public static final class Lane {
            public ItemStack goods = ItemStack.EMPTY;
            public int lot = 1, base = 1, price = 1, stock;
            /** 0 none, 1 «акция» (yellow tag, 30% off), 2 the shop's «товар дня» (half price). */
            public int deal, was;
        }
        public final Lane[] lanes;
        long restocked = Long.MIN_VALUE;
        boolean stocked;

        public DisplayBlockEntity(BlockPos pos, BlockState state) {
            super(DISPLAY_ENTITY, pos, state);
            Display display = state.getBlock() instanceof DisplayBlock block ? block.display : Display.SHELF;
            lanes = new Lane[display.lanes];
            for (int i = 0; i < lanes.length; i++) lanes[i] = new Lane();
        }

        public Display display() { return getCachedState().getBlock() instanceof DisplayBlock block ? block.display : Display.SHELF; }

        void serverTick(ServerWorld world) {
            if ((world.getTime() + pos.asLong()) % 100 != 0) return;
            ShopController.tickDisplay(world, this);
        }

        /** Fills empty lanes from the catalogue: the lanes of one display get different goods. */
        void stockDefaults() {
            stocked = true;
            long seed = pos.asLong() * 31 + display().ordinal();
            List<ShopCatalog.Goods> used = new ArrayList<>();
            for (int i = 0; i < lanes.length; i++) {
                if (!lanes[i].goods.isEmpty()) continue;
                ShopCatalog.Goods goods = ShopCatalog.pick(display().place, seed + i * 7919L);
                for (int k = 1; used.contains(goods) && k < 8; k++) goods = ShopCatalog.pick(display().place, seed + i * 7919L + k);
                used.add(goods);
                setGoods(i, goods.item(), goods.lot(), goods.price());
                lanes[i].stock = display().capacity();
            }
        }

        void setGoods(int lane, String item, int lot, int price) {
            Item it = Registries.ITEM.get(Identifier.of(item));
            lanes[lane].goods = new ItemStack(it);
            lanes[lane].lot = Math.max(1, Math.min(it.getMaxCount(), lot));
            lanes[lane].base = Math.max(1, price);
            lanes[lane].price = lanes[lane].base;
        }

        void assign(int lane, ItemStack stack, ServerWorld world) {
            String item = Registries.ITEM.getId(stack.getItem()).toString();
            ShopCatalog.Goods goods = ShopCatalog.of(item);
            if (goods != null) setGoods(lane, item, goods.lot(), goods.price());
            else setGoods(lane, item, Math.max(1, stack.getCount()), 1);
            lanes[lane].stock = display().capacity();
            stocked = true;
            ShopController.tickDisplay(world, this);
            changed();
        }

        void changed() {
            markDirty();
            if (world != null) world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_LISTENERS);
        }

        @Override protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.writeNbt(nbt, registries);
            NbtList list = new NbtList();
            for (Lane lane : lanes) {
                NbtCompound one = new NbtCompound();
                if (!lane.goods.isEmpty()) one.putString("item", Registries.ITEM.getId(lane.goods.getItem()).toString());
                one.putInt("lot", lane.lot); one.putInt("base", lane.base); one.putInt("price", lane.price); one.putInt("stock", lane.stock);
                if (lane.deal != 0) { one.putInt("deal", lane.deal); one.putInt("was", lane.was); }
                list.add(one);
            }
            nbt.put("lanes", list);
            nbt.putBoolean("stocked", stocked);
            nbt.putLong("restocked", restocked);
        }

        @Override protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.readNbt(nbt, registries);
            NbtList list = nbt.getList("lanes", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < Math.min(list.size(), lanes.length); i++) {
                NbtCompound one = list.getCompound(i);
                Lane lane = lanes[i];
                if (one.contains("item")) {
                    Item item = Registries.ITEM.get(Identifier.tryParse(one.getString("item")));
                    lane.goods = item == net.minecraft.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
                }
                if (one.contains("lot")) lane.lot = Math.max(1, one.getInt("lot"));
                if (one.contains("base")) lane.base = Math.max(1, one.getInt("base"));
                if (one.contains("price")) lane.price = Math.max(1, one.getInt("price")); else lane.price = lane.base;
                lane.deal = one.getInt("deal"); lane.was = one.getInt("was");
                // A template names goods without a stock: such a lane starts full.
                lane.stock = one.contains("stock") ? one.getInt("stock") : lane.goods.isEmpty() ? 0 : display().capacity();
            }
            stocked = nbt.getBoolean("stocked");
            restocked = nbt.contains("restocked") ? nbt.getLong("restocked") : Long.MIN_VALUE;
        }

        @Override public Packet<ClientPlayPacketListener> toUpdatePacket() { return BlockEntityUpdateS2CPacket.create(this); }
        @Override public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) { return createNbt(registries); }
    }

    /** The shop's basket and cart: the goods taken while one is in the inventory go into it (up to {@link #BASKET_LOTS} or
     * {@link #CART_LOTS} stacks). Paying at a till empties it into the inventory and it goes back to the shop; carried out unpaid
     * it is the shop's goods too. */
    public static final int BASKET_LOTS = 12, CART_LOTS = 27;

    public static boolean carrier(ItemStack stack) { return stack.getItem() instanceof BasketItem; }
    public static int lots(ItemStack stack) { return stack.getItem() instanceof BasketItem b ? b.lots : 0; }

    public static class BasketItem extends Item {
        final int lots;
        BasketItem(Settings settings, int lots) { super(settings); this.lots = lots; }

        @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
            ItemStack basket = player.getStackInHand(hand);
            if (!player.isSneaking()) return TypedActionResult.pass(basket);
            if (!world.isClient) ShopController.emptyBasket(basket, player, false);
            return TypedActionResult.success(basket, world.isClient);
        }

        @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
            List<ItemStack> inside = ShopController.basketContents(stack);
            if (inside.isEmpty()) tooltip.add(Text.translatable("mobtalk.shop.basket.empty").formatted(Formatting.GRAY));
            for (ItemStack goods : inside) tooltip.add(Text.literal(goods.getCount() + " × ").append(goods.getName()).formatted(Formatting.GRAY));
            tooltip.add(Text.translatable("mobtalk.shop.basket.hint").formatted(Formatting.DARK_GRAY));
        }
    }
}
