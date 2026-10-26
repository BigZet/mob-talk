package io.github.mobtalk.game;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.SlabType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.command.CommandSource;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.TallBlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** The house blocks of the rebuilt cities (spec §3-§4, tools/city2/blocks.py draws them): walls papered per room on each side,
 * facade panels with the room's finish inside, floor slabs, windows, doors, balconies, stair steps and railings, roof things.
 * Also {@code /city house <series> <sections> <floors> [colour]}, which sets a pre-built house (tools/city2/house.py) down where
 * the player stands, so a house can be looked at before whole cities are generated. */
public final class CityBuild {
    private CityBuild() { }

    /** What a wall's side is covered with: whitewash, wallpaper, tiles, the stairwell's oil paint. */
    public enum Finish implements StringIdentifiable {
        PLASTER, PAPER_BEIGE, PAPER_GREEN, PAPER_BLUE, PAPER_PINK, PAPER_STRIPE, PAPER_ROSES, PAPER_DAMASK, PAPER_GEO, PAPER_WOOD, TILE_BATH,
        TILE_KITCHEN, PAINT_STAIR, PAINT_STAIR_BLUE, PAINT_STAIR_OCHRE, PAINT_STAIR_BROWN;

        @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    /** A window's frames (tools/city2/blocks.py WIN_FRAMES): white PVC, old painted wood, brown PVC, anthracite PVC. */
    public enum Frame implements StringIdentifiable {
        PVC, WOOD, BROWN, GREY;

        @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final EnumProperty<Frame> FRAME = EnumProperty.of("frame", Frame.class);

    /** A flat wall's sides: the room finishes and the green stair paint (other paints are on {@link StairWallBlock}s, so the
     *  wall's states stay at 13 to the fourth). */
    private static final java.util.Set<Finish> WALL_FINISHES = java.util.EnumSet.range(Finish.PLASTER, Finish.PAINT_STAIR);
    public static final EnumProperty<Finish> NORTH = EnumProperty.of("north", Finish.class, WALL_FINISHES);
    public static final EnumProperty<Finish> EAST = EnumProperty.of("east", Finish.class, WALL_FINISHES);
    public static final EnumProperty<Finish> SOUTH = EnumProperty.of("south", Finish.class, WALL_FINISHES);
    public static final EnumProperty<Finish> WEST = EnumProperty.of("west", Finish.class, WALL_FINISHES);
    /** A stair wall's room side: the room finishes only. */
    public static final EnumProperty<Finish> ROOM = EnumProperty.of("room", Finish.class, java.util.EnumSet.range(Finish.PLASTER, Finish.TILE_KITCHEN));
    public static final EnumProperty<Finish> INNER = EnumProperty.of("inner", Finish.class);

    /** Where a stair step's railing runs: none, the left or right edge (going up the flight), the front edge of a landing. */
    public enum Rail implements StringIdentifiable {
        NONE, LEFT, RIGHT, FRONT;

        @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final EnumProperty<Rail> RAIL = EnumProperty.of("rail", Rail.class);

