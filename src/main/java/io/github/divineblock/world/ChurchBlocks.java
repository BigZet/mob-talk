package io.github.divineblock.world;

import com.mojang.serialization.MapCodec;
import io.github.divineblock.DivineBlockMod;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/** The church's own furnishings, drawn by scripts/gen_church_assets.py: the gilded eight-pointed dome cross (three blocks
 * tall, the block is its middle) on its "apple", the bronze bells in three sizes that ring low, icon boards (single icons,
 * the two great icons of the local tier, the royal doors), the brass candle stand, the panikadilo and the analoy. */
public final class ChurchBlocks {
    private ChurchBlocks() { }
    private static final List<Item> ITEMS = new ArrayList<>();

    private static AbstractBlock.Settings metal(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(3f, 9f).sounds(BlockSoundGroup.METAL).nonOpaque().pistonBehavior(PistonBehavior.BLOCK);
    }

    private static AbstractBlock.Settings gilded() {
        return AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(2f, 6f).sounds(BlockSoundGroup.WOOD);
    }

    public static final Block DOME_CROSS = register("dome_cross", new DomeCrossBlock(metal(MapColor.GOLD)));
    public static final Block CROSS_APPLE = register("cross_apple", new Decor(metal(MapColor.GOLD), Block.createCuboidShape(4, 0, 4, 12, 16, 12), false));
    public static final Block CHURCH_BELL = register("church_bell", new ChurchBellBlock(metal(MapColor.ORANGE).strength(5f, 12f)));
    public static final Block ICON_PANEL = register("icon_panel", new IconBlock(AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(1f)
            .sounds(BlockSoundGroup.WOOD).nonOpaque().noCollision()));
    public static final Block CANDLE_STAND = register("candle_stand", new Decor(metal(MapColor.GOLD).luminance(state -> 13),
            Block.createCuboidShape(1, 0, 1, 15, 14, 15), true));
    public static final Block CHANDELIER = register("chandelier", new Decor(metal(MapColor.GOLD).luminance(state -> 15).noCollision(),
            Block.createCuboidShape(4, 0, 4, 12, 16, 12), true));
    /** The stone iconostasis' gilded carving (scripts/gen_gilded_assets.py): the gold-leaf ground in its frames, the columns
     * with a grapevine winding up them, the cornice between the tiers. */
    public static final Block GILDED_GROUND = register("gilded_ground", new Block(gilded()));
    public static final Block GILDED_COLUMN = register("gilded_column", new Block(gilded()));
    public static final Block GILDED_CORNICE = register("gilded_cornice", new Block(gilded()));
    public static final Block ANALOY = register("analoy", new AnaloyBlock(AbstractBlock.Settings.create().mapColor(MapColor.RED).strength(1.5f)
            .sounds(BlockSoundGroup.WOOL).nonOpaque()));

