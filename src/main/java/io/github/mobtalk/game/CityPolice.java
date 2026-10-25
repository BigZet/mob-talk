package io.github.mobtalk.game;

import io.github.mobtalk.core.VillageAlarm;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.PersistentState;
import java.util.*;

/** The city police (Dmitriy, 2026-10-25: «полиция в городе как аналог стражей в деревне»). In a city the village's guard is the
 * police: {@link VillageGuards} puts up police posts instead of guard stands and a policeman ({@link PoliceContent#POLICE}) takes
 * each, with a baton, on the guards' shifts and rounds; the city builds no watchtowers. What is the police's own is here:
 * <ul>
 * <li>the alarm sounds the Russian police siren from the posts and the lit cars instead of the bell, and the police cars parked by
 * the posts flash their light bars while it lasts;</li>
 * <li>a baton blow stuns (slowness), and an offender worn down to {@code policeArrestHealth} is not beaten further but arrested:
 * handcuffs (no blows, no tools) for a few seconds, a fine in emeralds, then {@code policeJailSeconds} in the holding cell under the
 * city's first post, a cell of walls no tool breaks; the city's alarm against them ends. Leaving the cell early (a pearl, a death)
 * is an escape: the city is after them again;</li>
 * <li>a creature tagged {@link #WANTED} (the night bandits, a shoplifter) is fair game for the police too and is taken away when
 * arrested;</li>
 * <li>{@link #report} lets other parts of the game (the supermarket, the officials) call the police on a player.</li>
 * </ul>
 * Inmates and cells live in the world's saved data (mobtalk_police), so a sentence outlasts a logout. Server thread only. */
final class CityPolice {
    /** A creature with this tag is wanted by the police (the bandits' and the shops' tracks set it). */
    static final String WANTED = "mobtalk_wanted";
    /** How long the handcuffs stay on before the cell, and how long between two quacks of the same post. */
    static final long CUFF_TICKS = 80, QUACK_TICKS = 100;
    /** Cars within this many blocks of a post are that post's; the cell lies this deep under the first post. */
    static final int CAR_REACH = 10, CELL_DEPTH = 9;
    static final long LIGHTS_PERIOD = 20, LIGHTS_PHASE = 13;

    private final MobGameBridge bridge;
    private final VillageDefenseController defense;
    /** Offenders in handcuffs: when the cuffs come off (then the cell), and the zone that arrested them. */
    private final Map<UUID, Long> cuffed = new HashMap<>();
    private final Map<UUID, String> cuffedBy = new HashMap<>();
    /** Wanted creatures in handcuffs, taken away when the time is up. */
    private final Map<UUID, Long> takenAway = new HashMap<>();
    private final Map<String, Long> lastQuack = new HashMap<>();
    private static boolean hooked;

    CityPolice(MobGameBridge bridge, VillageDefenseController defense) { this.bridge = bridge; this.defense = defense; hook(); }

    boolean on() { return bridge.config.enabled && bridge.config.villageDefense && bridge.config.cityPolice && PoliceContent.POLICE != null; }