    /** Steel doors that open by hand, with the iron door's sounds. */
    private static final BlockSetType STEEL = new BlockSetType("mobtalk_steel", true, true, false, BlockSetType.ActivationRule.EVERYTHING,
            BlockSoundGroup.METAL, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
            SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_OFF, SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_ON,
            SoundEvents.BLOCK_STONE_BUTTON_CLICK_OFF, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON);

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        if (!CityBuildBlocks.FINISHES.equals(java.util.Arrays.stream(Finish.values()).map(Finish::asString).toList()))
            throw new IllegalStateException("CityBuild.Finish is out of step with tools/city2/blocks.py");
        if (!CityBuildBlocks.FRAMES.equals(java.util.Arrays.stream(Frame.values()).map(Frame::asString).toList()))
            throw new IllegalStateException("CityBuild.Frame is out of step with tools/city2/blocks.py");
        List<Item> items = new ArrayList<>();
        for (String row : CityBuildBlocks.WALLS)
            add(row, new FinishWallBlock(concrete(MapColor.WHITE)), items);
        for (String id : CityBuildBlocks.STAIR_WALLS)
            add(id, new StairWallBlock(concrete(MapColor.WHITE)), items);
        for (String id : CityBuildBlocks.FACADES)
            add(id, new FacadeBlock(concrete(MapColor.STONE_GRAY)), items);
        for (String id : CityBuildBlocks.WINDOWS)
            add(id, new WindowBlock(concrete(MapColor.WHITE).sounds(BlockSoundGroup.GLASS).nonOpaque()
                    .solidBlock((state, world, pos) -> false).suffocates((state, world, pos) -> false).blockVision((state, world, pos) -> false)), items);
        // Nothing spawns on the floors of flats and staircases: in a dark hall or pantry bats and zombies would (Dmitriy, 2026-10-26).
        // Nor on the roof slab, the floor of the brick houses' dark attics.
        CityBuildBlocks.CUBES.forEach((id, color) -> add(id, new Block(id.startsWith("floor_") || id.equals("plinth") || id.equals("roof_bitumen")
                ? concrete(color).allowsSpawning((state, world, pos, type) -> false) : concrete(color)), items));
        for (String id : CityBuildBlocks.STEPS)
            add(id, new StepBlock(concrete(MapColor.STONE_GRAY).nonOpaque().allowsSpawning((state, world, pos, type) -> false)), items);
        for (String id : CityBuildBlocks.DOORS) {
            boolean steel = CityBuildBlocks.METAL_DOORS.contains(id);
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(CityBuildBlocks.DOOR_COLORS.get(id))
                    .strength(3f).nonOpaque().pistonBehavior(PistonBehavior.DESTROY).sounds(steel ? BlockSoundGroup.METAL : BlockSoundGroup.WOOD);
            Block block = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", id), new PairedDoorBlock(steel ? STEEL : BlockSetType.OAK, settings));
            BLOCKS.put(id, block);
            items.add(Registry.register(Registries.ITEM, Identifier.of("mobtalk", id), new TallBlockItem(block, new Item.Settings())));
        }
        for (CityContent.Spec spec : CityBuildBlocks.DECOR) {
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(spec.color()).strength(1.5f).sounds(spec.sound())
                    .nonOpaque().luminance(state -> spec.light());
            add(spec.id(), new CityContent.DecorBlock(settings, spec), items);
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> items.forEach(entries::add));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var colour = argument("colour", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestMatching(List.of("grey", "beige", "white", "blue", "brick", "silicate"), b))
                    .executes(c -> house(c.getSource(), StringArgumentType.getString(c, "series"), IntegerArgumentType.getInteger(c, "sections"),
                            IntegerArgumentType.getInteger(c, "floors"), StringArgumentType.getString(c, "colour")));
            var floors = argument("floors", IntegerArgumentType.integer(1, 25))
                    .executes(c -> house(c.getSource(), StringArgumentType.getString(c, "series"), IntegerArgumentType.getInteger(c, "sections"),
                            IntegerArgumentType.getInteger(c, "floors"), "grey"))
                    .then(colour);
            var series = argument("series", StringArgumentType.string())
                    .suggests((c, b) -> CommandSource.suggestMatching(List.of("\"1-464\"", "\"1-335\"", "\"1-447\"", "\"ii-03\"", "\"1-515-9\"", "\"i-209a\"", "\"p-44\"",
                            "\"1-447-l\"", "\"ii-03-l\"", "\"ii-03-yard\"", "\"school\"", "\"kindergarten\""), b))
                    .then(argument("sections", IntegerArgumentType.integer(0, 8)).then(floors));
            dispatcher.register(literal("city").requires(source -> source.hasPermissionLevel(2)).then(literal("house").then(series)));
        });
    }

    /** Blocks drawn in the translucent layer (glass); for the client. */
    public static List<String> translucent() { return CityBuildBlocks.TRANSLUCENT; }
    /** Blocks drawn in the cut-out layer (railings, aerials, doors without glass); for the client. */
    public static List<String> cutout() { return CityBuildBlocks.CUTOUT; }

    private static AbstractBlock.Settings concrete(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(1.8f, 6f).requiresTool().sounds(BlockSoundGroup.STONE);
    }

