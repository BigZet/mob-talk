package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BedItem;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** The furniture of the new city's flats and stairwells (rebuild spec §4, stage 5): the list is generated into {@link CityFurniture}
 * by tools/city2/furniture.py, the behaviour is here. A piece can span several cells: the placed block draws its own cell and
 * puts a {@link Part} in each other cell (each draws its own share of the model, breaking one breaks the whole). Chairs, stools,
 * sofas and armchairs can be sat on (an invisible {@link Seat}), beds are real beds, lamps and the TV switch on and off, curtains
 * open and close along the whole window. {@link #place} sets a piece out in the world for the city generator and the tests. */
public final class FurnitureBlocks {
    private FurnitureBlocks() { }

    public enum Kind { DECOR, BED, CURTAIN }
    public enum Mount { GROUND, WALL, CEILING }

    /** One piece: cells it spans in its north-facing model (the first is the placed block), the outline boxes of each cell in pixels,
     * the seat height in pixels (0: not a seat), whether it switches on and off, the light it gives when on. */
    public record F(String id, Kind kind, Mount mount, int[][] cells, double[][][] boxes, double seat, boolean toggle, int light,
                    MapColor color, BlockSoundGroup sound, boolean solid, boolean cutout) { }

    public static final BooleanProperty LIT = Properties.LIT;
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** Curtains hang floor to ceiling in a 3-block room: tier 0 at the floor (with the radiator), 2 under the ceiling (the rod). */
    public static final int TIERS = 3;
    public static final IntProperty TIER = IntProperty.of("tier", 0, TIERS - 1);
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /** A curtain cell with the window's radiator under the sill (both share the cell in front of the window). */
    public static final BooleanProperty RADIATOR = BooleanProperty.of("radiator");
    private static final IntProperty[] PARTS = new IntProperty[17];
    private static int partsOfNext; // how many cells the Part being built has (appendProperties runs inside the constructor)

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    /** Blocks drawn with see-through pixels (lace, glass): the client puts them in the cutout layer; curtains go translucent. */
    public static final List<Block> CUTOUT = new ArrayList<>();
    public static final List<Block> TRANSLUCENT = new ArrayList<>();
    public static EntityType<Seat> SEAT;
    public static BlockEntityType<FurnitureStorage> STORAGE;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        for (F f : CityFurniture.ALL) {
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(f.color).strength(1.0f).sounds(f.sound).nonOpaque()
                    .luminance(s -> s.contains(LIT) && !s.get(LIT) ? 0 : f.light);
            if (!f.solid) settings = settings.noCollision();
            Identifier id = Identifier.of("mobtalk", f.id);
            Block block = switch (f.kind) {
                case BED -> new Bed(settings, f);
                case CURTAIN -> new Curtain(settings, f);
                default -> FurnitureUse.rows(f.id) > 0 ? new Storage(settings, f, FurnitureUse.rows(f.id)) : new Furniture(settings, f);
            };
            Registry.register(Registries.BLOCK, id, block);
            BLOCKS.put(f.id, block);
            (f.kind == Kind.CURTAIN ? TRANSLUCENT : f.cutout ? CUTOUT : new ArrayList<Block>()).add(block);
            Item item = f.kind == Kind.BED ? new BedItem(block, new Item.Settings().maxCount(1)) : new BlockItem(block, new Item.Settings());
            items.add(Registry.register(Registries.ITEM, id, item));
            if (block instanceof Furniture main && f.cells.length > 1) {
                partsOfNext = f.cells.length;
                Part part = new Part(AbstractBlock.Settings.copy(main).dropsNothing(), main);
                main.part = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", f.id + "_part"), part);
                if (f.cutout) CUTOUT.add(part);
            }
        }
        Block[] storage = BLOCKS.values().stream().filter(b -> b instanceof Storage).toArray(Block[]::new);
        STORAGE = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of("mobtalk", "furniture_storage"),
                BlockEntityType.Builder.create(FurnitureStorage::new, storage).build(null));
        FurnitureUse.register();
        SEAT = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "seat"), EntityType.Builder.<Seat>create(Seat::new, SpawnGroup.MISC)
                .dimensions(0.25f, 0.05f).disableSaving().disableSummon().makeFireImmune().maxTrackingRange(8).build("seat"));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
    }

    // ---------------------------------------------------------------- geometry

    /** A cell offset of the north-facing model turned to face {@code facing} (clockwise from above, as the blockstates turn). */
    static Vec3i turn(int[] c, Direction facing) {
        int x = c[0], z = c[2];
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (int i = 0; i < turns; i++) { int nx = -z; z = x; x = nx; }
        return new Vec3i(x, c[1], z);
    }

    static VoxelShape[][] shapes(F f) {
        VoxelShape[][] out = new VoxelShape[f.cells.length][4];
        for (int i = 0; i < f.cells.length; i++)
            for (Direction d : Direction.Type.HORIZONTAL) out[i][d.getHorizontal()] = CityContent.shape(f.boxes[i], d);
        return out;
    }

    static Direction facingFor(Mount mount, ItemPlacementContext ctx) {
        Direction side = ctx.getSide();
        if (mount == Mount.WALL && side.getAxis().isHorizontal()) return side;
        return ctx.getHorizontalPlayerFacing().getOpposite();
    }

    /** Every piece's id, in catalogue order, with how it hangs (for the city generator and the tests). */
    public static Map<String, Mount> catalogue() {
        Map<String, Mount> out = new LinkedHashMap<>();
        for (F f : CityFurniture.ALL) out.put(f.id, f.mount);
        return out;
    }

    /** Sets a piece out with its parts (or both halves of a bed or a curtain), facing {@code facing}; false when there is no room. */
    public static boolean place(World world, BlockPos pos, String id, Direction facing) { return place(world, pos, id, facing, false); }

    /** As {@link #place(World, BlockPos, String, Direction)}; a curtain with {@code radiator} also draws the radiator under the window. */
    public static boolean place(World world, BlockPos pos, String id, Direction facing, boolean radiator) {
        Block block = BLOCKS.get(id);
        if (block == null || !world.getBlockState(pos).isReplaceable()) return false;
        if (block instanceof Furniture f) {
            for (int i = 1; i < f.f.cells.length; i++) if (!world.getBlockState(pos.add(turn(f.f.cells[i], facing))).isReplaceable()) return false;
            BlockState state = f.getDefaultState().with(HorizontalFacingBlock.FACING, facing);
            world.setBlockState(pos, state, Block.NOTIFY_ALL);
            f.placeParts(world, pos, state);
            return true;
        }
        if (block instanceof Bed bed) {
            BlockPos head = pos.offset(facing);
            if (!world.getBlockState(head).isReplaceable()) return false;
            world.setBlockState(pos, bed.getDefaultState().with(BedBlock.FACING, facing).with(BedBlock.PART, BedPart.FOOT), Block.NOTIFY_ALL);
            world.setBlockState(head, bed.getDefaultState().with(BedBlock.FACING, facing).with(BedBlock.PART, BedPart.HEAD), Block.NOTIFY_ALL);
            return true;
        }
        if (block instanceof Curtain c) {
            for (int t = 1; t < TIERS; t++) if (!world.getBlockState(pos.up(t)).isReplaceable()) return false;
            BlockState lower = c.getDefaultState().with(HorizontalFacingBlock.FACING, facing).with(TIER, 0).with(RADIATOR, radiator);
            for (int t = 0; t < TIERS; t++) world.setBlockState(pos.up(t), c.edges(world, pos.up(t), lower.with(TIER, t)), Block.NOTIFY_ALL);
            return true;
        }
        return false;
    }

    /** A double bed: two real beds side by side ({@code apt_bed_double_l} at {@code foot}, {@code _r} one cell to its right as you
     * lie in it), head towards {@code facing}; false when there is no room for all four cells. */
    public static boolean placeDoubleBed(World world, BlockPos foot, Direction facing) {
        BlockPos right = foot.add(turn(new int[] {-1, 0, 0}, facing));
        for (BlockPos p : new BlockPos[] {foot, foot.offset(facing), right, right.offset(facing)})
            if (!world.getBlockState(p).isReplaceable()) return false;
        return place(world, foot, "apt_bed_double_l", facing) && place(world, right, "apt_bed_double_r", facing);
    }

    // ---------------------------------------------------------------- sitting

    /** Seats {@code who} on the piece's cell at {@code cell} (its seat height), facing the way the piece faces. */
    public static boolean sit(World world, BlockPos cell, F f, Direction facing, LivingEntity who) {
        if (world.isClient || f.seat <= 0 || who.hasVehicle()) return false;
        List<Seat> taken = world.getEntitiesByClass(Seat.class, new net.minecraft.util.math.Box(cell), Entity::hasPassengers);
        if (!taken.isEmpty()) return false;
        Seat seat = new Seat(SEAT, world);
        seat.refreshPositionAndAngles(cell.getX() + 0.5, cell.getY() + f.seat / 16.0, cell.getZ() + 0.5, facing.asRotation(), 0);
        seat.front = facing;
        world.spawnEntity(seat);
        if (!who.startRiding(seat, true)) return false;
        // look out the way the seat faces, not at the wall behind
        who.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, seat.getPos().add(facing.getOffsetX() * 4, 1.2, facing.getOffsetZ() * 4));
        return true;
    }

    /** The invisible thing a sitter rides: it stays put, goes away when nobody sits on it or the furniture is gone. */
    public static class Seat extends Entity {
        Direction front = Direction.NORTH;

        public Seat(EntityType<? extends Seat> type, World world) {
            super(type, world);
            noClip = true;
            setNoGravity(true);
            setInvisible(true);
        }

        @Override protected void initDataTracker(DataTracker.Builder builder) { }
        @Override protected void readCustomDataFromNbt(NbtCompound nbt) { }
        @Override protected void writeCustomDataToNbt(NbtCompound nbt) { }
        @Override public boolean canHit() { return false; }
        @Override public boolean isCollidable() { return false; }

        @Override public void tick() {
            super.tick();
            if (getWorld().isClient) return;
            BlockState under = getWorld().getBlockState(getBlockPos());
            if (!hasPassengers() || !(under.getBlock() instanceof Furniture || under.getBlock() instanceof Part)) discard();
        }

        @Override protected void removePassenger(Entity passenger) {
            super.removePassenger(passenger);
            if (!getWorld().isClient) discard();
        }

        /** Gets up in front of the seat if there is room, otherwise on top of it. */
        @Override public Vec3d updatePassengerForDismount(LivingEntity passenger) {
            BlockPos at = getBlockPos();
            for (BlockPos p : List.of(at.offset(front), at.offset(front.rotateYClockwise()), at.offset(front.rotateYCounterclockwise()), at.up())) {
                World w = getWorld();
                if (w.getBlockState(p).getCollisionShape(w, p).isEmpty() && w.getBlockState(p.up()).getCollisionShape(w, p.up()).isEmpty()) {
                    double floor = w.getBlockState(p.down()).getCollisionShape(w, p.down()).isEmpty() ? p.getY() : p.getY();
                    return new Vec3d(p.getX() + 0.5, floor, p.getZ() + 0.5);
                }
            }
            return super.updatePassengerForDismount(passenger);
        }
    }

    // ---------------------------------------------------------------- the blocks

    /** A piece of furniture (the placed cell of a bigger one). */
    public static class Furniture extends HorizontalFacingBlock {
        final F f;
        final VoxelShape[][] shapes;
        Block part;

        Furniture(AbstractBlock.Settings settings, F f) {
            super(settings);
            this.f = f;
            this.shapes = shapes(f);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(LIT, true));
        }

        public F spec() { return f; }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new Furniture(s, f)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, LIT); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shapes[0][state.get(FACING).getHorizontal()]; }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return f.solid ? shapes[0][state.get(FACING).getHorizontal()] : VoxelShapes.empty();
        }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing = facingFor(f.mount, ctx);
            World world = ctx.getWorld();
            for (int i = 1; i < f.cells.length; i++) {
                BlockPos p = ctx.getBlockPos().add(turn(f.cells[i], facing));
                if (!world.getBlockState(p).canReplace(ctx) || world.isOutOfHeightLimit(p)) return null;
            }
            return getDefaultState().with(FACING, facing);
        }

        @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) { placeParts(world, pos, state); }

        void placeParts(World world, BlockPos pos, BlockState state) {
            if (part == null) return;
            Direction facing = state.get(FACING);
            for (int i = 1; i < f.cells.length; i++)
                world.setBlockState(pos.add(turn(f.cells[i], facing)), part.getDefaultState().with(FACING, facing).with(PARTS[f.cells.length], i)
                        .with(LIT, state.get(LIT)), Block.NOTIFY_ALL);
        }

        @Override protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.isOf(newState.getBlock()) && part != null) {
                Direction facing = state.get(FACING);
                for (int i = 1; i < f.cells.length; i++) {
                    BlockPos p = pos.add(turn(f.cells[i], facing));
                    if (world.getBlockState(p).isOf(part)) world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
                }
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            return use(state, world, pos, pos, player, hit);
        }

        @Override protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
                                                           Hand hand, BlockHitResult hit) {
            return FurnitureUse.useWithItem(this, world, pos, player, hand, stack);
        }

        /** A click on the piece (at {@code cell}, the main block at {@code pos}): what it does ({@link FurnitureUse}), else switch it,
         * or sit down on that cell. */
        ActionResult use(BlockState state, World world, BlockPos pos, BlockPos cell, PlayerEntity player, BlockHitResult hit) {
            ActionResult special = FurnitureUse.use(this, state, world, pos, cell, player, hit);
            if (special != null) return special;
            if (f.toggle) {
                if (!world.isClient) setLit(world, pos, state, !state.get(LIT));
                return ActionResult.success(world.isClient);
            }
            if (f.seat > 0 && cell.getY() == pos.getY()) {
                if (!world.isClient) sit(world, cell, f, state.get(FACING), player);
                return ActionResult.success(world.isClient);
            }
            return ActionResult.PASS;
        }

        public void setLit(World world, BlockPos pos, BlockState state, boolean on) {
            setLitQuietly(world, pos, state, on);
            world.playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.3f, on ? 0.65f : 0.55f);
        }

        /** As {@link #setLit} without the click (a lamp switched from the wall). */
        public void setLitQuietly(World world, BlockPos pos, BlockState state, boolean on) {
            world.setBlockState(pos, state.with(LIT, on), Block.NOTIFY_ALL);
            Direction facing = state.get(FACING);
            for (int i = 1; i < f.cells.length && part != null; i++) {
                BlockPos p = pos.add(turn(f.cells[i], facing));
                BlockState ps = world.getBlockState(p);
                if (ps.isOf(part)) world.setBlockState(p, ps.with(LIT, on), Block.NOTIFY_ALL);
            }
        }
    }

    /** A piece that opens: a wardrobe, a cupboard, a fridge, a chest of drawers ({@link FurnitureStorage} holds its things). */
    public static class Storage extends Furniture implements BlockEntityProvider {
        final int rows;

        Storage(AbstractBlock.Settings settings, F f, int rows) {
            super(settings, f);
            this.rows = rows;
        }

        @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new FurnitureStorage(pos, state); }

        @Override protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof FurnitureStorage inv) {
                ItemScatterer.spawn(world, pos, inv);
                world.updateComparators(pos, this);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }

    /** Another cell of a bigger piece: draws its share of the model, sends clicks and breaking to the placed block. */
    public static class Part extends HorizontalFacingBlock {
        final Furniture owner;
        final IntProperty index;

        Part(AbstractBlock.Settings settings, Furniture owner) {
            super(settings);
            this.owner = owner;
            this.index = PARTS[owner.f.cells.length];
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(LIT, true));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new Part(s, owner)); }

        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            int n = partsOfNext;
            if (PARTS[n] == null) PARTS[n] = IntProperty.of("part", 1, Math.max(2, n - 1)); // a property needs two values at least
            builder.add(FACING, LIT, PARTS[n]);
        }

        int part(BlockState state) { return Math.min(state.get(index), owner.f.cells.length - 1); } // two cells: part=2 never placed
        BlockPos main(BlockPos pos, BlockState state) { return pos.subtract(turn(owner.f.cells[part(state)], state.get(FACING))); }

        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return owner.shapes[part(state)][state.get(FACING).getHorizontal()];
        }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return owner.f.solid ? getOutlineShape(state, world, pos, context) : VoxelShapes.empty();
        }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }
        @Override public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) { return new ItemStack(owner); }

        @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            BlockPos main = main(pos, state);
            if (world.getBlockState(main).isOf(owner)) world.breakBlock(main, !player.isCreative(), player);
            return super.onBreak(world, pos, state, player);
        }

        @Override protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.isOf(newState.getBlock())) {
                BlockPos main = main(pos, state);
                if (world.getBlockState(main).isOf(owner)) world.setBlockState(main, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            BlockPos main = main(pos, state);
            BlockState ms = world.getBlockState(main);
            return ms.isOf(owner) ? owner.use(ms, world, main, pos, player, hit) : ActionResult.PASS;
        }

        @Override protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
                                                           Hand hand, BlockHitResult hit) {
            BlockPos main = main(pos, state);
            return world.getBlockState(main).isOf(owner) ? FurnitureUse.useWithItem(owner, world, main, player, hand, stack)
                    : ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
    }

    /** A real bed (sleep, spawn point, villagers' beds tag) drawn by our model instead of the vanilla bed renderer. */
    public static class Bed extends BedBlock {
        final F f;
        final VoxelShape[][] shapes;

        Bed(AbstractBlock.Settings settings, F f) {
            super(DyeColor.WHITE, settings);
            this.f = f;
            this.shapes = shapes(f);
        }

        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }
        @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return null; }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shapes[state.get(PART) == BedPart.FOOT ? 0 : 1][state.get(FACING).getHorizontal()];
        }
    }

    /** Curtains on a rod with a net curtain behind: two cells high, open or drawn; a row of them reads as one window. */
    public static class Curtain extends HorizontalFacingBlock {
        final F f;
        private static final VoxelShape[] SHAPE = new VoxelShape[4];

        Curtain(AbstractBlock.Settings settings, F f) {
            super(settings);
            this.f = f;
            for (Direction d : Direction.Type.HORIZONTAL) SHAPE[d.getHorizontal()] = CityContent.shape(new double[][] {{0, 0, 11, 16, 16, 16}}, d);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(TIER, 0)
                    .with(OPEN, true).with(LEFT, true).with(RIGHT, true).with(RADIATOR, false));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new Curtain(s, f)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, TIER, OPEN, LEFT, RIGHT, RADIATOR); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE[state.get(FACING).getHorizontal()]; }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return VoxelShapes.empty(); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }

        boolean same(BlockState a, BlockState b) { return b.isOf(this) && b.get(FACING) == a.get(FACING) && b.get(TIER) == a.get(TIER); }

        /** Left and right as seen by someone facing the window (the curtain's front looks at him). */
        BlockState edges(WorldAccess world, BlockPos pos, BlockState state) {
            Direction facing = state.get(FACING);
            return state.with(LEFT, !same(state, world.getBlockState(pos.offset(facing.rotateYClockwise()))))
                    .with(RIGHT, !same(state, world.getBlockState(pos.offset(facing.rotateYCounterclockwise()))));
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockPos pos = ctx.getBlockPos();
            if (pos.getY() >= ctx.getWorld().getTopY() - TIERS) return null;
            for (int t = 1; t < TIERS; t++) if (!ctx.getWorld().getBlockState(pos.up(t)).canReplace(ctx)) return null;
            BlockState state = getDefaultState().with(FACING, facingFor(Mount.WALL, ctx)).with(TIER, 0);
            return edges(ctx.getWorld(), pos, state);
        }

        @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            for (int t = 1; t < TIERS; t++) world.setBlockState(pos.up(t), edges(world, pos.up(t), state.with(TIER, t)), Block.NOTIFY_ALL);
        }

        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor, WorldAccess world,
                                                                 BlockPos pos, BlockPos neighborPos) {
            int tier = state.get(TIER);
            if (direction.getAxis() == Direction.Axis.Y && (direction == Direction.UP ? tier < TIERS - 1 : tier > 0)) {
                int want = tier + (direction == Direction.UP ? 1 : -1);
                return neighbor.isOf(this) && neighbor.get(TIER) == want ? state.with(OPEN, neighbor.get(OPEN)) : Blocks.AIR.getDefaultState();
            }
            return direction.getAxis().isHorizontal() ? edges(world, pos, state) : state;
        }

        @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            if (!world.isClient && state.get(TIER) > 0) {
                BlockPos lower = pos.down(state.get(TIER));
                if (world.getBlockState(lower).isOf(this)) world.breakBlock(lower, !player.isCreative(), player);
            }
            return super.onBreak(world, pos, state, player);
        }

        /** Opens or draws the whole row: every curtain of this kind side by side along the wall, both halves. */
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) return ActionResult.SUCCESS;
            boolean open = !state.get(OPEN);
            BlockPos lower = pos.down(state.get(TIER));
            Direction facing = state.get(FACING);
            setOpen(world, lower, open);
            for (Direction side : List.of(facing.rotateYClockwise(), facing.rotateYCounterclockwise())) {
                BlockPos p = lower.offset(side);
                for (int i = 0; i < 16 && same(world.getBlockState(lower), world.getBlockState(p)); i++, p = p.offset(side)) setOpen(world, p, open);
            }
            world.playSound(null, pos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 0.7f, open ? 1.1f : 0.9f);
            return ActionResult.CONSUME;
        }

        private void setOpen(World world, BlockPos lower, boolean open) {
            for (int t = 0; t < TIERS; t++) {
                BlockState s = world.getBlockState(lower.up(t));
                BlockPos p = lower.up(t);
                if (s.isOf(this)) world.setBlockState(p, s.with(OPEN, open), Block.NOTIFY_LISTENERS);
            }
        }
    }
}
