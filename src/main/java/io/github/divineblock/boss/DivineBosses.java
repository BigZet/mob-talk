package io.github.divineblock.boss;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.random.Random;

/** The sacred bosses: registration and the API the rest of the mod (the church, the cemetery, the god's punishments) uses to
 * bring them into the world.
 * <pre>
 *   DivineBosses.spawn("black_schemnik", world, pos, 3);              // one boss by id, power 1..5
 *   DivineBosses.spawnFor(BossKind.Place.CEMETERY, world, pos, 2, r); // a random boss of a place
 * </pre>
 * A boss switched off in the settings comes as a buffed vanilla mob instead ({@link #fallback}), so callers never need to
 * check the settings. Structures place a {@link BossSealBlock}: the boss rises from it when a player comes near. */
public final class DivineBosses {
    private static final Map<BossKind, EntityType<? extends SacredBoss>> TYPES = new EnumMap<>(BossKind.class);
    public static final EntityType<SchemnikEntity> BLACK_SCHEMNIK = entity(BossKind.BLACK_SCHEMNIK, SchemnikEntity::new, 1.1f, 2.7f, false);
    public static final EntityType<BellRingerEntity> BELL_RINGER = entity(BossKind.BELL_RINGER, BellRingerEntity::new, 3.2f, 5.0f, false);
    public static final EntityType<SeraphEntity> SERAPH = entity(BossKind.SERAPH, SeraphEntity::new, 3.4f, 7.2f, true);
    public static final EntityType<FacelessIconEntity> FACELESS_ICON = entity(BossKind.FACELESS_ICON, FacelessIconEntity::new, 3.0f, 4.5f, false);
    public static final EntityType<WailerEntity> WAILER = entity(BossKind.WAILER, WailerEntity::new, 0.9f, 2.9f, false);
    public static final EntityType<ArchangelEntity> ARCHANGEL = entity(BossKind.ARCHANGEL, ArchangelEntity::new, 2.2f, 7.4f, true);
    public static final EntityType<LeviathanEntity> LEVIATHAN = entity(BossKind.LEVIATHAN, LeviathanEntity::new, 4.0f, 2.0f, false);
    public static final EntityType<BehemothEntity> BEHEMOTH = entity(BossKind.BEHEMOTH, BehemothEntity::new, 3.2f, 3.0f, false);
    public static final EntityType<GoliathEntity> GOLIATH = entity(BossKind.GOLIATH, GoliathEntity::new, 1.6f, 4.6f, false);
    public static final EntityType<HorsemenEntity> HORSEMEN = entity(BossKind.HORSEMEN, HorsemenEntity::new, 1.6f, 3.2f, true);
    public static final EntityType<DestroyingAngelEntity> DESTROYING_ANGEL = entity(BossKind.DESTROYING_ANGEL, DestroyingAngelEntity::new, 1.0f, 2.8f, true);
    public static final EntityType<CherubEntity> CHERUB = entity(BossKind.CHERUB, CherubEntity::new, 2.0f, 5.8f, true);
    public static final EntityType<BeastEntity> BEAST = entity(BossKind.BEAST, BeastEntity::new, 3.2f, 3.6f, true);
    public static final EntityType<NimrodEntity> NIMROD = entity(BossKind.NIMROD, NimrodEntity::new, 1.1f, 2.7f, false);

    /** Holy relics: one from each boss, for the altar's great craft (item tag {@code divineblock:holy_relics}). */
    public static final Map<BossKind, Item> RELICS = new EnumMap<>(BossKind.class);
    public static final Block SEAL = new BossSealBlock(AbstractBlock.Settings.create().mapColor(MapColor.BLACK).strength(50f, 1200f)
            .luminance(state -> 3).sounds(BlockSoundGroup.DEEPSLATE_TILES).requiresTool());
    public static BlockEntityType<BossSealBlockEntity> SEAL_ENTITY;

    private static final Map<EntityType<?>, Double> HEALTH = new java.util.HashMap<>();

    private DivineBosses() { }

    private static <T extends SacredBoss> EntityType<T> entity(BossKind kind, EntityType.EntityFactory<T> factory, float width, float height, boolean fireImmune) {
        EntityType.Builder<T> builder = EntityType.Builder.create(factory, SpawnGroup.MONSTER).dimensions(width, height).eyeHeight(height * 0.85f).maxTrackingRange(10);
        if (fireImmune) builder.makeFireImmune();
        EntityType<T> type = Registry.register(Registries.ENTITY_TYPE, Identifier.of(DivineBlockMod.ID, kind.id), builder.build(DivineBlockMod.ID + ":" + kind.id));
        TYPES.put(kind, type);
        return type;
    }