    private static void add(String id, Block block, List<Item> items) {
        Identifier key = Identifier.of("mobtalk", id);
        BLOCKS.put(id, Registry.register(Registries.BLOCK, key, block));
        items.add(Registry.register(Registries.ITEM, key, new BlockItem(block, new Item.Settings())));
    }

    /** Sets a pre-built house down with its template origin (the north-west corner of the apron) at the block under the player. */
    private static int house(ServerCommandSource source, String series, int sections, int floors, String colour) {
        ServerWorld world = source.getWorld();
        Identifier id = Identifier.of("mobtalk", "city2/house/" + series + "_s" + sections + "_f" + floors + "_" + colour);
        Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(id);
        if (template.isEmpty()) {
            source.sendError(Text.literal("Нет такого дома: " + id + " (собирается tools/city2/build.py)"));
            return 0;
        }
        BlockPos at = BlockPos.ofFloored(source.getPosition()).down();
        template.get().place(world, at, at, new StructurePlacementData(), world.getRandom(), Block.NOTIFY_LISTENERS);
        source.sendFeedback(() -> Text.literal("Дом " + series + ", подъездов " + sections + ", этажей " + floors + ": " + at.toShortString()
                + " … " + at.add(template.get().getSize()).toShortString()), false);
        return 1;
    }

