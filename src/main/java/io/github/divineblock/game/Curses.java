package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.CurseTerm;
import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Curses that stay: the land around the home blighted, the cattle dying, the mark of Cain, the Egyptian darkness and blood
 * in the water, a flood that has not yet gone down. Saved with the world, measured in game ticks (they pass while the game
 * runs), never longer than twenty minutes ({@link CurseTerm}), worked a little every two seconds on the server thread, and
 * all lifted at once by sincere repentance. */
public final class Curses {
    public enum Type {
        BLIGHT("порча земли"), MURRAIN("мор скота"), FEAR("жители боятся"), CAIN("печать Каина"), DARKNESS("тьма египетская"),
        BLOOD("вода в кровь"), FLOOD("потоп");
        public final String label;
        Type(String label) { this.label = label; }
    }

    /** One curse on one mortal: where it sits, how far it reaches and until when. Plain data for Gson. */
    public static final class Curse {
        public Type type;
        public String player = "", dimension = World.OVERWORLD.getValue().toString();
        public int x, y, z, radius;
        public long start, until;
        /** Free numbers for the act that laid it (the flood's water line). */
        public int top, base;
        /** The punishment that laid it, as Mob Talk's villages heard it: the event id (its end carries the same), the act's id
         * and the mortal's name. Empty in curses saved before. */
        public String event = "", deed = "", name = "";

        public BlockPos center() { return new BlockPos(x, y, z); }
        public String describe(long now) { return type.label + " ещё " + Math.max(1, (until - now) / 1200) + " мин"; }
    }

    /** The saved list, kept in {@link DivineState}. */
    public static final class Book { public List<Curse> active = new ArrayList<>(); }

    /** Every this many ticks the curses do their work. */
    static final int PULSE = 40;
    /** A curse when lingering curses are off: three minutes. */
    static final long SHORT_TICKS = 20 * 60 * 3;
    public static final int FLAG_BLOOD = 1;
    private static int ticks;

    private Curses() { }

