package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/** The city's decorations (tools/city/decor.py draws their models and textures, tools/city/pieces.py sets them out in the city):
 * benches and bins by the entrances, street lamps, playgrounds, dumpsters, the flag at the administration, the hospital's red cross,
 * parked Zhigulis and tyre swans. Plain blocks that face a side; a car is three cells long and fills its two end cells with invisible
 * {@link #CAR_PART}s so it can be walked against. Registered before any world loads, on both sides. */
public final class CityContent {
    private CityContent() { }

    /** How a decoration stands: on the ground facing the player, or on a wall it was put against. */
    enum Mount { GROUND, WALL }

    /** One decoration: its id, how it stands, its outline in the north-facing model (pixel boxes), light, colour on maps and sound. */
    record Spec(String id, Mount mount, double[][] boxes, int light, MapColor color, BlockSoundGroup sound, boolean solid) { }

    private static final BlockSoundGroup METAL = BlockSoundGroup.METAL, STONE = BlockSoundGroup.STONE, WOOD = BlockSoundGroup.WOOD;
    static final List<Spec> SPECS = List.of(
            new Spec("bench", Mount.GROUND, new double[][] {{0, 0, 4, 16, 9, 12}, {0, 9, 12, 16, 15, 14}}, 0, MapColor.DARK_GREEN, WOOD, true),
            new Spec("urn", Mount.GROUND, new double[][] {{4, 0, 4, 12, 11, 12}}, 0, MapColor.STONE_GRAY, STONE, true),
            new Spec("lamp_pole", Mount.GROUND, new double[][] {{6.5, 0, 6.5, 9.5, 16, 9.5}}, 0, MapColor.GRAY, METAL, true),
            new Spec("street_lamp", Mount.GROUND, new double[][] {{6.5, 0, 6.5, 9.5, 12, 9.5}}, 15, MapColor.GRAY, METAL, true),
            new Spec("dumpster", Mount.GROUND, new double[][] {{1, 0, 2, 15, 14, 14}}, 0, MapColor.DARK_GREEN, METAL, true),
            new Spec("swing", Mount.GROUND, new double[][] {{3, 10, 5, 13, 12, 11}}, 0, MapColor.RED, METAL, true),
            new Spec("slide", Mount.GROUND, new double[][] {{2, 0, 2, 14, 16, 14}}, 0, MapColor.BLUE, METAL, true),
            new Spec("chin_up_bar", Mount.GROUND, new double[][] {{1, 0, 7, 2.5, 16, 9}, {13.5, 0, 7, 15, 16, 9}}, 0, MapColor.GRAY, METAL, true),
            new Spec("flagpole", Mount.GROUND, new double[][] {{7, 0, 7, 9, 16, 9}}, 0, MapColor.WHITE, METAL, true),
            new Spec("flag", Mount.GROUND, new double[][] {{7, 0, 7, 9, 16, 9}}, 0, MapColor.BLUE, WOOD, true),
            new Spec("red_cross", Mount.WALL, new double[][] {{2, 2, 15, 14, 14, 16}}, 10, MapColor.WHITE, METAL, true),
            new Spec("notice_board", Mount.WALL, new double[][] {{1, 2, 15, 15, 14, 16}}, 0, MapColor.BROWN, WOOD, true),
            new Spec("air_conditioner", Mount.WALL, new double[][] {{2, 4, 9, 14, 12, 16}}, 0, MapColor.WHITE, METAL, true),
            new Spec("satellite_dish", Mount.WALL, new double[][] {{3, 3, 8, 13, 13, 16}}, 0, MapColor.LIGHT_GRAY, METAL, true),
            new Spec("bus_stop_sign", Mount.GROUND, new double[][] {{7, 0, 7, 9, 16, 9}}, 0, MapColor.YELLOW, METAL, true),
            new Spec("tyre_swan", Mount.GROUND, new double[][] {{1, 0, 1, 15, 4, 15}, {5, 4, 3, 11, 13, 13}}, 0, MapColor.WHITE, BlockSoundGroup.WOOL, true),
            new Spec("car_white", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.WHITE, METAL, true),
            new Spec("car_red", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.RED, METAL, true),
            new Spec("car_green", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.DARK_GREEN, METAL, true),
            new Spec("car_blue", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.BLUE, METAL, true),
            new Spec("car_volga_black", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.BLACK, METAL, true),
            new Spec("car_volga_beige", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.PALE_YELLOW, METAL, true),
            new Spec("car_moskvich", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.ORANGE, METAL, true),
            new Spec("car_moskvich_grey", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.GRAY, METAL, true),
            new Spec("car_zaporozhets", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.LIGHT_BLUE, METAL, true),
            new Spec("car_zaporozhets_yellow", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.YELLOW, METAL, true),
            new Spec("car_uaz", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.DARK_GREEN, METAL, true),
            new Spec("car_uaz_ambulance", Mount.GROUND, new double[][] {{0, 0, 0, 16, 16, 16}}, 0, MapColor.WHITE, METAL, true),
            // after the Pushchino panoramas
            new Spec("lamp_green_base", Mount.GROUND, new double[][] {{5.5, 0, 5.5, 10.5, 16, 10.5}}, 0, MapColor.GREEN, METAL, true),
            new Spec("lamp_green_pole", Mount.GROUND, new double[][] {{6.5, 0, 6.5, 9.5, 16, 9.5}}, 0, MapColor.GREEN, METAL, true),
            new Spec("lamp_green_double", Mount.GROUND, new double[][] {{6.5, 0, 6.5, 9.5, 8, 9.5}}, 15, MapColor.GREEN, METAL, true),
            new Spec("lamp_green_single", Mount.GROUND, new double[][] {{6.5, 0, 6.5, 9.5, 8, 9.5}}, 15, MapColor.GREEN, METAL, true),
            new Spec("curb", Mount.GROUND, new double[][] {{0, 0, 0, 16, 5.5, 3.5}}, 0, MapColor.STONE_GRAY, STONE, true),
            new Spec("park_bench", Mount.GROUND, new double[][] {{0, 0, 4, 16, 9, 12.5}, {0, 9, 12.5, 16, 15, 13.5}}, 0, MapColor.ORANGE, WOOD, true),
            new Spec("bin_metal", Mount.GROUND, new double[][] {{4.5, 0, 4.5, 11.5, 11, 11.5}}, 0, MapColor.BROWN, METAL, true),
            new Spec("bike_rack", Mount.GROUND, new double[][] {{0, 0, 4, 16, 11, 12}}, 0, MapColor.BLACK, METAL, true),
            new Spec("lamp_black_pole", Mount.GROUND, new double[][] {{5.5, 0, 5.5, 10.5, 3, 10.5}, {6.5, 3, 6.5, 9.5, 16, 9.5}}, 0, MapColor.BLACK, METAL, true),
            new Spec("lamp_black_top", Mount.GROUND, new double[][] {{5.5, 0, 5.5, 10.5, 13.5, 10.5}}, 15, MapColor.BLACK, METAL, true),
            new Spec("long_bench", Mount.GROUND, new double[][] {{0, 0, 3, 16, 8, 13.1}}, 0, MapColor.ORANGE, WOOD, true));
    /** Plain full blocks: the pavers. */
    static final List<String> CUBES = List.of("paving_grey", "paving_red");
    /** Painted metal railings that join like fences (they are in #minecraft:fences, so they also join iron-free vanilla fences). */
    static final List<String> FENCES = List.of("fence_green", "fence_black");

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    /** The invisible ends of a parked car: solid, no drop, breaking one breaks the car. */
    public static Block CAR_PART;
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        for (Spec spec : SPECS) {
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(spec.color).strength(1.5f).sounds(spec.sound)
                    .nonOpaque().luminance(state -> spec.light);
            Block block = spec.id.startsWith("car_") ? new CarBlock(settings, spec) : new DecorBlock(settings, spec);
            Identifier id = Identifier.of("mobtalk", spec.id);
            BLOCKS.put(spec.id, Registry.register(Registries.BLOCK, id, block));
            items.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        }
        for (String cube : CUBES) {
            Identifier id = Identifier.of("mobtalk", cube);
            Block block = Registry.register(Registries.BLOCK, id, new Block(AbstractBlock.Settings.create()
                    .mapColor(cube.endsWith("red") ? MapColor.TERRACOTTA_RED : MapColor.STONE_GRAY).strength(1.5f, 6f).requiresTool()
                    .sounds(BlockSoundGroup.STONE)));
            BLOCKS.put(cube, block);
            items.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        }
        for (String fence : FENCES) {
            Identifier id = Identifier.of("mobtalk", fence);
            Block block = Registry.register(Registries.BLOCK, id, new FenceBlock(AbstractBlock.Settings.create()
                    .mapColor(fence.endsWith("green") ? MapColor.GREEN : MapColor.BLACK).strength(2f, 6f).sounds(METAL).nonOpaque()));
            BLOCKS.put(fence, block);
            items.add(Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings())));
        }
        CAR_PART = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", "car_part"), new CarPartBlock(AbstractBlock.Settings.create()
                .mapColor(MapColor.CLEAR).strength(1.5f).sounds(METAL).nonOpaque().dropsNothing().noBlockBreakParticles()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
    }

    /** The north-facing boxes turned to {@code facing} (clockwise seen from above, as the blockstates turn the models). */
    static VoxelShape shape(double[][] boxes, Direction facing) {
        VoxelShape shape = VoxelShapes.empty();
        int turns = switch (facing) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
        for (double[] b : boxes) {
            double x0 = b[0], z0 = b[2], x1 = b[3], z1 = b[5];
            for (int i = 0; i < turns; i++) {
                double nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
                x0 = nx0; x1 = nx1; z0 = nz0; z1 = nz1;
            }
            shape = VoxelShapes.union(shape, Block.createCuboidShape(x0, b[1], z0, x1, b[4], z1));
        }
        return shape;
    }

    /** A decoration that faces one of four sides. */
    public static class DecorBlock extends HorizontalFacingBlock {
        final Spec spec;
        private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

        DecorBlock(AbstractBlock.Settings settings, Spec spec) {
            super(settings);
            this.spec = spec;
            for (Direction d : Direction.Type.HORIZONTAL) shapes.put(d, shape(spec.boxes, d));
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new DecorBlock(s, spec)); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return shapes.get(state.get(FACING)); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            if (spec.mount == Mount.WALL) {
                Direction side = ctx.getSide();
                return side.getAxis().isHorizontal() ? getDefaultState().with(FACING, side) : null;
            }
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }

        @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            if (spec.mount != Mount.WALL) return true;
            BlockPos behind = pos.offset(state.get(FACING).getOpposite());
            return world.getBlockState(behind).isSideSolidFullSquare(world, behind, state.get(FACING));
        }
    }

    /** A parked car: the model is three cells long along its facing; the cells in front and behind hold invisible parts. */
    public static class CarBlock extends DecorBlock {
        CarBlock(AbstractBlock.Settings settings, Spec spec) { super(settings, spec); }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(s -> new CarBlock(s, spec)); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState state = getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
            Direction along = state.get(FACING);
            World world = ctx.getWorld();
            BlockPos pos = ctx.getBlockPos();
            for (BlockPos end : List.of(pos.offset(along), pos.offset(along.getOpposite())))
                if (!world.getBlockState(end).canReplace(ctx)) return null;
            return state;
        }

        @Override public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            Direction along = state.get(FACING);
            for (BlockPos end : List.of(pos.offset(along), pos.offset(along.getOpposite())))
                world.setBlockState(end, CAR_PART.getDefaultState(), Block.NOTIFY_ALL);
        }

        @Override protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            if (!state.isOf(newState.getBlock())) {
                Direction along = state.get(FACING);
                for (BlockPos end : List.of(pos.offset(along), pos.offset(along.getOpposite())))
                    if (world.getBlockState(end).isOf(CAR_PART)) world.setBlockState(end, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            super.onStateReplaced(state, world, pos, newState, moved);
        }
    }

    /** An end of a car: drawn by the car's model, broken with it. */
    public static class CarPartBlock extends Block {
        CarPartBlock(AbstractBlock.Settings settings) { super(settings); }

        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.INVISIBLE; }

        @Override public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockState car = world.getBlockState(pos.offset(d));
                if (car.getBlock() instanceof CarBlock && car.get(HorizontalFacingBlock.FACING).getAxis() == d.getAxis())
                    world.breakBlock(pos.offset(d), !player.isCreative(), player);
            }
            return super.onBreak(world, pos, state, player);
        }
    }
}