    /** Called once from the mod initializer. */
    public static void register() {
        attributes(BLACK_SCHEMNIK, 320, 0.26, 10);
        attributes(BELL_RINGER, 420, 0.2, 16);
        attributes(SERAPH, 280, 0.3, 6);
        attributes(FACELESS_ICON, 300, 0.18, 12);
        attributes(WAILER, 260, 0.28, 6);
        attributes(ARCHANGEL, 600, 0.3, 20);
        attributes(LEVIATHAN, 520, 0.3, 14);
        attributes(BEHEMOTH, 560, 0.24, 18);
        attributes(GOLIATH, 380, 0.25, 14);
        attributes(HORSEMEN, 720, 0.32, 10);
        attributes(DESTROYING_ANGEL, 260, 0.3, 6);
        attributes(CHERUB, 340, 0.28, 12);
        attributes(BEAST, 620, 0.26, 16);
        attributes(NIMROD, 300, 0.26, 8);
        BossEffects.register();
        // Each boss keeps the event listeners of its own affliction in its class.
        SchemnikEntity.registerHooks(); BellRingerEntity.registerHooks(); SeraphEntity.registerHooks(); FacelessIconEntity.registerHooks();
        WailerEntity.registerHooks(); ArchangelEntity.registerHooks(); LeviathanEntity.registerHooks(); BehemothEntity.registerHooks();
        GoliathEntity.registerHooks(); HorsemenEntity.registerHooks(); DestroyingAngelEntity.registerHooks(); CherubEntity.registerHooks();
        BeastEntity.registerHooks(); NimrodEntity.registerHooks();
        String[][] relics = { { "schema_rosary", "BLACK_SCHEMNIK" }, { "tocsin_tongue", "BELL_RINGER" }, { "seraph_feather", "SERAPH" },
                { "icon_oklad", "FACELESS_ICON" }, { "mourning_veil", "WAILER" }, { "wrath_sword_shard", "ARCHANGEL" },
                { "leviathan_scale", "LEVIATHAN" }, { "behemoth_horn", "BEHEMOTH" }, { "goliath_spearhead", "GOLIATH" },
                { "broken_seal", "HORSEMEN" }, { "hyssop_branch", "DESTROYING_ANGEL" }, { "cherub_flame", "CHERUB" },
                { "beast_horn", "BEAST" }, { "babel_brick", "NIMROD" } };
        for (String[] relic : relics) {
            Item item = Registry.register(Registries.ITEM, Identifier.of(DivineBlockMod.ID, relic[0]),
                    new Item(new Item.Settings().maxCount(16).rarity(Rarity.EPIC).fireproof().component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)));
            RELICS.put(BossKind.valueOf(relic[1]), item);
        }
        Registry.register(Registries.BLOCK, Identifier.of(DivineBlockMod.ID, "boss_seal"), SEAL);
        Item sealItem = Registry.register(Registries.ITEM, Identifier.of(DivineBlockMod.ID, "boss_seal"), new BlockItem(SEAL, new Item.Settings().rarity(Rarity.EPIC)));
        SEAL_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of(DivineBlockMod.ID, "boss_seal"),
                BlockEntityType.Builder.create(BossSealBlockEntity::new, SEAL).build(null));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> RELICS.values().forEach(entries::add));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.OPERATOR).register(entries -> entries.add(sealItem));
        BossCommands.register();
    }

    private static void attributes(EntityType<? extends SacredBoss> type, double health, double speed, double armor) {
        HEALTH.put(type, health);
        FabricDefaultAttributeRegistry.register(type, SacredBoss.attributes(health, speed, armor));
    }
    static double defaultHealth(EntityType<?> type) { return HEALTH.getOrDefault(type, 300.0); }

    public static EntityType<? extends SacredBoss> type(BossKind kind) { return TYPES.get(kind); }

    public static List<String> ids() { return java.util.Arrays.stream(BossKind.values()).map(kind -> kind.id).toList(); }

    public static boolean enabled(BossKind kind, DivineConfig config) { return !config.disabledBosses.contains(kind.id); }

    /** Brings boss {@code id} into the world at {@code pos} with power 1..5; null if the id is unknown or there is no room.
     * A switched-off boss comes as a buffed vanilla mob. */
    public static LivingEntity spawn(String id, ServerWorld world, BlockPos pos, int power) {
        BossKind kind = BossKind.byId(id);
        return kind == null ? null : spawn(kind, world, pos, power, null);
    }

    /** A random boss of {@code place} (the church's crypt, the cemetery, the god's punishment, the final one). */
    public static LivingEntity spawnFor(BossKind.Place place, ServerWorld world, BlockPos pos, int power, Random random) {
        List<BossKind> pool = new ArrayList<>(BossKind.at(place));
        if (pool.isEmpty()) return null;
        return spawn(pool.get(random.nextInt(pool.size())), world, pos, power, null);
    }

    /** The full form: {@code target} (may be null) is attacked at once; the boss remembers {@code pos} as home. */
    public static LivingEntity spawn(BossKind kind, ServerWorld world, BlockPos pos, int power, PlayerEntity target) {
        DivineConfig config = DivineBlockMod.config();
        BlockPos at = room(world, pos, 3);
        if (at == null) return null;
        LivingEntity spawned;
        if (enabled(kind, config)) {
            SacredBoss boss = type(kind).create(world);
            if (boss == null) return null;
            boss.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360f, 0);
            boss.setPower(power);
            boss.setHome(at);
            if (target != null) boss.setTarget(target);
            world.spawnEntity(boss);
            spawned = boss;
        } else {
            spawned = fallback(kind, world, at, power, target);
            if (spawned == null) return null;
        }
        world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, 80, 0.8, 1.2, 0.8, 0.03);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 40, 1, 0.5, 1, 0.02);
        world.playSound(null, at, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 3f, 0.5f);
        world.playSound(null, at, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 2f, 0.6f);
        if (config.debug) DivineBlockMod.LOG.info("Божественный блок: босс {} ({}) ×{} в {}", kind.id, spawned.getType().getUntranslatedName(), power, at.toShortString());
        return spawned;
    }

    /** The vanilla-safe stand-in for a switched-off boss: a vanilla mob with the boss's name, more health and strength. */
    public static MobEntity fallback(BossKind kind, ServerWorld world, BlockPos at, int power, PlayerEntity target) {
        EntityType<? extends MobEntity> type = switch (kind) {
            case BLACK_SCHEMNIK -> EntityType.WITHER_SKELETON;
            case BELL_RINGER -> EntityType.RAVAGER;
            case SERAPH -> EntityType.BLAZE;
            case FACELESS_ICON -> EntityType.EVOKER;
            case WAILER -> EntityType.WITCH;
            case ARCHANGEL, GOLIATH, CHERUB -> EntityType.VINDICATOR;
            case LEVIATHAN -> EntityType.GUARDIAN;
            case BEHEMOTH, BEAST -> EntityType.RAVAGER;
            case HORSEMEN -> EntityType.SKELETON;
            case DESTROYING_ANGEL -> EntityType.VEX;
            case NIMROD -> EntityType.EVOKER;
        };
        MobEntity mob = type.create(world);
        if (mob == null) return null;
        mob.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360f, 0);
        mob.setCustomName(Text.literal(kind.title).formatted(Formatting.GOLD));
        mob.setCustomNameVisible(true);
        mob.setPersistent();
        var health = mob.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) health.addPersistentModifier(new EntityAttributeModifier(Identifier.of(DivineBlockMod.ID, "boss_stand_in"),
                1.0 + Math.max(1, Math.min(5, power)), EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        mob.setHealth(mob.getMaxHealth());
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, StatusEffectInstance.INFINITE, power >= 4 ? 1 : 0, false, false));
        mob.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
        if (kind == BossKind.ARCHANGEL || kind == BossKind.CHERUB) {
            mob.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
            mob.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.GOLDEN_CHESTPLATE));
            mob.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.GOLDEN_SWORD));
        }
        if (target != null) mob.setTarget(target);
        world.spawnEntity(mob);
        return mob;
    }

    /** A free spot at {@code pos} or a few blocks above it, tall enough for the biggest boss. */
    static BlockPos room(ServerWorld world, BlockPos pos, int up) {
        for (int dy = 0; dy <= up; dy++) {
            BlockPos at = pos.up(dy);
            boolean free = true;
            for (int y = 0; y < 3 && free; y++) free = world.getBlockState(at.up(y)).getCollisionShape(world, at.up(y)).isEmpty();
            if (free) return at;
        }
        return null;
    }

    /** Sacred bosses alive within {@code radius} blocks. */
    public static List<SacredBoss> near(ServerWorld world, BlockPos pos, double radius) {
        return world.getEntitiesByClass(SacredBoss.class, new Box(pos).expand(radius), SacredBoss::isAlive);
    }
}
