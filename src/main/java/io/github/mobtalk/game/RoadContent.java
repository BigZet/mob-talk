package io.github.mobtalk.game;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

/** The odds and ends laid beside the roads (Dmitriy 2026-10-25: «камушки и прочее, чтобы дорога была чуть интереснее, возможно
 * в зависимости от биома»): pebbles of three kinds, a cairn, a verst post and a fallen log. Their models are made of vanilla block
 * textures (the verst post's stripes are the mod's own). Players may place them too; they are in the Functional tab. */
public final class RoadContent {
    private RoadContent() { }

    public static Block PEBBLES, MOSSY_PEBBLES, SAND_PEBBLES, CAIRN, VERST_POST, FALLEN_LOG;
    private static boolean registered;

    /** The woods a fallen log can be of (the biome's trees). */
    public enum Wood implements StringIdentifiable {
        OAK, SPRUCE, BIRCH, JUNGLE, ACACIA, DARK_OAK, MANGROVE, CHERRY;
        @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static void register() {
        if (registered) return;
        registered = true;
        PEBBLES = block("pebbles", new GroundPieceBlock(stone(MapColor.STONE_GRAY), Block.createCuboidShape(1, 0, 1, 15, 3, 15)));
        MOSSY_PEBBLES = block("mossy_pebbles", new GroundPieceBlock(stone(MapColor.DARK_GREEN), Block.createCuboidShape(1, 0, 1, 15, 3, 15)));
        SAND_PEBBLES = block("sand_pebbles", new GroundPieceBlock(stone(MapColor.PALE_YELLOW), Block.createCuboidShape(1, 0, 1, 15, 3, 15)));
        CAIRN = block("cairn", new GroundPieceBlock(stone(MapColor.STONE_GRAY).strength(1.5f), Block.createCuboidShape(3, 0, 3, 13, 14, 13)));
        VERST_POST = block("verst_post", new VerstPostBlock(AbstractBlock.Settings.create().mapColor(MapColor.OAK_TAN).strength(1.5f)
                .sounds(BlockSoundGroup.WOOD).nonOpaque()));
        FALLEN_LOG = block("fallen_log", new FallenLogBlock(AbstractBlock.Settings.create().mapColor(MapColor.SPRUCE_BROWN).strength(1.5f)
                .sounds(BlockSoundGroup.WOOD).nonOpaque().burnable()));
    }

    private static AbstractBlock.Settings stone(MapColor colour) {
        return AbstractBlock.Settings.create().mapColor(colour).strength(0.6f).sounds(BlockSoundGroup.STONE).nonOpaque().noCollision();
    }

    private static Block block(String name, Block block) {
        Identifier id = Identifier.of("mobtalk", name);
        Registry.register(Registries.BLOCK, id, block);
        Item item = Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> entries.add(item));
        return block;
    }

    /** Something small lying on the ground, turned one of four ways so no two look alike; it goes when the ground under it does. */
    public static class GroundPieceBlock extends Block {
        public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
        private final VoxelShape shape;

        GroundPieceBlock(AbstractBlock.Settings settings, VoxelShape shape) {
            super(settings);
            this.shape = shape;
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override public BlockState getPlacementState(ItemPlacementContext context) { return getDefaultState().with(FACING, context.getHorizontalPlayerFacing()); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shape; }
        @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            return world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP);
        }
        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            if (direction == Direction.DOWN && !canPlaceAt(state, world, pos)) return Blocks.AIR.getDefaultState();
            return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        }
    }

    /** A striped post a block and a half tall, with a cap: a verst post («верстовой столб») beside the road. */
    public static class VerstPostBlock extends Block {
        private static final VoxelShape SHAPE = Block.createCuboidShape(5, 0, 5, 11, 16, 11);
        VerstPostBlock(AbstractBlock.Settings settings) { super(settings); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPE; }
        @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            return world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP);
        }
        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            if (direction == Direction.DOWN && !canPlaceAt(state, world, pos)) return Blocks.AIR.getDefaultState();
            return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
        }
    }

    /** A log lying on the ground along x or z, of one of the woods; pieces in a row join into one log (their end faces are not drawn
     * where they meet). */
    public static class FallenLogBlock extends Block {
        public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
        public static final EnumProperty<Wood> WOOD = EnumProperty.of("wood", Wood.class);
        private static final VoxelShape ALONG_Z = Block.createCuboidShape(3, 0, 0, 13, 10, 16), ALONG_X = Block.createCuboidShape(0, 0, 3, 16, 10, 13);

        FallenLogBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(AXIS, Direction.Axis.X).with(WOOD, Wood.OAK));
        }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(AXIS, WOOD); }
        @Override public BlockState getPlacementState(ItemPlacementContext context) {
            return getDefaultState().with(AXIS, context.getHorizontalPlayerFacing().getAxis());
        }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90
                    ? state.with(AXIS, state.get(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : state;
        }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return state.get(AXIS) == Direction.Axis.Z ? ALONG_Z : ALONG_X;
        }
        @Override protected boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
            return stateFrom.isOf(this) && stateFrom.get(AXIS) == state.get(AXIS) && stateFrom.get(WOOD) == state.get(WOOD)
                    && direction.getAxis() == state.get(AXIS);
        }
    }
}
