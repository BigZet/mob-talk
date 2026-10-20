package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CocoaBlock;
import net.minecraft.block.CropBlock;
import net.minecraft.block.Fertilizable;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** Performs the catalogue's acts in the world, on the server thread. Every act checks its own parameters; one that cannot
 * happen here (nothing in hand, the Nether's roof) returns null and the god shows an omen instead. Returns what happened in a
 * few words for the chat. */
public final class Miracles {
    private Miracles() { }

    public static String perform(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config) {
        return perform(miracle, power, player, altar, prayer, config, 1);
    }

    /** {@code severity} 1..5 sets how many bosses come and how much land is cut away in great fury. */
    public static String perform(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity) {
        ServerWorld world = player.getServerWorld();
        return switch (miracle) {
            case OMEN_LIGHT -> { column(world, altar, ParticleTypes.END_ROD); sound(world, altar, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, 0.8f); yield "столб света"; }
            case OMEN_THUNDER -> { sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f); effect(player, StatusEffects.DARKNESS, 50, 0); yield "гром и тьма"; }
            case OMEN_LAUGH -> { sound(world, altar, SoundEvents.ENTITY_WITCH_CELEBRATE, 2f, 0.5f); burst(world, altar.up(), ParticleTypes.LARGE_SMOKE, 40); yield "хохот с неба"; }
            case IGNORE -> { burst(world, altar.up(), ParticleTypes.SMOKE, 15); sound(world, altar, SoundEvents.BLOCK_FIRE_EXTINGUISH, 1f, 0.6f); yield "бог молчит"; }
            case HEAL -> heal(player, power);
            case FEED -> feed(world, player, power);
            case WEATHER_CLEAR -> clearWeather(world, power);
            case BUFF -> buff(player, power);
            case GIFT_ITEM -> gift(world, player, prayer, power);
            case ORE_VEIN -> oreVein(world, player, power);
            case ENCHANT -> enchant(world, player, power);
            case REPAIR -> repair(player);
            case GUARDIAN -> guardian(world, player, power);
            case GROW -> grow(world, player, power);
            case SMITE_ENEMIES -> smite(world, player, power);
            case SAFE_HOME -> safeHome(world, player);
            case TOTEM -> { give(world, player, new ItemStack(Items.TOTEM_OF_UNDYING)); column(world, player.getBlockPos(), ParticleTypes.TOTEM_OF_UNDYING); yield "тотем бессмертия"; }
            case BLESSING -> blessing(player);
            case CHICKEN_RAIN -> chickenRain(world, player, power);
            case SWAP_ITEMS -> swap(player);
            case MOON_GRAVITY -> { effect(player, StatusEffects.JUMP_BOOST, 1200 * power, 3); effect(player, StatusEffects.SLOW_FALLING, 1200 * power, 0); yield "лунная гравитация"; }
            case TRANSMUTE -> transmute(world, player, power);
            case RANDOM_TP -> randomTeleport(world, player, power);
            case LIGHTNING -> lightning(world, player, altar, power, config);
            case CURSE_MINOR -> curse(player, power);
            case SWARM -> swarm(world, player, power);
            case CURSE_TOOL -> curseTool(world, player);
            case ACID_RAIN -> acidRain(world, altar, power);
            case FIRE_RING -> fireRing(world, player, altar, power, config);
            case METEOR -> Wrath.meteors(world, player, altar, power, severity, config);
            case ANVIL_RAIN -> Wrath.anvilRain(world, player, altar, power, config);
            case EARTHQUAKE -> Wrath.earthquake(world, player, altar, power, config);
            case SINKHOLE -> Wrath.sinkhole(world, player, altar, power, config);
            case TNT_RAIN -> Wrath.tntRain(world, player, altar, power, config);
            case UNDEAD_HORDE -> Wrath.undeadHorde(world, player, power);
            case RAID -> Wrath.raid(world, player, power);
            case HELL_EXILE -> Wrath.hellExile(world, player);
            case BLAZE_HORDE -> Wrath.blazeHorde(world, player, power);
            case METEOR_SHOWER -> Wrath.meteorShower(world, player, altar, power, config);
            case WARDEN -> Wrath.warden(world, player);
            case END_EXILE -> Wrath.endExile(world, player);
            case SKELETON_RIDERS -> Wrath.skeletonRiders(world, player, power, severity);
            case RIFTS -> Cataclysm.rifts(world, player, altar, severity, config);
            case TORNADO -> Tornadoes.summon(world, player, altar, power >= 4 ? (severity >= 3 ? 5 : 4) : (severity >= 2 ? 3 : 2),
                    power >= 4 ? 20 * 90 : 20 * 60, config);
            case GREAT_CRATER -> Cataclysm.greatCrater(world, player, altar, severity, config);
            case CHUNK_VOID -> Wrath.chunkVoid(world, player, altar, severity, config);
            case WITHER -> Wrath.withers(world, player, altar, severity);
            case DRAGON -> Wrath.dragons(world, player, severity);
            case FLIP -> Jests.flip(world, player, power);
        };
    }

    // ---- Omens and the warning before wrath. ----

    /** The omen before a serious punishment: dark sky, thunder, a red glow over the altar. */
    public static void warn(ServerPlayerEntity player, BlockPos altar) {
        ServerWorld world = player.getServerWorld();
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.5f);
        sound(world, altar, SoundEvents.AMBIENT_CAVE.value(), 2f, 0.6f);
        burst(world, altar.up(), ParticleTypes.ANGRY_VILLAGER, 20);
        column(world, altar, ParticleTypes.SOUL_FIRE_FLAME);
    }
    /** While the god speaks: the altar breathes light and the air around the player stirs, coloured by what is coming. */
    public static void speaking(ServerPlayerEntity player, BlockPos altar, Miracle.Kind kind, double seconds) {
        ServerWorld world = player.getServerWorld();
        ParticleEffect glow = switch (kind) {
            case HELP -> ParticleTypes.END_ROD; case JEST -> ParticleTypes.WITCH; case WRATH -> ParticleTypes.SOUL_FIRE_FLAME; case OMEN -> ParticleTypes.ENCHANT; };
        int steps = (int) Math.max(1, Math.round(seconds * 4));
        sound(world, altar, kind == Miracle.Kind.WRATH ? SoundEvents.AMBIENT_CAVE.value() : SoundEvents.BLOCK_BEACON_AMBIENT, 1.5f, kind == Miracle.Kind.WRATH ? 0.5f : 0.8f);
        Scheduler.repeat(5, steps, i -> {
            double angle = i * 0.6, radius = 1.2;
            for (int arm = 0; arm < 3; arm++) {
                double a = angle + arm * Math.PI * 2 / 3;
                world.spawnParticles(glow, altar.getX() + 0.5 + Math.cos(a) * radius, altar.getY() + 1.2 + (i % 8) * 0.25,
                        altar.getZ() + 0.5 + Math.sin(a) * radius, 2, 0.05, 0.05, 0.05, 0.01);
            }
            world.spawnParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.6, player.getZ(), 6, 0.6, 0.6, 0.6, 0.4);
        });
    }

    /** The moment the act falls: a burst over the altar and the player, by kind and power. Effects only; never touches blocks. */
    public static void flourish(ServerPlayerEntity player, BlockPos altar, Miracle.Kind kind, int power) {
        ServerWorld world = player.getServerWorld();
        BlockPos at = player.getBlockPos();
        switch (kind) {
            case HELP -> {
                column(world, altar, ParticleTypes.END_ROD);
                column(world, at, ParticleTypes.TOTEM_OF_UNDYING);
                world.spawnParticles(ParticleTypes.GLOW, player.getX(), player.getY() + 1, player.getZ(), 60, 1.2, 1.2, 1.2, 0.05);
                sound(world, at, SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 1.2f);
                sound(world, at, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2f, 0.7f);
                effect(player, StatusEffects.GLOWING, 60, 0);
            }
            case JEST -> {
                world.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 2, player.getZ(), 80, 1.5, 1, 1.5, 0.15);
                world.spawnParticles(ParticleTypes.NOTE, altar.getX() + 0.5, altar.getY() + 1.5, altar.getZ() + 0.5, 12, 0.8, 0.5, 0.8, 1);
                sound(world, at, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1.5f, 1.3f);
                sound(world, altar, SoundEvents.ENTITY_WITCH_CELEBRATE, 2f, 0.8f);
            }
            case WRATH -> {
                warn(player, altar);
                bolt(world, altar.add(2, 0, 2), true);
                world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, player.getX(), player.getY() + 3, player.getZ(), 1, 0, 0, 0, 0);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 80, 2, 1, 2, 0.05);
                sound(world, at, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.6f);
                if (power >= 3) {
                    sound(world, at, SoundEvents.ENTITY_WITHER_AMBIENT, 1.5f, 0.5f);
                    if (world.getRegistryKey() == World.OVERWORLD && !world.isThundering()) world.setWeather(0, 20 * 40, true, true);
                }
            }
            case OMEN -> { }
        }
    }

    // ---- Help. ----

    private static String heal(ServerPlayerEntity player, int power) {
        player.setHealth(player.getMaxHealth());
        for (StatusEffectInstance active : new ArrayList<>(player.getStatusEffects()))
            if (active.getEffectType().value().getCategory() == StatusEffectCategory.HARMFUL) player.removeStatusEffect(active.getEffectType());
        player.extinguish();
        if (power >= 2) effect(player, StatusEffects.ABSORPTION, 2400, power - 1);
        if (power >= 3) effect(player, StatusEffects.REGENERATION, 600, 1);
        burst(player.getServerWorld(), player.getBlockPos().up(), ParticleTypes.HEART, 12);
        sound(player.getServerWorld(), player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        return "исцеление";
    }

    private static String feed(ServerWorld world, ServerPlayerEntity player, int power) {
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20f);
        int pieces = 3 * power;
        Scheduler.repeat(4, pieces, i -> drop(world, player.getPos().add(world.random.nextGaussian() * 2, 6, world.random.nextGaussian() * 2),
                new ItemStack(i % 2 == 0 ? Items.COOKED_BEEF : Items.BREAD)));
        return "сытость и " + pieces + " кусков еды с неба";
    }

    private static String clearWeather(ServerWorld world, int power) {
        if (world.getRegistryKey() != World.OVERWORLD) return null;
        world.setWeather(12_000 * power, 0, false, false);
        long time = world.getTimeOfDay();
        boolean night = time % 24_000 >= 12_500;
        if (power >= 2 && night) world.setTimeOfDay((time / 24_000 + 1) * 24_000 + 1_000);
        return power >= 2 && night ? "ясное утро" : "ясное небо";
    }

    private static String buff(ServerPlayerEntity player, int power) {
        int ticks = 20 * 60 * (power == 1 ? 3 : power == 2 ? 5 : 8);
        int amplifier = Math.min(1, power - 1);
        effect(player, StatusEffects.SPEED, ticks, amplifier);
        effect(player, StatusEffects.STRENGTH, ticks, amplifier);
        effect(player, StatusEffects.NIGHT_VISION, ticks, 0);
        effect(player, StatusEffects.LUCK, ticks, amplifier);
        if (power >= 3) effect(player, StatusEffects.RESISTANCE, ticks, 0);
        return "благословение силы на " + ticks / 1200 + " мин";
    }

    private static String gift(ServerWorld world, ServerPlayerEntity player, String prayer, int power) {
        ItemWish.Wish wish = ItemWish.grant(ItemWish.find(prayer), power);
        give(world, player, new ItemStack(wish.item(), wish.count()));
        column(world, player.getBlockPos(), ParticleTypes.HAPPY_VILLAGER);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, 1f, 0.6f);
        String name = new ItemStack(wish.item()).getName().getString();
        return (wish.tooGreedy() ? "просил слишком многого, получил " : "дар: ") + name + " ×" + wish.count();
    }

    private static String oreVein(ServerWorld world, ServerPlayerEntity player, int power) {
        List<Block> ores = power >= 3 ? List.of(Blocks.DIAMOND_ORE, Blocks.GOLD_ORE, Blocks.DIAMOND_ORE)
                : List.of(Blocks.IRON_ORE, Blocks.COAL_ORE, Blocks.GOLD_ORE, Blocks.COPPER_ORE);
        BlockPos center = player.getBlockPos().down(4 + power);
        int placed = 0, wanted = 6 + power * 2;
        for (BlockPos pos : BlockPos.iterateOutwards(center, 2, 2, 2)) {
            if (placed >= wanted) break;
            BlockState state = world.getBlockState(pos);
            boolean deep = state.isIn(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
            if (!deep && !state.isIn(BlockTags.STONE_ORE_REPLACEABLES)) continue;
            Block ore = ores.get(placed % ores.size());
            world.setBlockState(pos, deep ? deepslate(ore).getDefaultState() : ore.getDefaultState());
            placed++;
        }
        if (placed == 0) return null;
        sound(world, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.7f);
        return "жила руды в " + (4 + power) + " блоках под ногами (" + placed + " шт.)";
    }
    private static Block deepslate(Block ore) {
        Map<Block, Block> map = Map.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        return map.getOrDefault(ore, ore);
    }

    private static String enchant(ServerWorld world, ServerPlayerEntity player, int power) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || !held.isEnchantable()) return null;
        ItemStack result = EnchantmentHelper.enchant(world.random, held.copy(), 10 * power, world.getRegistryManager(), Optional.empty());
        player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, result);
        sound(world, player.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
        burst(world, player.getBlockPos().up(), ParticleTypes.ENCHANT, 60);
        return "зачарован " + result.getName().getString();
    }

    private static String repair(ServerPlayerEntity player) {
        int repaired = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = player.getEquippedStack(slot);
            if (!stack.isEmpty() && stack.isDamageable() && stack.getDamage() > 0) { stack.setDamage(0); repaired++; }
        }
        if (repaired == 0) return null;
        sound(player.getServerWorld(), player.getBlockPos(), SoundEvents.BLOCK_ANVIL_USE, 1f, 1.2f);
        return "починено предметов: " + repaired;
    }

    private static String guardian(ServerWorld world, ServerPlayerEntity player, int power) {
        BlockPos at = player.getBlockPos().add(2, 0, 0);
        if (power >= 3) {
            IronGolemEntity golem = EntityType.IRON_GOLEM.create(world);
            if (golem == null) return null;
            golem.setPlayerCreated(true);
            spawnNamed(world, golem, at, "Страж бога");
            int id = golem.getId();
            Scheduler.later(20 * 60 * 5, () -> { var entity = world.getEntityById(id); if (entity != null) { burst(world, entity.getBlockPos().up(), ParticleTypes.POOF, 20); entity.discard(); } });
            return "железный страж на 5 минут";
        }
        WolfEntity wolf = EntityType.WOLF.create(world);
        if (wolf == null) return null;
        wolf.setTamed(true, true);
        wolf.setOwner(player);
        spawnNamed(world, wolf, at, "Посланник бога");
        return "ручной волк";
    }

    private static String grow(ServerWorld world, ServerPlayerEntity player, int power) {
        int grown = 0;
        for (BlockPos pos : BlockPos.iterate(player.getBlockPos().add(-7, -2, -7), player.getBlockPos().add(7, 2, 7))) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (!(block instanceof CropBlock || block instanceof SaplingBlock || block instanceof StemBlock || block instanceof SweetBerryBushBlock || block instanceof CocoaBlock)) continue;
            BlockPos fixed = pos.toImmutable();
            for (int i = 0; i < 2 * power + 1; i++) {
                BlockState current = world.getBlockState(fixed);
                if (current.getBlock() instanceof Fertilizable fertilizable && fertilizable.isFertilizable(world, fixed, current)) fertilizable.grow(world, world.random, fixed, current);
            }
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, fixed.getX() + 0.5, fixed.getY() + 0.5, fixed.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0);
            grown++;
        }
        return grown == 0 ? null : "выросло растений: " + grown;
    }

    private static String smite(ServerWorld world, ServerPlayerEntity player, int power) {
        List<HostileEntity> enemies = world.getEntitiesByClass(HostileEntity.class, new Box(player.getBlockPos()).expand(24), HostileEntity::isAlive);
        int struck = 0;
        for (HostileEntity enemy : enemies) {
            if (struck >= 12) break;
            bolt(world, enemy.getBlockPos(), true);
            enemy.damage(world.getDamageSources().lightningBolt(), power >= 3 ? 1000 : 10f * power);
            struck++;
        }
        return struck == 0 ? null : "молнии поразили врагов: " + struck;
    }

    private static String safeHome(ServerWorld world, ServerPlayerEntity player) {
        var server = world.getServer();
        BlockPos spawn = player.getSpawnPointPosition();
        ServerWorld target = spawn == null ? null : server.getWorld(player.getSpawnPointDimension());
        if (target == null) { target = server.getOverworld(); spawn = target.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, target.getSpawnPos()); }
        else spawn = spawn.up();
        player.teleport(target, spawn.getX() + 0.5, spawn.getY() + 0.1, spawn.getZ() + 0.5, player.getYaw(), player.getPitch());
        burst(target, spawn, ParticleTypes.PORTAL, 60);
        sound(target, spawn, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1f, 0.8f);
        return "перенос домой";
    }

    private static String blessing(ServerPlayerEntity player) {
        effect(player, StatusEffects.REGENERATION, 24_000, 0);
        effect(player, StatusEffects.LUCK, 24_000, 1);
        effect(player, StatusEffects.RESISTANCE, 24_000, 0);
        effect(player, StatusEffects.HERO_OF_THE_VILLAGE, 24_000, 1);
        column(player.getServerWorld(), player.getBlockPos(), ParticleTypes.END_ROD);
        return "благословение на игровой день";
    }

    // ---- Jests. ----

    private static String chickenRain(ServerWorld world, ServerPlayerEntity player, int power) {
        int count = 6 * power;
        Scheduler.repeat(3, count, i -> {
            var chicken = EntityType.CHICKEN.create(world);
            if (chicken == null) return;
            Vec3d at = player.getPos().add(world.random.nextGaussian() * 4, 14 + world.random.nextInt(6), world.random.nextGaussian() * 4);
            chicken.refreshPositionAndAngles(at.x, at.y, at.z, world.random.nextFloat() * 360, 0);
            world.spawnEntity(chicken);
        });
        return count + " куриц с неба";
    }

    private static String swap(ServerPlayerEntity player) {
        var main = player.getInventory().main;
        List<ItemStack> hotbar = new ArrayList<>();
        for (int i = 0; i < 9; i++) hotbar.add(main.get(i));
        Collections.rotate(hotbar, 3);
        Collections.swap(hotbar, 0, 5);
        for (int i = 0; i < 9; i++) main.set(i, hotbar.get(i));
        player.getInventory().markDirty();
        player.currentScreenHandler.sendContentUpdates();
        sound(player.getServerWorld(), player.getBlockPos(), SoundEvents.ENTITY_WITCH_AMBIENT, 1f, 1.4f);
        return "хотбар перемешан";
    }

    private static String transmute(ServerWorld world, ServerPlayerEntity player, int power) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty()) return null;
        Item from = held.getItem();
        Item into = from == Items.DIAMOND ? Items.GLASS : from == Items.EMERALD ? Items.LIME_DYE : from == Items.GOLD_INGOT ? Items.COPPER_INGOT
                : from == Items.IRON_INGOT ? Items.BRICK : from == Items.BREAD ? Items.WHEAT_SEEDS : from == Items.COOKED_BEEF ? Items.ROTTEN_FLESH
                : held.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? Items.STICK : held.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES) ? Items.WOODEN_PICKAXE : null;
        if (into == null) return null;
        int count = Math.min(held.getCount(), 2 * power); // A jest stings; it does not empty the vault.
        held.decrement(count);
        give(world, player, new ItemStack(into, count));
        burst(world, player.getBlockPos().up(), ParticleTypes.WITCH, 30);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1f);
        return new ItemStack(from).getName().getString() + " ×" + count + " → " + new ItemStack(into).getName().getString();
    }

    private static String randomTeleport(ServerWorld world, ServerPlayerEntity player, int power) {
        if (world.getDimension().hasCeiling()) return null;
        for (int attempt = 0; attempt < 8; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = 100 + world.random.nextInt(50 * power);
            BlockPos column = BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, 0, player.getZ() + Math.sin(angle) * distance);
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
            if (!world.getFluidState(top.down()).isEmpty() || top.getY() <= world.getBottomY() + 1) continue;
            player.teleport(world, top.getX() + 0.5, top.getY() + 0.1, top.getZ() + 0.5, player.getYaw(), player.getPitch());
            effect(player, StatusEffects.SLOW_FALLING, 200, 0);
            sound(world, top, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            return "заброшен за " + (int) distance + " блоков";
        }
        return null;
    }

    // ---- Wrath. ----

    private static String lightning(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        BlockPos at = power >= 2 ? player.getBlockPos() : player.getBlockPos().add(3 + world.random.nextInt(3), 0, 3 + world.random.nextInt(3));
        boolean spare = Protection.spares(world, player, altar, at, config);
        bolt(world, at, spare);
        if (spare && power >= 2) player.damage(world.getDamageSources().lightningBolt(), 3f * power);
        if (power >= 3) Scheduler.repeat(15, 2, i -> bolt(world, player.getBlockPos(), Protection.spares(world, player, altar, player.getBlockPos(), config)));
        return power >= 2 ? "молния в грешника" : "молния рядом";
    }

    private static String curse(ServerPlayerEntity player, int power) {
        int ticks = 20 * 60 * (power + 1);
        effect(player, StatusEffects.HUNGER, ticks, power - 1);
        effect(player, StatusEffects.WEAKNESS, ticks, 0);
        effect(player, StatusEffects.SLOWNESS, ticks, 0);
        effect(player, StatusEffects.UNLUCK, ticks, power);
        if (power >= 2) effect(player, StatusEffects.MINING_FATIGUE, ticks, 0);
        burst(player.getServerWorld(), player.getBlockPos().up(), ParticleTypes.SQUID_INK, 30);
        return "проклятие на " + ticks / 1200 + " мин";
    }

    private static String swarm(ServerWorld world, ServerPlayerEntity player, int power) {
        int spawned = 0;
        if (power == 1) for (int i = 0; i < 4; i++) spawned += hostile(world, player, EntityType.SILVERFISH.create(world), 3, 0);
        if (power >= 2) for (int i = 0; i < 5; i++) {
            BeeEntity bee = EntityType.BEE.create(world);
            if (bee == null) continue;
            bee.setAngryAt(player.getUuid()); bee.chooseRandomAngerTime();
            spawned += hostile(world, player, bee, 3, 2);
        }
        if (power >= 3) for (int i = 0; i < 3; i++) spawned += hostile(world, player, EntityType.PHANTOM.create(world), 6, 10);
        return spawned == 0 ? null : "рой: " + spawned;
    }
    private static int hostile(ServerWorld world, ServerPlayerEntity player, MobEntity mob, int spread, int above) {
        if (mob == null) return 0;
        Vec3d at = player.getPos().add(world.random.nextGaussian() * spread, above, world.random.nextGaussian() * spread);
        mob.refreshPositionAndAngles(at.x, at.y, at.z, world.random.nextFloat() * 360, 0);
        mob.setTarget(player);
        world.spawnEntity(mob);
        return 1;
    }

    private static String curseTool(ServerWorld world, ServerPlayerEntity player) {
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || !held.isDamageable()) return null;
        var enchantments = world.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        boolean wearable = held.getItem() instanceof ArmorItem;
        RegistryEntry<net.minecraft.enchantment.Enchantment> curse = enchantments.entryOf(wearable ? Enchantments.BINDING_CURSE : Enchantments.VANISHING_CURSE);
        held.addEnchantment(curse, 1);
        burst(world, player.getBlockPos().up(), ParticleTypes.SOUL, 20);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
        return "проклят " + held.getName().getString();
    }

    private static String acidRain(ServerWorld world, BlockPos altar, int power) {
        if (world.getRegistryKey() != World.OVERWORLD) return null;
        world.setWeather(0, 1_400, true, false);
        Scheduler.repeat(40, 30, i -> {
            for (ServerPlayerEntity victim : world.getPlayers(p -> p.isAlive() && p.getBlockPos().isWithinDistance(altar, 96)))
                if (world.hasRain(victim.getBlockPos().up())) effect(victim, StatusEffects.POISON, 60, power >= 3 ? 1 : 0);
        });
        return "ядовитый дождь на минуту";
    }

    private static String fireRing(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        BlockPos center = player.getBlockPos();
        int placed = 0, radius = 3 + power;
        for (int step = 0; step < 32; step++) {
            double angle = Math.PI * 2 * step / 32;
            BlockPos column = center.add((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos pos = column.up(dy);
                if (world.getBlockState(pos).isAir() && world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), net.minecraft.util.math.Direction.UP)) {
                    if (!Protection.spares(world, player, altar, pos, config)) { world.setBlockState(pos, Blocks.FIRE.getDefaultState()); placed++; }
                    else world.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 6, 0.2, 0.4, 0.2, 0.01);
                    break;
                }
            }
        }
        player.setOnFireFor(2 + power);
        sound(world, center, SoundEvents.ITEM_FIRECHARGE_USE, 1.5f, 0.6f);
        return placed > 0 ? "кольцо огня" : "кольцо призрачного огня";
    }

    // ---- Helpers. ----

    static void effect(ServerPlayerEntity player, RegistryEntry<StatusEffect> effect, int ticks, int amplifier) {
        player.addStatusEffect(new StatusEffectInstance(effect, ticks, Math.max(0, amplifier)));
    }
    static void sound(ServerWorld world, BlockPos pos, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, pos, sound, SoundCategory.AMBIENT, volume, pitch);
    }
    static void sound(ServerWorld world, BlockPos pos, RegistryEntry<SoundEvent> sound, float volume, float pitch) { sound(world, pos, sound.value(), volume, pitch); }
    static void burst(ServerWorld world, BlockPos pos, ParticleEffect particle, int count) {
        world.spawnParticles(particle, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, count, 0.6, 0.6, 0.6, 0.05);
    }
    static void column(ServerWorld world, BlockPos base, ParticleEffect particle) {
        for (int y = 1; y < 24; y++) world.spawnParticles(particle, base.getX() + 0.5, base.getY() + y, base.getZ() + 0.5, 3, 0.15, 0.3, 0.15, 0.01);
    }
    static void bolt(ServerWorld world, BlockPos pos, boolean cosmetic) {
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
        if (bolt == null) return;
        bolt.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(pos));
        bolt.setCosmetic(cosmetic);
        world.spawnEntity(bolt);
    }
    static void give(ServerWorld world, ServerPlayerEntity player, ItemStack stack) {
        player.getInventory().insertStack(stack);
        if (!stack.isEmpty()) drop(world, player.getPos().add(0, 1, 0), stack);
        player.currentScreenHandler.sendContentUpdates();
    }
    static void drop(ServerWorld world, Vec3d at, ItemStack stack) {
        ItemEntity item = new ItemEntity(world, at.x, at.y, at.z, stack);
        item.setToDefaultPickupDelay();
        world.spawnEntity(item);
    }
    private static void spawnNamed(ServerWorld world, MobEntity mob, BlockPos at, String name) {
        mob.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        mob.setCustomName(Text.literal(name));
        mob.setPersistent();
        world.spawnEntity(mob);
        burst(world, at.up(), ParticleTypes.END_ROD, 30);
        DivineBlockMod.LOG.debug("Божественный блок: {} у {}", name, at);
    }
}