    private static Block register(String name, Block block) {
        Identifier id = Identifier.of(DivineBlockMod.ID, name);
        Registry.register(Registries.BLOCK, id, block);
        ITEMS.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        return block;
    }

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> ITEMS.forEach(entries::add));
    }

    /** A furnishing with a fixed shape; candle-lit ones throw little flames now and then. */
    public static class Decor extends Block {
        private final VoxelShape shape;
        private final boolean flames;
        public Decor(Settings settings, VoxelShape shape, boolean flames) { super(settings); this.shape = shape; this.flames = flames; }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(settings -> new Decor(settings, shape, flames)); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shape; }
        @Override public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
            if (!flames || random.nextInt(3) > 0) return;
            double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * (this == CHANDELIER ? 2.2 : 0.8);
            double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * (this == CHANDELIER ? 2.2 : 0.8);
            double y = pos.getY() + (this == CHANDELIER ? -0.2 + random.nextInt(3) * 0.7 : 1.05);
            world.addParticle(ParticleTypes.SMALL_FLAME, x, y, z, 0, 0, 0);
        }
    }

    /** The eight-pointed cross, its bars along the axis. */
    public static class DomeCrossBlock extends Block {
        public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
        private static final VoxelShape SHAPE = Block.createCuboidShape(6, 0, 6, 10, 16, 10);
        public DomeCrossBlock(Settings settings) { super(settings); setDefaultState(getDefaultState().with(AXIS, Direction.Axis.Z)); }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(DomeCrossBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(AXIS); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(AXIS, ctx.getHorizontalPlayerFacing().rotateYClockwise().getAxis());
        }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) {
            if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90)
                return state.with(AXIS, state.get(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            return state;
        }
    }

    /** A bronze bell: 0 small (one block), 1 medium (two), 2 the great bell (three blocks; the block is its middle, the beam
     * two above). Rung by hand it booms low, the great one lowest and longest. */
    public static class ChurchBellBlock extends Block {
        public static final IntProperty SIZE = IntProperty.of("size", 0, 2);
        private static final VoxelShape SHAPE = Block.createCuboidShape(2, 0, 2, 14, 16, 14);
        public ChurchBellBlock(Settings settings) { super(settings); setDefaultState(getDefaultState().with(SIZE, 1)); }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(ChurchBellBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(SIZE); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!world.isClient) ring(world, pos, state.get(SIZE));
            return ActionResult.success(world.isClient);
        }
        /** The bell's voice: the vanilla bell at the lowest pitch, with its resonance for the bigger ones. */
        public static void ring(World world, BlockPos pos, int size) {
            float pitch = size == 2 ? 0.5f : size == 1 ? 0.62f : 0.85f;
            float volume = size == 2 ? 6f : size == 1 ? 3.5f : 2f;
            world.playSound(null, pos, SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, volume, pitch);
            if (size > 0) world.playSound(null, pos, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.BLOCKS, volume * 0.6f, pitch);
            if (size == 2) world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.4f, 0.5f);
            world.emitGameEvent(null, GameEvent.BLOCK_CHANGE, pos);
        }
    }

    /** Something that faces one way and turns with the church. */
    public abstract static class Facing extends Block {
        public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
        protected Facing(Settings settings) { super(settings); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }
    }

    /** An icon board hung on the wall behind it; art picks the picture (or a piece of a big one: see ART_*). */
    public static class IconBlock extends Facing {
        public static final IntProperty ART = IntProperty.of("art", 0, 19);
        public static final int CHRIST = 0, THEOTOKOS = 1, NICHOLAS = 2, MICHAEL = 3, BAPTIST = 4, TRINITY = 5,
                CHRIST_LARGE = 6, THEOTOKOS_LARGE = 10, ROYAL_DOORS = 14;
        private static final VoxelShape[] SHAPES = { Block.createCuboidShape(0, 0, 0, 16, 16, 2), Block.createCuboidShape(14, 0, 0, 16, 16, 16),
                Block.createCuboidShape(0, 0, 14, 16, 16, 16), Block.createCuboidShape(0, 0, 0, 2, 16, 16) }; // south, west, north, east
        public IconBlock(Settings settings) { super(settings); setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(ART, 0)); }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(IconBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, ART); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return SHAPES[state.get(FACING).getHorizontal()];
        }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction side = ctx.getSide().getAxis().isHorizontal() ? ctx.getSide() : ctx.getHorizontalPlayerFacing().getOpposite();
            return getDefaultState().with(FACING, side);
        }
        /** A right click with an empty hand shows the next icon. */
        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (!player.getAbilities().allowModifyWorld || state.get(ART) > TRINITY) return ActionResult.PASS;
            world.setBlockState(pos, state.with(ART, (state.get(ART) + 1) % (TRINITY + 1)), Block.NOTIFY_ALL);
            return ActionResult.success(world.isClient);
        }
    }

    /** The analoy: a tall stand under a cloth, the feast's icon on its slanted top. */
    public static class AnaloyBlock extends Facing {
        private static final VoxelShape SHAPE = Block.createCuboidShape(2, 0, 2, 14, 15, 14);
        public AnaloyBlock(Settings settings) { super(settings); setDefaultState(getDefaultState().with(FACING, Direction.NORTH)); }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(AnaloyBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()); }
    }
}