    /** A wall cell: each of its four sides has its own finish, so a partition shows each room its own wallpaper. */
    public static class FinishWallBlock extends Block {
        FinishWallBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(NORTH, Finish.PLASTER).with(EAST, Finish.PLASTER)
                    .with(SOUTH, Finish.PLASTER).with(WEST, Finish.PLASTER));
        }

        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(FinishWallBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(NORTH, EAST, SOUTH, WEST); }

        static EnumProperty<Finish> side(Direction d) {
            return switch (d) { case EAST -> EAST; case SOUTH -> SOUTH; case WEST -> WEST; default -> NORTH; };
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            Finish f = Finish.PAPER_BEIGE;
            return getDefaultState().with(NORTH, f).with(EAST, f).with(SOUTH, f).with(WEST, f);
        }

        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) {
            BlockState out = state;
            for (Direction d : Direction.Type.HORIZONTAL) out = out.with(side(rotation.rotate(d)), state.get(side(d)));
            return out;
        }

        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState out = state;
            for (Direction d : Direction.Type.HORIZONTAL) out = out.with(side(mirror.apply(d)), state.get(side(d)));
            return out;
        }
    }

    /** A staircase wall in a paint other than green: each side is painted ({@code north} … {@code west} true) or shows the one
     *  room finish {@code room} (a stair wall borders one room at most, the rest of its sides face walls). */
    public static class StairWallBlock extends Block {
        StairWallBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(Properties.NORTH, false).with(Properties.EAST, false)
                    .with(Properties.SOUTH, false).with(Properties.WEST, false).with(ROOM, Finish.PLASTER));
        }

        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(StairWallBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(Properties.NORTH, Properties.EAST, Properties.SOUTH, Properties.WEST, ROOM);
        }

        static BooleanProperty side(Direction d) {
            return switch (d) { case EAST -> Properties.EAST; case SOUTH -> Properties.SOUTH; case WEST -> Properties.WEST; default -> Properties.NORTH; };
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(side(ctx.getHorizontalPlayerFacing().getOpposite()), true);
        }

        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) {
            BlockState out = state;
            for (Direction d : Direction.Type.HORIZONTAL) out = out.with(side(rotation.rotate(d)), state.get(side(d)));
            return out;
        }

        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState out = state;
            for (Direction d : Direction.Type.HORIZONTAL) out = out.with(side(mirror.apply(d)), state.get(side(d)));
            return out;
        }
    }

    /** A facade cell: the panel faces {@code facing} (outside), the room's finish is on the opposite side. */
    public static class FacadeBlock extends HorizontalFacingBlock {
        FacadeBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(INNER, Finish.PLASTER));
        }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(FacadeBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, INNER); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }
    }

    /** A window part: a facade cell with glass in it, so see-through and never a full solid block. */
    public static class WindowBlock extends FacadeBlock {
        WindowBlock(AbstractBlock.Settings settings) { super(settings); }

        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return createCodec(WindowBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, INNER, FRAME); }

        @Override protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) { return true; }
        @Override protected float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) { return 1f; }
    }

    /** A stair step: a half-slab that knows which way its flight climbs ({@code facing}) and where its thin railing runs. */
    public static class StepBlock extends SlabBlock {
        StepBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getDefaultState().with(HorizontalFacingBlock.FACING, Direction.NORTH).with(RAIL, Rail.NONE));
        }

        @Override public MapCodec<? extends SlabBlock> getCodec() { return createCodec(StepBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            super.appendProperties(builder);
            builder.add(HorizontalFacingBlock.FACING, RAIL);
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState state = super.getPlacementState(ctx);
            return state == null ? null : state.with(HorizontalFacingBlock.FACING, ctx.getHorizontalPlayerFacing());
        }

        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            VoxelShape slab = super.getOutlineShape(state, world, pos, context);
            Rail rail = state.get(RAIL);
            if (rail == Rail.NONE || state.get(TYPE) == SlabType.DOUBLE) return slab;
            double y = state.get(TYPE) == SlabType.TOP ? 16 : 8;
            double[] north = switch (rail) {   // the railing in the north-facing model, turned to the step's facing
                case LEFT -> new double[] {0, y, 0, 2, y + 16, 16};
                case RIGHT -> new double[] {14, y, 0, 16, y + 16, 16};
                default -> new double[] {0, y, 0, 16, y + 16, 2};
            };
            return VoxelShapes.union(slab, CityContent.shape(new double[][] {north}, state.get(HorizontalFacingBlock.FACING)));
        }

        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(HorizontalFacingBlock.FACING, rotation.rotate(state.get(HorizontalFacingBlock.FACING)));
        }

        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) {
            Direction facing = state.get(HorizontalFacingBlock.FACING);
            BlockState out = state.with(HorizontalFacingBlock.FACING, mirror.apply(facing));
            if (mirror != BlockMirror.NONE && state.get(RAIL) == Rail.LEFT) out = out.with(RAIL, Rail.RIGHT);
            else if (mirror != BlockMirror.NONE && state.get(RAIL) == Rail.RIGHT) out = out.with(RAIL, Rail.LEFT);
            return out;
        }
    }

    /** A door that opens with its pair: the leaf beside it with the same facing and the other hinge opens and closes along. */
    public static class PairedDoorBlock extends DoorBlock {
        PairedDoorBlock(BlockSetType type, AbstractBlock.Settings settings) { super(type, settings); }

        /** The other leaf of a double door, or null. A right-hinged leaf's pair is on its left (seen along facing), and back. */
        private static BlockPos pair(World world, BlockPos pos, BlockState state) {
            Direction facing = state.get(FACING);
            BlockPos other = pos.offset(state.get(HINGE) == DoorHinge.RIGHT ? facing.rotateYCounterclockwise() : facing.rotateYClockwise());
            BlockState o = world.getBlockState(other);
            return o.getBlock() instanceof PairedDoorBlock && o.get(FACING) == facing && o.get(HINGE) != state.get(HINGE)
                    && o.get(HALF) == state.get(HALF) ? other : null;
        }

        private static void follow(World world, BlockPos pos, BlockState state, boolean open) {
            BlockPos other = pair(world, pos, state);
            if (other != null && world.getBlockState(other).get(OPEN) != open)
                world.setBlockState(other, world.getBlockState(other).with(OPEN, open), Block.NOTIFY_LISTENERS | Block.REDRAW_ON_MAIN_THREAD);
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            ActionResult result = super.onUse(state, world, pos, player, hit);
            BlockState now = world.getBlockState(pos);
            if (result.isAccepted() && now.isOf(this)) follow(world, pos, now, now.get(OPEN));
            return result;
        }

        @Override public void setOpen(Entity entity, World world, BlockState state, BlockPos pos, boolean open) {
            super.setOpen(entity, world, state, pos, open);
            BlockState now = world.getBlockState(pos);
            if (now.isOf(this)) follow(world, pos, now, open);
        }
    }
}