    static void register() {
        // A cursed mortal is refused by every trader: the villager shakes his head and steps back.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient || !(entity instanceof MerchantEntity merchant) || !(player instanceof ServerPlayerEntity server)) return ActionResult.PASS;
            if (!has(server.getServer(), server.getUuid(), Type.FEAR) && !has(server.getServer(), server.getUuid(), Type.CAIN)) return ActionResult.PASS;
            merchant.playSound(SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.8f);
            flee(merchant, server.getPos(), 1.0);
            return ActionResult.FAIL;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> sync(handler.player)));
    }

    // ---- Laying and lifting. ----

    /** Lays a curse for the minutes the settings allow (three when lingering curses are off). */
    public static Curse add(ServerPlayerEntity player, Type type, BlockPos center, int radius, DivineConfig config) {
        return add(player, type, center, radius, CurseTerm.ticks(config, SHORT_TICKS));
    }

    /** Lays a curse for {@code ticks}, never more than twenty minutes. */
    public static Curse add(ServerPlayerEntity player, Type type, BlockPos center, int radius, long ticks) {
        ticks = CurseTerm.cap(ticks);
        MinecraftServer server = player.getServer();
        DivineState state = DivineState.of(server);
        long now = server.getOverworld().getTime();
        // The same curse again only lasts longer and reaches further.
        for (Curse curse : state.curses.active)
            if (curse.type == type && curse.player.equals(player.getUuidAsString()) && type != Type.FLOOD) {
                curse.until = CurseTerm.trim(Math.max(curse.until, now + ticks), now);
                curse.radius = Math.max(curse.radius, radius);
                state.markDirty();
                return curse;
            }
        Curse curse = new Curse();
        curse.type = type; curse.player = player.getUuidAsString();
        curse.dimension = player.getServerWorld().getRegistryKey().getValue().toString();
        curse.x = center.getX(); curse.y = center.getY(); curse.z = center.getZ(); curse.radius = radius;
        curse.start = now; curse.until = now + ticks;
        String[] owner = VillageEvents.curseOwner(player.getUuid());
        curse.event = owner[0]; curse.deed = owner[1]; curse.name = player.getName().getString();
        state.curses.active.add(curse);
        state.markDirty();
        DivineBlockMod.LOG.info("Божественный блок: проклятие «{}» на {} на {} мин", type.label, player.getName().getString(), ticks / 1200);
        sync(player);
        return curse;
    }

    public static boolean has(MinecraftServer server, UUID player, Type type) {
        if (server == null) return false;
        for (Curse curse : DivineState.of(server).curses.active) if (curse.type == type && curse.player.equals(player.toString())) return true;
        return false;
    }

    public static List<Curse> of(MinecraftServer server, UUID player) {
        List<Curse> out = new ArrayList<>();
        for (Curse curse : DivineState.of(server).curses.active) if (curse.player.equals(player.toString())) out.add(curse);
        return out;
    }

    /** A sin under a running punishment: every curse on this mortal lasts {@code extra} ticks longer, within the twenty
     * minutes. Returns how many were prolonged. */
    public static int prolong(MinecraftServer server, UUID player, long extra) {
        long now = server.getOverworld().getTime();
        List<Curse> mine = of(server, player);
        for (Curse curse : mine) curse.until = CurseTerm.prolong(curse.until, now, extra);
        if (!mine.isEmpty()) DivineState.of(server).markDirty();
        return mine.size();
    }

    /** Sincere repentance: every curse on this mortal ends now (the flood goes down, the sun comes up). Returns how many. */
    public static int lift(MinecraftServer server, UUID player) { return lift(server, player, new ArrayList<>(), "проклятия сняты"); }

    /** {@link #lift(MinecraftServer, UUID)}, adding the ids of the punishments that ended to {@code events}. */
    public static int lift(MinecraftServer server, UUID player, List<String> events, String reason) {
        DivineState state = DivineState.of(server);
        List<Curse> mine = of(server, player);
        for (Curse curse : mine) if (curse.event != null && !curse.event.isBlank() && !events.contains(curse.event)) events.add(curse.event);
        VillageEvents.cursesEnded(server, mine, reason);
        for (Curse curse : mine) end(server, curse);
        state.curses.active.removeAll(mine);
        state.markDirty();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(player);
        if (online != null) sync(online);
        return mine.size();
    }

    /** What happens when a curse runs out or is lifted. */
    private static void end(MinecraftServer server, Curse curse) {
        ServerWorld world = world(server, curse);
        if (world == null) return;
        switch (curse.type) {
            case DARKNESS -> {
                // The sun rises on the next morning.
                long time = world.getTimeOfDay();
                world.setTimeOfDay(time - time % 24_000 + 24_000);
            }
            case FLOOD -> Ultra.recede(world, curse);
            default -> { }
        }
        DivineBlockMod.LOG.info("Божественный блок: проклятие «{}» снято", curse.type.label);
    }

    /** Tells the mortal's client what to tint (blood in the water). */
    static void sync(ServerPlayerEntity player) {
        if (player.getServer() == null || !ServerPlayNetworking.canSend(player, CursePayload.ID)) return;
        int flags = has(player.getServer(), player.getUuid(), Type.BLOOD) ? FLAG_BLOOD : 0;
        ServerPlayNetworking.send(player, new CursePayload(flags));
    }

    // ---- The acts that lay them. ----

    /** Blight on the home (the bed, else the altar): the rot spreads for days; the first wave falls at once. */
    static String layBlight(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        if (config.protectBlocks) return null;
        BlockPos bed = player.getSpawnPointPosition();
        BlockPos home = bed != null && player.getSpawnPointDimension() == world.getRegistryKey() && !ChurchArea.inZone(world, bed) ? bed : altar;
        Curse curse = add(player, Type.BLIGHT, home, 24 + 8 * power, config);
        for (int wave = 0; wave < 6; wave++) blight(world, curse);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.4f);
        Miracles.sound(world, home, SoundEvents.BLOCK_SCULK_SPREAD, 3f, 0.5f);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, home.getX() + 0.5, home.getY() + 2, home.getZ() + 0.5, 120, curse.radius / 3.0, 2, curse.radius / 3.0, 0.01);
        return "порча земли у " + (home.equals(bed) ? "дома" : "алтаря") + " на " + days(curse) + ", скот болеет";
    }

    /** The mark of Cain: for days every beast is his enemy and every villager runs. */
    static String layCain(ServerWorld world, ServerPlayerEntity player, int power, DivineConfig config) {
        Curse curse = add(player, Type.CAIN, player.getBlockPos(), 24 + 8 * power, config);
        cain(world, player, curse.radius);
        fear(world, player);
        Miracles.sound(world, player.getBlockPos(), SoundEvents.ENTITY_WITHER_SPAWN, 1f, 0.5f);
        world.spawnParticles(new DustParticleEffect(new Vector3f(0.5f, 0f, 0f), 2f), player.getX(), player.getY() + 1, player.getZ(), 80, 0.5, 1, 0.5, 0);
        return "печать Каина на " + days(curse) + ": все твари враждебны, жители бегут";
    }

    private static String days(Curse curse) { return Math.max(1, (curse.until - curse.start) / CurseTerm.TICKS_PER_MINUTE) + " мин"; }

    // ---- The work of the curses. ----

    static void tick(MinecraftServer server) {
        if (++ticks % PULSE != 0) return;
        DivineState state = DivineState.of(server);
        if (state.curses.active.isEmpty()) return;
        long now = server.getOverworld().getTime();
        List<Curse> over = new ArrayList<>();
        for (Curse curse : new ArrayList<>(state.curses.active)) {
            // Curses saved by an older version could last days: none outlasts twenty minutes from now.
            long trimmed = CurseTerm.trim(curse.until, now);
            if (trimmed != curse.until) { curse.until = trimmed; state.markDirty(); }
            if (now >= curse.until) { over.add(curse); continue; }
            ServerWorld world = world(server, curse);
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(curse.player));
            try { pulse(world, player, curse); }
            catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: проклятие {} упало", curse.type, failure); }
        }
        if (over.isEmpty()) return;
        VillageEvents.cursesEnded(server, over, "срок кары вышел");
        for (Curse curse : over) end(server, curse);
        state.curses.active.removeAll(over);
        state.markDirty();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) sync(player);
    }

    private static void pulse(ServerWorld world, ServerPlayerEntity player, Curse curse) {
        boolean near = player != null && world != null && player.getServerWorld() == world;
        switch (curse.type) {
            case BLIGHT -> { if (world != null) blight(world, curse); }
            case MURRAIN -> { if (near) murrain(world, player.getBlockPos(), curse.radius); }
            case FEAR -> { if (near) fear(world, player); }
            case CAIN -> { if (near) { fear(world, player); cain(world, player, curse.radius); } }
            case DARKNESS -> { if (world != null) darkness(world); }
            case BLOOD -> { if (near) blood(world, player, curse.radius); }
            case FLOOD -> { }
        }
    }

    /** The land around the home: grass blackens to coarse earth, flowers and grass wither, crops fall back and die, leaves
     * drop, and the cattle within sicken. A few dozen blocks a pulse, so the rot spreads over the days. */
    private static void blight(ServerWorld world, Curse curse) {
        BlockPos center = curse.center();
        int radius = curse.radius;
        if (!world.isChunkLoaded(center.getX() >> 4, center.getZ() >> 4)) return;
        for (int n = 0; n < 24; n++) {
            int x = center.getX() + world.random.nextInt(radius * 2 + 1) - radius, z = center.getZ() + world.random.nextInt(radius * 2 + 1) - radius;
            if (!world.isChunkLoaded(x >> 4, z >> 4) || (x - center.getX()) * (x - center.getX()) + (z - center.getZ()) * (z - center.getZ()) > radius * radius) continue;
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, new BlockPos(x, 0, z)).down();
            BlockState state = world.getBlockState(top);
            if (state.isIn(BlockTags.LEAVES)) { world.breakBlock(top, false); continue; }
            BlockPos ground = top;
            BlockState above = world.getBlockState(ground.up());
            if (above.getBlock() instanceof CropBlock crop) {
                // The harvest fails: a grown crop falls back to a sprout, a sprout dies and the soil turns to dust.
                if (crop.getAge(above) > 0) world.setBlockState(ground.up(), crop.withAge(0), Block.NOTIFY_ALL);
                else { world.setBlockState(ground.up(), Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL); world.setBlockState(ground, Blocks.COARSE_DIRT.getDefaultState(), Block.NOTIFY_ALL); }
            } else if (above.getBlock() instanceof PlantBlock && !above.isOf(Blocks.DEAD_BUSH)) {
                world.setBlockState(ground.up(), world.random.nextInt(3) == 0 ? Blocks.DEAD_BUSH.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
            }
            if (state.getBlock() instanceof CropBlock crop && crop.getAge(state) > 0) world.setBlockState(top, crop.withAge(0), Block.NOTIFY_ALL);
            else if (state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM) || state.isOf(Blocks.MOSS_BLOCK))
                world.setBlockState(top, (world.random.nextInt(4) == 0 ? Blocks.PODZOL : Blocks.COARSE_DIRT).getDefaultState(), Block.NOTIFY_ALL);
            else if (state.isOf(Blocks.FARMLAND)) world.setBlockState(top, Blocks.COARSE_DIRT.getDefaultState(), Block.NOTIFY_ALL);
            world.spawnParticles(ParticleTypes.SMOKE, x + 0.5, top.getY() + 1.2, z + 0.5, 3, 0.3, 0.2, 0.3, 0.01);
        }
        murrain(world, center, radius);
    }

    /** The cattle sicken: every pulse one or two beasts near the cursed one cough smoke and lose a heart, until none is left. */
    private static void murrain(ServerWorld world, BlockPos center, int radius) {
        List<AnimalEntity> beasts = world.getEntitiesByClass(AnimalEntity.class, new Box(center).expand(radius, 24, radius),
                beast -> beast.isAlive() && !(beast instanceof TameableEntity tame && tame.isTamed()));
        for (int i = 0; i < Math.min(2, beasts.size()); i++) {
            AnimalEntity beast = beasts.get(world.random.nextInt(beasts.size()));
            beast.damage(world.getDamageSources().magic(), 2f);
            world.spawnParticles(ParticleTypes.SQUID_INK, beast.getX(), beast.getY() + beast.getHeight(), beast.getZ(), 6, 0.3, 0.3, 0.3, 0.01);
            world.spawnParticles(new DustParticleEffect(new Vector3f(0.25f, 0.35f, 0.1f), 1.2f), beast.getX(), beast.getY() + 0.6, beast.getZ(), 10, 0.4, 0.4, 0.4, 0);
            if (world.random.nextInt(3) == 0) world.playSound(null, beast.getBlockPos(), SoundEvents.ENTITY_COW_HURT, beast.getSoundCategory(), 1f, 0.6f);
        }
    }

    /** Traders and villagers run from the cursed one. */
    private static void fear(ServerWorld world, ServerPlayerEntity player) {
        for (MerchantEntity merchant : world.getEntitiesByClass(MerchantEntity.class, new Box(player.getBlockPos()).expand(20), MerchantEntity::isAlive)) {
            flee(merchant, player.getPos(), 0.9);
            if (world.random.nextInt(4) == 0) merchant.playSound(SoundEvents.ENTITY_VILLAGER_NO, 1f, 0.7f + world.random.nextFloat() * 0.3f);
        }
    }

    /** The mark of Cain: every beast that can be angered is angered at him, golems and wolves hunt him (tamed ones too), the
     * meek run away. */
    private static void cain(ServerWorld world, ServerPlayerEntity player, int radius) {
        for (MobEntity mob : world.getEntitiesByClass(MobEntity.class, new Box(player.getBlockPos()).expand(radius), MobEntity::isAlive)) {
            if (mob instanceof MerchantEntity) continue;
            if (mob instanceof Angerable angry) { angry.setAngryAt(player.getUuid()); angry.chooseRandomAngerTime(); }
            if (mob instanceof TameableEntity tame && tame.isTamed()) tame.setSitting(false);
            if (mob instanceof IronGolemEntity || mob instanceof Angerable || mob instanceof net.minecraft.entity.mob.HostileEntity) mob.setTarget(player);
            else if (mob instanceof AnimalEntity) flee(mob, player.getPos(), 1.3);
        }
        if (world.random.nextInt(3) == 0)
            world.spawnParticles(new DustParticleEffect(new Vector3f(0.45f, 0.0f, 0.0f), 1f), player.getX(), player.getY() + 2.1, player.getZ(), 4, 0.15, 0.05, 0.15, 0);
    }

    /** Three days of darkness: the overworld stays at midnight whatever the clock says. */
    private static void darkness(ServerWorld world) {
        long time = world.getTimeOfDay(), hour = time % 24_000;
        if (hour < 14_000 || hour > 22_000) world.setTimeOfDay(time - hour + 18_000);
    }

    /** Blood in the water: red mist over the water near him, the fish die in it, and whoever stands in it is sickened. */
    private static void blood(ServerWorld world, ServerPlayerEntity player, int radius) {
        BlockPos at = player.getBlockPos();
        for (int n = 0; n < 30; n++) {
            BlockPos column = at.add(world.random.nextInt(33) - 16, 0, world.random.nextInt(33) - 16);
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING, column).down();
            if (world.getFluidState(top).isIn(net.minecraft.registry.tag.FluidTags.WATER))
                world.spawnParticles(new DustParticleEffect(new Vector3f(0.55f, 0.02f, 0.02f), 1.6f), top.getX() + 0.5, top.getY() + 1.05, top.getZ() + 0.5, 4, 0.4, 0.05, 0.4, 0);
        }
        for (FishEntity fish : world.getEntitiesByClass(FishEntity.class, new Box(at).expand(radius), FishEntity::isAlive)) fish.damage(world.getDamageSources().magic(), 4f);
        for (SquidEntity squid : world.getEntitiesByClass(SquidEntity.class, new Box(at).expand(radius), SquidEntity::isAlive)) squid.damage(world.getDamageSources().magic(), 4f);
        if (player.isTouchingWater()) Miracles.effect(player, StatusEffects.NAUSEA, 120, 0);
        if (player.isTouchingWater()) Miracles.effect(player, StatusEffects.POISON, 60, 0);
    }

    /** Sends a mob running from a point. */
    static void flee(MobEntity mob, Vec3d from, double speed) {
        Vec3d away = mob.getPos().subtract(from).multiply(1, 0, 1);
        if (away.lengthSquared() < 1e-3) away = new Vec3d(1, 0, 0);
        Vec3d target = mob.getPos().add(away.normalize().multiply(12));
        mob.setTarget(null);
        mob.getNavigation().startMovingTo(target.x, target.y, target.z, speed);
    }

    private static ServerWorld world(MinecraftServer server, Curse curse) {
        Identifier id = Identifier.tryParse(curse.dimension);
        return id == null ? server.getOverworld() : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
    }

    /** Clears the in-memory pulse counter when the server stops. */
    static void clear() { ticks = 0; }
}