    /** A cuffed player can neither strike nor use anything (the cuffs hold their hands); the hooks ask the live police. */
    private static void hook() {
        if (hooked) return;
        hooked = true;
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> cuffed(player) ? ActionResult.FAIL : ActionResult.PASS);
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> cuffed(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> cuffed(player) ? ActionResult.FAIL : ActionResult.PASS);
        UseItemCallback.EVENT.register((player, world, hand) -> cuffed(player) ? TypedActionResult.fail(player.getStackInHand(hand)) : TypedActionResult.pass(player.getStackInHand(hand)));
    }
    private static boolean cuffed(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity server)) return false;
        MobGameBridge bridge = MobGameBridge.active(server.getServer());
        return bridge != null && bridge.defense.police.cuffed.containsKey(player.getUuid());
    }

    // ---- Calling the police (for the other tracks) ----

    /** Somebody calls the police on this player where they stand (a theft in a shop, a bribe offered to the wrong man, a brawl):
     * in a city the alarm is raised against them, {@code serious} as an attack (the police go for them at once), else as a threat.
     * Returns false outside a city or when the police are off. */
    static boolean report(ServerPlayerEntity player, String cause, boolean serious) {
        MobGameBridge bridge = MobGameBridge.active(player.getServer());
        if (bridge == null || !bridge.defense.police.on()) return false;
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(player.getServerWorld(), player.getBlockPos());
        if (zone == null || !zone.city()) return false;
        bridge.defense.report(player.getServerWorld(), zone, player, serious ? VillageAlarm.Level.ATTACK : VillageAlarm.Level.THREAT, cause);
        return true;
    }

    /** Whether the police keep order at this spot (a city with the police on). */
    static boolean guarded(ServerWorld world, BlockPos pos) {
        MobGameBridge bridge = MobGameBridge.active(world.getServer());
        if (bridge == null || !bridge.defense.police.on()) return false;
        VillageDefenseController.Zone zone = bridge.defense.zoneAt(world, pos);
        return zone != null && zone.city();
    }

    // ---- The tick ----

    void tick(ServerWorld world, List<VillageDefenseController.Zone> cities) {
        long now = world.getTime();
        if (!cuffed.isEmpty()) cuffs(world, now);
        if (!takenAway.isEmpty()) takeAway(world, now);
        if (now % LIGHTS_PERIOD != LIGHTS_PHASE) return;
        inmates(world, now);
        boolean lights = on() && bridge.config.policeCars;
        for (VillageDefenseController.Zone zone : cities) {
            VillageAlarm.Alarm alarm = defense.alarm(zone);
            boolean flash = lights && alarm != null && (alarm.attack() || !alarm.targets.isEmpty());
            for (BlockPos post : defense.guards.posts(world, zone)) lights(world, post, flash);
        }
    }

    /** The cars by the post flash, or go dark. */
    private void lights(ServerWorld world, BlockPos post, boolean on) {
        for (BlockPos pos : BlockPos.iterate(post.add(-CAR_REACH, -3, -CAR_REACH), post.add(CAR_REACH, 3, CAR_REACH))) {
            if (!world.isChunkLoaded(pos)) continue;
            BlockState state = world.getBlockState(pos);
            if (PoliceContent.car(state) && state.get(PoliceContent.LIT) != on) world.setBlockState(pos, state.with(PoliceContent.LIT, on), Block.NOTIFY_LISTENERS);
        }
    }

    // ---- The siren ----

    /** The city's alarm sounds: the siren from each post's lit cars (or from the post), and the city's players beyond its reach hear
     * it faintly where they are. Called once a ring period from the defence instead of the bell. */
    void siren(ServerWorld world, VillageDefenseController.Zone zone, List<ServerPlayerEntity> listeners) {
        List<BlockPos> from = new ArrayList<>();
        for (BlockPos post : defense.guards.posts(world, zone)) {
            BlockPos car = car(world, post);
            from.add(car != null ? car : post);
            if (from.size() >= 3) break;
        }
        if (from.isEmpty()) from.add(zone.center());
        for (BlockPos at : from) world.playSound(null, at, PoliceContent.SIREN, SoundCategory.BLOCKS, 4f, 1f);
        if (!bridge.config.alarmSound) return;
        for (ServerPlayerEntity player : listeners) {
            double nearest = Double.MAX_VALUE;
            for (BlockPos at : from) nearest = Math.min(nearest, player.getBlockPos().getSquaredDistance(at));
            if (nearest > 80 * 80) player.playSoundToPlayer(PoliceContent.SIREN, SoundCategory.BLOCKS, 0.35f, 1f);
        }
    }

    /** A policeman has an offender in sight: the «кряк» from the nearest car, once in a while per post. */
    void quack(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity policeman, long now) {
        Long last = lastQuack.get(zone.key());
        if (last != null && now - last < QUACK_TICKS) return;
        lastQuack.put(zone.key(), now);
        BlockPos car = null;
        for (BlockPos post : defense.guards.posts(world, zone))
            if (post.getSquaredDistance(policeman.getBlockPos()) < 32 * 32 && (car = car(world, post)) != null) break;
        world.playSound(null, car != null ? car : policeman.getBlockPos(), PoliceContent.QUACK, SoundCategory.NEUTRAL, 2.5f, 1f);
    }

    private static BlockPos car(ServerWorld world, BlockPos post) {
        for (BlockPos pos : BlockPos.iterate(post.add(-CAR_REACH, -3, -CAR_REACH), post.add(CAR_REACH, 3, CAR_REACH)))
            if (world.isChunkLoaded(pos) && PoliceContent.car(world.getBlockState(pos))) return pos.toImmutable();
        return null;
    }

    // ---- The car by a new post ----

    /** A new post gets a police car parked beside it: three free cells in a row on firm ground, within a few blocks; the kind by
     * chance (the white Zhiguli most often, the UAZ, now and then the Soviet militia's yellow one). */
    void park(ServerWorld world, BlockPos post) {
        if (!bridge.config.policeCars || PoliceContent.CAR_BLOCKS.isEmpty() || car(world, post) != null) return;
        Random random = new Random(post.asLong());
        String kind = random.nextInt(10) < 6 ? "car_police" : random.nextInt(3) < 2 ? "car_police_uaz" : "car_militsiya";
        Block block = PoliceContent.CAR_BLOCKS.get(kind);
        for (int r = 2; r <= 6; r++)
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos c = post.offset(side, r);
                if (!world.isChunkLoaded(c)) continue;
                c = new BlockPos(c.getX(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ()), c.getZ());
                for (Direction along : List.of(side.rotateYClockwise(), side)) {
                    if (!fits(world, c, along)) continue;
                    BlockState state = block.getDefaultState().with(HorizontalFacingBlock.FACING, along).with(PoliceContent.LIT, false);
                    world.setBlockState(c, state, Block.NOTIFY_ALL);
                    for (BlockPos end : List.of(c.offset(along), c.offset(along.getOpposite())))
                        world.setBlockState(end, CityContent.CAR_PART.getDefaultState(), Block.NOTIFY_ALL);
                    return;
                }
            }
    }

    private static boolean fits(ServerWorld world, BlockPos c, Direction along) {
        for (BlockPos cell : List.of(c, c.offset(along), c.offset(along.getOpposite()))) {
            BlockState here = world.getBlockState(cell), below = world.getBlockState(cell.down());
            if (!(here.isAir() || here.isReplaceable()) || !world.getFluidState(cell).isEmpty()) return false;
            if (!world.getBlockState(cell.up()).isAir() || !below.isSolidBlock(world, cell.down())) return false;
            if (below.isOf(Blocks.FARMLAND) || PoliceContent.car(below) || below.isOf(PoliceContent.POLICE_POST)) return false;
            if (Math.abs(world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, cell.getX(), cell.getZ()) - c.getY()) > 0) return false;
        }
        return true;
    }

    // ---- The blow and the arrest ----

    /** A policeman's blow at arm's length: a baton stuns; an offender (a player the alarm is against, or a wanted creature) worn down
     * to the arrest line is arrested instead. A monster just takes the blow. */
    void strike(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity policeman, LivingEntity foe, long now) {
        boolean offender = foe instanceof ServerPlayerEntity || foe.getCommandTags().contains(WANTED);
        if (offender && bridge.config.policeArrest && foe.getHealth() <= bridge.config.policeArrestHealth) { arrest(world, zone, policeman, foe, now); return; }
        if (offender) quack(world, zone, policeman, now);
        if (foe.damage(world.getDamageSources().mobAttack(policeman), bridge.config.policeDamage)) {
            foe.takeKnockback(0.35, policeman.getX() - foe.getX(), policeman.getZ() - foe.getZ());
            if (offender) foe.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1), policeman);
            world.playSound(null, foe.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.NEUTRAL, 0.8f, 0.9f);
        }
    }

    /** Whether the police leave this one be: it is in handcuffs or in the cell already. */
    boolean held(LivingEntity entity) {
        if (cuffed.containsKey(entity.getUuid()) || takenAway.containsKey(entity.getUuid())) return true;
        return entity.getWorld() instanceof ServerWorld world && saved(world).inmates.containsKey(entity.getUuid());
    }

    private void arrest(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity policeman, LivingEntity foe, long now) {
        if (held(foe)) return;
        policeman.swingHand(net.minecraft.util.Hand.OFF_HAND);
        world.playSound(null, foe.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_CHAIN.value(), SoundCategory.NEUTRAL, 1.2f, 1.3f);
        world.spawnParticles(ParticleTypes.CRIT, foe.getX(), foe.getY() + 1, foe.getZ(), 12, 0.3, 0.4, 0.3, 0.1);
        int ticks = (int) CUFF_TICKS + 20;
        foe.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, ticks, 5, false, false, true));
        foe.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, ticks, 4, false, false, true));
        foe.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, ticks, 4, false, false, true));
        String officer = name(policeman);
        if (foe instanceof ServerPlayerEntity player) {
            cuffed.put(player.getUuid(), now + CUFF_TICKS);
            cuffedBy.put(player.getUuid(), zone.key());
            defense.release(world, zone, player);
            int fine = fine(player);
            title(player, Text.translatable("mobtalk.police.arrested.title").formatted(Formatting.RED, Formatting.BOLD),
                    Text.translatable("mobtalk.police.arrested.subtitle", officer));
            player.sendMessage(Text.translatable(fine > 0 ? "mobtalk.police.arrested.fined" : "mobtalk.police.arrested", officer, fine,
                    bridge.config.policeJailSeconds).formatted(Formatting.RED), false);
            say(world, zone, policeman, "mobtalk.police.say.arrest." + world.random.nextInt(3), MobGameBridge.playerName(player));
            announce(world, zone, Text.translatable("mobtalk.police.arrested.city", zone.title(), MobGameBridge.playerName(player), officer));
        } else {
            takenAway.put(foe.getUuid(), now + CUFF_TICKS);
            if (foe instanceof MobEntity mob) { mob.setAiDisabled(true); mob.setTarget(null); }
            say(world, zone, policeman, "mobtalk.police.say.taken." + world.random.nextInt(3), foe.getName().getString());
            announce(world, zone, Text.translatable("mobtalk.police.taken.city", zone.title(), foe.getName().getString(), officer));
        }
    }

    /** The fine: up to {@code policeFine} emeralds from the offender's pockets. */
    private int fine(ServerPlayerEntity player) {
        int due = bridge.config.policeFine, taken = 0;
        if (due <= 0 || player.isCreative()) return 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.size() && taken < due; i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isOf(Items.EMERALD)) continue;
            int take = Math.min(due - taken, stack.getCount());
            stack.decrement(take);
            taken += take;
        }
        return taken;
    }

    /** The cuffs come off at the cell door: into the cell under the city's first post. */
    private void cuffs(ServerWorld world, long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = cuffed.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(entry.getKey());
            String key = cuffedBy.get(entry.getKey());
            if (player == null || player.getWorld() != world || !player.isAlive()) {
                if (player == null || !player.isAlive()) { it.remove(); cuffedBy.remove(entry.getKey()); }
                continue;
            }
            if (now % 10 == 0) world.spawnParticles(ParticleTypes.ENCHANTED_HIT, player.getX(), player.getY() + 1.1, player.getZ(), 3, 0.2, 0.1, 0.2, 0);
            if (now < entry.getValue()) continue;
            it.remove(); cuffedBy.remove(entry.getKey());
            VillageDefenseController.Zone zone = key == null ? null : defense.zone(world, key);
            if (zone != null) jail(world, zone, player, now);
        }
    }

    private void takeAway(ServerWorld world, long now) {
        for (Iterator<Map.Entry<UUID, Long>> it = takenAway.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            var entity = world.getEntity(entry.getKey());
            if (entity == null) { if (now > entry.getValue() + 1200) it.remove(); continue; }
            if (now < entry.getValue()) continue;
            it.remove();
            world.spawnParticles(ParticleTypes.POOF, entity.getX(), entity.getY() + 0.8, entity.getZ(), 16, 0.3, 0.5, 0.3, 0.02);
            world.playSound(null, entity.getBlockPos(), SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.NEUTRAL, 1f, 1f);
            entity.discard(); // «увезли в отделение»
        }
    }

    // ---- The cell ----

    /** One sentence: whose, when it ends, the cell, where to let them out, why. */
    static final class Inmate {
        final UUID id; final long until; final BlockPos cell, exit; final String zone;
        Inmate(UUID id, long until, BlockPos cell, BlockPos exit, String zone) { this.id = id; this.until = until; this.cell = cell; this.exit = exit; this.zone = zone; }
        NbtCompound write() {
            NbtCompound tag = new NbtCompound();
            tag.putUuid("id", id); tag.putLong("until", until); tag.putLong("cell", cell.asLong()); tag.putLong("exit", exit.asLong()); tag.putString("zone", zone);
            return tag;
        }
        static Inmate read(NbtCompound tag) {
            return new Inmate(tag.getUuid("id"), tag.getLong("until"), BlockPos.fromLong(tag.getLong("cell")), BlockPos.fromLong(tag.getLong("exit")), tag.getString("zone"));
        }
    }

    static final class Saved extends PersistentState {
        final Map<UUID, Inmate> inmates = new LinkedHashMap<>();
        /** The cell of each city, by its zone key. */
        final Map<String, BlockPos> cells = new LinkedHashMap<>();
        static final Type<Saved> TYPE = new Type<>(Saved::new, Saved::read, null);
        static Saved read(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            Saved saved = new Saved();
            NbtList list = tag.getList("inmates", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) { Inmate inmate = Inmate.read(list.getCompound(i)); saved.inmates.put(inmate.id, inmate); }
            NbtCompound cells = tag.getCompound("cells");
            for (String key : cells.getKeys()) saved.cells.put(key, BlockPos.fromLong(cells.getLong(key)));
            return saved;
        }
        @Override public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup lookup) {
            NbtList list = new NbtList();
            for (Inmate inmate : inmates.values()) list.add(inmate.write());
            tag.put("inmates", list);
            NbtCompound cells = new NbtCompound();
            this.cells.forEach((key, pos) -> cells.putLong(key, pos.asLong()));
            tag.put("cells", cells);
            return tag;
        }
    }
    private static Saved saved(ServerWorld world) { return world.getPersistentStateManager().getOrCreate(Saved.TYPE, "mobtalk_police"); }

    private void jail(ServerWorld world, VillageDefenseController.Zone zone, ServerPlayerEntity player, long now) {
        List<BlockPos> posts = defense.guards.posts(world, zone);
        BlockPos post = posts.isEmpty() ? zone.center() : posts.getFirst();
        Saved saved = saved(world);
        BlockPos cell = saved.cells.get(zone.key());
        if (cell == null) {
            int y = Math.max(world.getBottomY() + 6, post.getY() - CELL_DEPTH);
            cell = new BlockPos(post.getX(), y, post.getZ());
            saved.cells.put(zone.key(), cell);
        }
        build(world, cell);
        BlockPos exit = outside(world, post);
        saved.inmates.put(player.getUuid(), new Inmate(player.getUuid(), now + bridge.config.policeJailSeconds * 20L, cell, exit, zone.key()));
        saved.markDirty();
        player.teleport(world, cell.getX() + 0.5, cell.getY(), cell.getZ() - 0.5, 0f, 0f);
        world.playSound(null, cell, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundCategory.BLOCKS, 1f, 0.8f);
        title(player, Text.translatable("mobtalk.police.cell.title").formatted(Formatting.GRAY, Formatting.BOLD),
                Text.translatable("mobtalk.police.cell.subtitle", bridge.config.policeJailSeconds));
    }

    /** The holding cell: a shell of cell wall 5 wide, 5 long and 5 high around a room 3x3x3; the bunk at the back, bars across the
     * front with a strip of corridor behind them and a lantern there. Built again over whatever is there each time someone goes in. */
    private static void build(ServerWorld world, BlockPos c) {
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 3; y++) for (int z = -3; z <= 3; z++) {
            BlockPos p = c.add(x, y, z);
            boolean shell = Math.abs(x) == 2 || y == -1 || y == 3 || Math.abs(z) == 3;
            BlockState want = shell ? PoliceContent.JAIL_WALL.getDefaultState() : Blocks.AIR.getDefaultState();
            if (!shell && z == 1) want = Blocks.IRON_BARS.getDefaultState(); // the bars; IronBars join on their own when updated
            if (!shell && z == 2 && x == 0 && y == 0) want = Blocks.LANTERN.getDefaultState();
            if (!world.getBlockState(p).equals(want)) world.setBlockState(p, want, Block.NOTIFY_ALL);
        }
        Block bunk = CityContent.BLOCKS.get("long_bench");
        if (bunk != null)
            for (int x = -1; x <= 1; x++) {
                BlockPos p = c.add(x, 0, -2);
                BlockState want = bunk.getDefaultState().with(HorizontalFacingBlock.FACING, Direction.SOUTH);
                if (!world.getBlockState(p).equals(want)) world.setBlockState(p, want, Block.NOTIFY_ALL);
            }
    }
    private static Box cellBox(BlockPos c) { return new Box(c.getX() - 2, c.getY() - 1, c.getZ() - 3, c.getX() + 3, c.getY() + 4, c.getZ() + 4); }

    /** A free spot by the post to let a released inmate out. */
    private static BlockPos outside(ServerWorld world, BlockPos post) {
        for (Direction side : Direction.Type.HORIZONTAL) {
            BlockPos p = post.offset(side);
            p = new BlockPos(p.getX(), world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()), p.getZ());
            if (world.getBlockState(p).isAir() && world.getBlockState(p.up()).isAir()) return p;
        }
        return post.up();
    }

    /** The inmates: let out those whose time is up, call an escape on those gone from their cell early. */
    private void inmates(ServerWorld world, long now) {
        Saved saved = saved(world);
        if (saved.inmates.isEmpty()) return;
        for (Iterator<Inmate> it = saved.inmates.values().iterator(); it.hasNext(); ) {
            Inmate inmate = it.next();
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(inmate.id);
            if (player == null || player.getWorld() != world) continue; // the sentence waits for them
            boolean inside = cellBox(inmate.cell).contains(player.getPos());
            if (now >= inmate.until) {
                it.remove(); saved.markDirty();
                if (inside) player.teleport(world, inmate.exit.getX() + 0.5, inmate.exit.getY(), inmate.exit.getZ() + 0.5, player.getYaw(), 0f);
                player.sendMessage(Text.translatable("mobtalk.police.released").formatted(Formatting.GREEN), false);
                continue;
            }
            if (!inside && player.isAlive()) {
                it.remove(); saved.markDirty();
                player.sendMessage(Text.translatable("mobtalk.police.escaped").formatted(Formatting.RED), false);
                VillageDefenseController.Zone zone = defense.zone(world, inmate.zone);
                if (zone != null) defense.report(world, zone, player, VillageAlarm.Level.ATTACK, "побег из изолятора");
            } else if (now % 200 == LIGHTS_PHASE)
                player.sendMessage(Text.translatable("mobtalk.police.cell.left", (inmate.until - now + 19) / 20).formatted(Formatting.GRAY), true);
        }
    }

    /** A player died: out of the handcuffs, and out of the cell's books (a death in the cell is no escape). */
    void died(ServerPlayerEntity player) {
        cuffed.remove(player.getUuid()); cuffedBy.remove(player.getUuid());
        Saved saved = saved(player.getServerWorld());
        if (saved.inmates.remove(player.getUuid()) != null) saved.markDirty();
    }

    // ---- Words ----

    private static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 50, 15));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }

    private void say(ServerWorld world, VillageDefenseController.Zone zone, VillagerEntity policeman, String key, String who) {
        Text line = Text.literal("<" + name(policeman) + "> ").append(Text.translatable(key, who));
        for (ServerPlayerEntity player : world.getPlayers()) if (player.squaredDistanceTo(policeman) <= 32 * 32) player.sendMessage(line, false);
    }

    private void announce(ServerWorld world, VillageDefenseController.Zone zone, Text text) {
        if (!bridge.config.alarmChat) return;
        double range = bridge.config.alarmNoticeRange;
        for (ServerPlayerEntity player : world.getPlayers())
            if (player.getBlockPos().getSquaredDistance(zone.center()) <= range * range) player.sendMessage(text.copy().formatted(Formatting.GOLD), false);
    }

    private String name(VillagerEntity villager) {
        String name = bridge.memory.displayName(villager.getUuid());
        return name == null || name.isBlank() ? villager.getName().getString() : name;
    }

    void clearRuntime() { cuffed.clear(); cuffedBy.clear(); takenAway.clear(); lastQuack.clear(); }
}
