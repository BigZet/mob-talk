package io.github.mobtalk.game;

import io.github.mobtalk.core.Mischief;
import io.github.mobtalk.core.MobState;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BarrelBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Misdeeds without a blow, seen by the village: a chest or barrel opened, a crop taken off a plot, a villager's work block, a door
 * or a bed broken. Every villager (and golem) in sight of the spot takes it personally through the world memory — a smaller drop
 * than a blow, a memory line «Видел, как игрок …», suspicion in the emotional state — and the owner of a broken work block or bed
 * notices even without seeing. Blocks the player placed in this session are theirs, and nothing outside a village counts.
 * The rules live in {@link Mischief}; this class only reads the world. Server thread only. */
public final class MischiefWatch {
    /** How far a villager sees a misdeed, in blocks from the block itself. */
    /** How far villagers see a misdeed: the witnesses sight range from the settings. */
    static double sight() { return io.github.mobtalk.core.Tuning.get().witnessSightRange; }
    /** How far the owner of a work block or bed may be to notice it broken. */
    static final double OWNER_RANGE = 48;
    /** How many chunk sections from an occupied point of interest (a bed, a job site, a bell) still count as the village. */
    static final int VILLAGE_SECTIONS = 2;
    /** The job-site blocks of the vanilla professions, for a work block whose owner is not around to claim it. */
    static final Set<String> JOB_BLOCKS = Set.of("minecraft:barrel", "minecraft:blast_furnace", "minecraft:brewing_stand", "minecraft:cartography_table",
            "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:composter", "minecraft:fletching_table", "minecraft:grindstone", "minecraft:lectern",
            "minecraft:loom", "minecraft:smithing_table", "minecraft:smoker", "minecraft:stonecutter");
    private static boolean registered;
    private final MobGameBridge bridge;
    private final Mischief.Ledger ledger = new Mischief.Ledger();

    MischiefWatch(MobGameBridge bridge) { this.bridge = bridge; }

    /** Server-side block events: a right-click on a container, a block broken by the player. */
    public static void initialize() {
        if (registered) return;
        registered = true;
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand == Hand.MAIN_HAND && player instanceof ServerPlayerEntity server && world instanceof ServerWorld serverWorld) {
                MobGameBridge bridge = MobGameBridge.forEntity(server);
                if (bridge != null) bridge.mischief.used(serverWorld, server, hit.getBlockPos());
            }
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (player instanceof ServerPlayerEntity server && world instanceof ServerWorld serverWorld) {
                MobGameBridge bridge = MobGameBridge.forEntity(server);
                if (bridge != null) bridge.mischief.broke(serverWorld, server, pos, state);
            }
        });
    }

    /** BlockItem.place succeeded: the block (a chest, a door, a seed) is the player's own from now on. */
    public static void placed(ItemPlacementContext context) {
        if (!(context.getPlayer() instanceof ServerPlayerEntity player) || !(context.getWorld() instanceof ServerWorld world)) return;
        MobGameBridge bridge = MobGameBridge.forEntity(player);
        BlockPos pos = context.getBlockPos();
        if (bridge != null) bridge.mischief.ledger.placed(dimension(world), pos.getX(), pos.getY(), pos.getZ());
    }

    /** The player clicked a block: a chest or a barrel about to open. Sneaking with an item in hand places instead of opening. */
    void used(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        if (!bridge.config.enabled || player.isSpectator()) return;
        if (player.shouldCancelInteraction() && (!player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty())) return;
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock)) return;
        notice(world, player, pos, Mischief.Kind.CONTAINER, name(state), null);
    }

    /** The player broke a block: a villager's work block, a bed, a door, a crop. */
    void broke(ServerWorld world, ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!bridge.config.enabled || state == null) return;
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        VillagerEntity owner = owner(world, pos, MemoryModuleType.JOB_SITE);
        if (owner != null || JOB_BLOCKS.contains(id)) { notice(world, player, pos, Mischief.Kind.WORKSTATION, name(state), owner); return; }
        if (state.isIn(BlockTags.BEDS)) {
            owner = owner(world, pos, MemoryModuleType.HOME);
            for (BlockPos half : List.of(pos.north(), pos.south(), pos.east(), pos.west())) if (owner == null) owner = owner(world, half, MemoryModuleType.HOME);
            notice(world, player, pos, Mischief.Kind.BED, name(state), owner); return;
        }
        if (state.isIn(BlockTags.DOORS)) { notice(world, player, pos, Mischief.Kind.DOOR, name(state), null); return; }
        if (state.isIn(BlockTags.CROPS) || state.getBlock() instanceof CropBlock) notice(world, player, pos, Mischief.Kind.CROP, name(state), null);
    }

    private void notice(ServerWorld world, ServerPlayerEntity player, BlockPos pos, Mischief.Kind kind, String detail, VillagerEntity owner) {
        if (ledger.ownBlock(dimension(world), pos.getX(), pos.getY(), pos.getZ())) return;
        List<MobEntity> witnesses = witnesses(world, player, pos);
        if (owner != null && owner.isAlive() && !witnesses.contains(owner)) witnesses.addFirst(owner);
        if (witnesses.isEmpty()) return;
        // A chest, a plot or a door is somebody's only inside a village; a claimed work block or bed speaks for itself.
        if (owner == null && !world.isNearOccupiedPointOfInterest(pos, VILLAGE_SECTIONS)) return;
        long now = System.currentTimeMillis();
        if (!ledger.count(player.getUuid(), kind, now)) return;
        String seen = Mischief.seen(kind, detail);
        for (MobEntity witness : witnesses)
            bridge.memory.recordWitnessed(witness.getUuid(), MobGameBridge.type(witness), witness.getName().getString(), player.getUuid(), player.getName().getString(),
                    seen, kind.penalty, MobState.Event.MISCHIEF, now);
        MobEntity first = witnesses.getFirst();
        String name = bridge.memory.displayName(first.getUuid());
        player.sendMessage(Text.literal("[Mob Talk] " + (name.isBlank() ? first.getName().getString() : name) + (witnesses.size() > 1 ? " и ещё " + (witnesses.size() - 1) : "")
                + " видел, как вы " + Mischief.done(kind, detail)).formatted(Formatting.GRAY), false);
    }

    /** Villagers and golems close to the block that can see the player. */
    private static List<MobEntity> witnesses(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        Box around = new Box(pos).expand(sight());
        List<MobEntity> result = new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, around, villager -> villager.isAlive() && !villager.isRemoved() && villager.canSee(player)));
        result.addAll(world.getEntitiesByClass(IronGolemEntity.class, around, golem -> golem.isAlive() && !golem.isRemoved() && golem.canSee(player)));
        return result;
    }
    /** The villager whose brain remembers this very block as its job site or its bed, or null. */
    private static VillagerEntity owner(ServerWorld world, BlockPos pos, MemoryModuleType<GlobalPos> memory) {
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, new Box(pos).expand(OWNER_RANGE), VillagerEntity::isAlive))
            if (villager.getBrain().getOptionalRegisteredMemory(memory).map(place -> place.dimension().equals(world.getRegistryKey()) && place.pos().equals(pos)).orElse(false)) return villager;
        return null;
    }
    private static String dimension(ServerWorld world) { return world.getRegistryKey().getValue().toString(); }
    private static String name(BlockState state) { return state.getBlock().getName().getString(); }
}
