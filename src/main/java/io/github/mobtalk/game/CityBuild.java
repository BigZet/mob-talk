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
import net.minecraft.state.property.EnumProperty;
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
        PLASTER, PAPER_BEIGE, PAPER_GREEN, PAPER_BLUE, PAPER_PINK, PAPER_WOOD, TILE_BATH, TILE_KITCHEN, PAINT_STAIR;

        @Override public String asString() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public static final EnumProperty<Finish> NORTH = EnumProperty.of("north", Finish.class);
    public static final EnumProperty<Finish> EAST = EnumProperty.of("east", Finish.class);
    public static final EnumProperty<Finish> SOUTH = EnumProperty.of("south", Finish.class);
    public static final EnumProperty<Finish> WEST = EnumProperty.of("west", Finish.class);
    public static final EnumProperty<Finish> INNER = EnumProperty.of("inner", Finish.class);

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        if (!CityBuildBlocks.FINISHES.equals(java.util.Arrays.stream(Finish.values()).map(Finish::asString).toList()))
            throw new IllegalStateException("CityBuild.Finish is out of step with tools/city2/blocks.py");
        List<Item> items = new ArrayList<>();
        for (String row : List.of("wall_lo", "wall_hi"))
            add(row, new FinishWallBlock(concrete(MapColor.WHITE)), items);
        for (String id : CityBuildBlocks.FACADES)
            add(id, new FacadeBlock(concrete(MapColor.STONE_GRAY)), items);
        CityBuildBlocks.CUBES.forEach((id, color) -> add(id, new Block(concrete(color)), items));
        for (String id : CityBuildBlocks.SLABS)
            add(id, new SlabBlock(concrete(MapColor.STONE_GRAY)), items);
        for (String id : CityBuildBlocks.DOORS) {
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(id.equals("door_flat") || id.equals("door_entrance") ? MapColor.BROWN : MapColor.WHITE)
                    .strength(3f).nonOpaque().pistonBehavior(PistonBehavior.DESTROY)
                    .sounds(id.equals("door_entrance") ? BlockSoundGroup.METAL : BlockSoundGroup.WOOD);
            Block block = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", id), new DoorBlock(BlockSetType.OAK, settings));
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
                    .suggests((c, b) -> CommandSource.suggestMatching(List.of("grey", "beige", "white", "blue"), b))
                    .executes(c -> house(c.getSource(), StringArgumentType.getString(c, "series"), IntegerArgumentType.getInteger(c, "sections"),
                            IntegerArgumentType.getInteger(c, "floors"), StringArgumentType.getString(c, "colour")));
            var floors = argument("floors", IntegerArgumentType.integer(1, 16))
                    .executes(c -> house(c.getSource(), StringArgumentType.getString(c, "series"), IntegerArgumentType.getInteger(c, "sections"),
                            IntegerArgumentType.getInteger(c, "floors"), "grey"))
                    .then(colour);
            var series = argument("series", StringArgumentType.string())
                    .suggests((c, b) -> CommandSource.suggestMatching(List.of("\"1-464\""), b))
                    .then(argument("sections", IntegerArgumentType.integer(1, 8)).then(floors));
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
}
