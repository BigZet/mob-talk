package io.github.mobtalk.game.city;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.mobtalk.MobTalkMod;
import io.github.mobtalk.game.CityBuild;
import io.github.mobtalk.server.ServerConfig;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;

/** Builds the cities into the world (spec §6.2): after a chunk's vanilla features, a chunk inside a city's biome is levelled
 * (cut and filled to the city's ground, easing back to the wild ground towards the biome's edge), paved by the plan and gets its
 * part of every house and sign that crosses it. Each chunk writes only inside itself, so houses across chunk borders are built
 * in parts without seams. */
public final class CityGen {
    private CityGen() { }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server ->
                CityMap.setBiome(server.getRegistryManager().get(RegistryKeys.BIOME).getEntry(CityMap.CITY).orElse(null)));
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (world.getRegistryKey() == World.OVERWORLD)
                CityMap.load(world.getChunkManager().getChunkGenerator().getBiomeSource(), world.getSeed(), ServerConfig.get());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CityMap.unload());
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("city")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("locate").executes(c -> locate(c.getSource())))
                .then(literal("tp").executes(c -> tp(c.getSource(), 0, 0))
                        .then(argument("dx", IntegerArgumentType.integer(-1000, 1000)).then(argument("dz", IntegerArgumentType.integer(-1000, 1000))
                                .executes(c -> tp(c.getSource(), IntegerArgumentType.getInteger(c, "dx"), IntegerArgumentType.getInteger(c, "dz"))))))
                .then(literal("plan").executes(c -> plan(c.getSource())))));
    }

    // ------------------------------------------------------------ generation

    /** Called after the vanilla features of a chunk (mixin into {@link ChunkGenerator#generateFeatures}). */
    public static void generate(StructureWorldAccess world, Chunk chunk) {
        if (!CityMap.enabled()) return;
        ServerWorld server = world.toServerWorld();
        if (server.getRegistryKey() != World.OVERWORLD) return;
        ChunkPos cp = chunk.getPos();
        CityMap.City city = CityMap.cityIn(server, cp.getCenterX(), cp.getCenterZ());
        if (city == null) return;
        int nx = MathHelper.clamp(city.x, cp.getStartX(), cp.getEndX()), nz = MathHelper.clamp(city.z, cp.getStartZ(), cp.getEndZ());
        if (city.reach(nx, nz) > city.biomeRadius) return;
        try {
            build(world, server, chunk, city);
        } catch (RuntimeException e) {
            MobTalkMod.LOGGER.error("City chunk {} failed", cp, e);
        }
    }

    private static void build(StructureWorldAccess world, ServerWorld server, Chunk chunk, CityMap.City city) {
        ChunkGenerator generator = server.getChunkManager().getChunkGenerator();
        int ground = CityMap.groundY(city, generator, server.getChunkManager().getNoiseConfig(), chunk, generator.getSeaLevel());
        CityPlan plan = city.plan();
        ChunkPos cp = chunk.getPos();
        double flat = city.flatReach();
        BlockPos.Mutable m = new BlockPos.Mutable();
        BlockState air = Blocks.AIR.getDefaultState(), dirt = Blocks.DIRT.getDefaultState(), stone = Blocks.STONE.getDefaultState();
        int bottom = chunk.getBottomY(), sea = generator.getSeaLevel() - 1;
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++) {
                int wx = cp.getStartX() + i, wz = cp.getStartZ() + j;
                double reach = city.reach(wx, wz);
                if (reach > city.biomeRadius) continue;
                int top = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, i, j);
                int natural = top;
                while (natural > bottom && soft(chunk.getBlockState(m.set(wx, natural, wz)))) natural--;
                int target = ground;
                if (reach > flat) {
                    double t = (reach - flat) / (city.biomeRadius - flat);
                    t = t * t * (3 - 2 * t);
                    target = (int) Math.round(ground + (natural - ground) * t);
                    if (natural >= sea) target = Math.max(target, sea);   // dry land is never cut below the sea: no holes by a shore
                }
                CityPlan.Surface surface = plan.surface(wx - city.x, wz - city.z);
                if (surface != CityPlan.Surface.WILD && surface != CityPlan.Surface.LAWN) target = Math.max(target, sea + 1);   // an exit crosses water on a causeway
                if (surface == CityPlan.Surface.WILD && target == natural) continue;
                for (int y = Math.max(top, target); y > target; y--) {
                    BlockState state = chunk.getBlockState(m.set(wx, y, wz));
                    // The sea and the rivers stay where the ground is left under them: only the land above them is cut.
                    if (!state.isAir() && !(y <= sea && state.getFluidState().isStill())) chunk.setBlockState(m, air, false);
                }
                for (int y = Math.min(natural, target - 6); y < target; y++) {
                    m.set(wx, y, wz);
                    if (y >= target - 3) chunk.setBlockState(m, dirt, false);
                    else if (soft(chunk.getBlockState(m))) chunk.setBlockState(m, stone, false);
                }
                chunk.setBlockState(m.set(wx, target, wz), surfaceBlock(surface), false);
            }
        BlockBox box = new BlockBox(cp.getStartX(), bottom, cp.getStartZ(), cp.getEndX(), chunk.getTopY() - 1, cp.getEndZ());
        for (CityPlan.House house : plan.houses) {
            if (house.maxX() + city.x < box.getMinX() || house.minX() + city.x > box.getMaxX()
                    || house.maxZ() + city.z < box.getMinZ() || house.minZ() + city.z > box.getMaxZ()) continue;
            Optional<StructureTemplate> template = server.getStructureTemplateManager().getTemplate(house.template());
            if (template.isEmpty()) continue;
            BlockPos origin = new BlockPos(city.x + house.originX(), ground, city.z + house.originZ());
            StructurePlacementData data = new StructurePlacementData().setRotation(house.rotation()).setBoundingBox(box).setIgnoreEntities(true);
            template.get().place(world, origin, origin, data, Random.create(city.seed ^ origin.asLong()), Block.NOTIFY_LISTENERS);
        }
        for (CityPlan.Sign sign : plan.signs) {
            BlockPos pos = new BlockPos(city.x + sign.x(), ground + 1, city.z + sign.z());
            if (!box.contains(pos)) continue;
            world.setBlockState(pos, Blocks.OAK_SIGN.getDefaultState().with(SignBlock.ROTATION, sign.rotation()), Block.NOTIFY_LISTENERS);
            if (world.getBlockEntity(pos) instanceof SignBlockEntity entity) {
                SignText text = new SignText();
                for (int k = 0; k < 4; k++) text = text.withMessage(k, Text.literal(sign.lines()[k]));
                // Read in as from a save: setText would tell a world that a chunk still being generated does not have.
                var ops = world.getRegistryManager().getOps(NbtOps.INSTANCE);
                NbtCompound nbt = new NbtCompound();
                SignText.CODEC.encodeStart(ops, text).result().ifPresent(t -> nbt.put("front_text", t));
                entity.read(nbt, world.getRegistryManager());
            }
        }
    }

    /** What the levelling sees through: air, water, plants, snow, leaves and logs (a tree is no ground). */
    private static boolean soft(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty() || state.isReplaceable() || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS);
    }

    private static BlockState surfaceBlock(CityPlan.Surface surface) {
        String id = switch (surface) {
            case ROAD -> "asphalt";
            case LINE_X -> "asphalt_line_x";
            case LINE_Z -> "asphalt_line_z";
            case KERB -> "kerb";
            case SIDEWALK -> "sidewalk";
            case PLAZA, ALLEY -> "plaza";
            default -> null;
        };
        Block block = id == null ? null : CityBuild.BLOCKS.get(id);
        return block == null ? Blocks.GRASS_BLOCK.getDefaultState() : block.getDefaultState();
    }

    // ------------------------------------------------------------ commands

    private static int locate(ServerCommandSource source) {
        BlockPos at = BlockPos.ofFloored(source.getPosition());
        List<CityMap.City> near = CityMap.near(source.getServer().getOverworld(), at.getX(), at.getZ(), 2);
        if (near.isEmpty()) {
            source.sendError(Text.literal(CityMap.enabled() ? "Рядом нет городов" : "Города 2.0 выключены (F7, «Города»)"));
            return 0;
        }
        CityMap.City c = near.get(0);
        source.sendFeedback(() -> Text.literal("Ближайший город: " + c.x + " " + c.z + " (" + (int) Math.hypot(c.x - at.getX(), c.z - at.getZ())
                + " блоков), застройка ±" + c.radius + ", биом " + c.biomeRadius), false);
        return near.size();
    }

    private static int tp(ServerCommandSource source, int dx, int dz) {
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) return 0;
        ServerWorld world = source.getServer().getOverworld();
        BlockPos at = player.getBlockPos();
        List<CityMap.City> near = CityMap.near(world, at.getX(), at.getZ(), 2);
        if (near.isEmpty()) {
            source.sendError(Text.literal("Рядом нет городов"));
            return 0;
        }
        CityMap.City c = near.get(0);
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        int ground = CityMap.groundY(c, generator, world.getChunkManager().getNoiseConfig(), world, generator.getSeaLevel());
        player.teleport(world, c.x + dx + 0.5, ground + 2, c.z + dz + 0.5, Set.of(), player.getYaw(), player.getPitch());
        source.sendFeedback(() -> Text.literal("Город " + c.x + " " + c.z + ", земля на " + ground), false);
        return 1;
    }

    /** A picture of the nearest city's plan, a pixel a block, into the world's folder. */
    private static int plan(ServerCommandSource source) {
        BlockPos at = BlockPos.ofFloored(source.getPosition());
        List<CityMap.City> near = CityMap.near(source.getServer().getOverworld(), at.getX(), at.getZ(), 2);
        if (near.isEmpty()) {
            source.sendError(Text.literal("Рядом нет городов"));
            return 0;
        }
        CityMap.City c = near.get(0);
        CityPlan plan = c.plan();
        int b = c.biomeRadius, n = 2 * b + 1;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        for (int x = -b; x <= b; x++)
            for (int z = -b; z <= b; z++) {
                int rgb;
                if (c.reach(c.x + x, c.z + z) > b) rgb = 0x2a3a2a;
                else rgb = switch (plan.surface(x, z)) {
                    case ROAD -> 0x474749; case LINE_X, LINE_Z -> 0xe0e0d6; case KERB -> 0xb0aeaa; case SIDEWALK -> 0xa4a29a;
                    case PLAZA, ALLEY -> 0xaa8c84; case LAWN -> 0x6f9a48; default -> c.reach(c.x + x, c.z + z) <= c.flatReach() ? 0x5d8040 : 0x4a6a38;
                };
                img.setRGB(x + b, z + b, rgb);
            }
        for (CityPlan.House h : plan.houses)
            for (int x = h.minX(); x <= h.maxX(); x++)
                for (int z = h.minZ(); z <= h.maxZ(); z++) {
                    boolean apron = x - h.minX() < 2 || h.maxX() - x < 2 || z - h.minZ() < 2 || h.maxZ() - z < 2;
                    img.setRGB(x + b, z + b, apron ? 0x555555 : h.template().getPath().endsWith("grey") ? 0x9a9a9a
                            : h.template().getPath().endsWith("beige") ? 0xd8c8a0 : h.template().getPath().endsWith("white") ? 0xe8e8e8 : 0x9ab4d0);
                }
        for (CityPlan.Sign s : plan.signs)
            for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) img.setRGB(s.x() + x + b, s.z() + z + b, 0xd04040);
        File out = source.getServer().getSavePath(WorldSavePath.ROOT).resolve("mobtalk_city_plan.png").toFile();
        try {
            ImageIO.write(img, "png", out);
        } catch (java.io.IOException e) {
            source.sendError(Text.literal("Не записал план: " + e.getMessage()));
            return 0;
        }
        source.sendFeedback(() -> Text.literal("План города " + c.x + " " + c.z + ": " + out + ", домов " + plan.houses.size()
                + ", кварталов " + plan.quarters.size()), false);
        return 1;
    }
}
