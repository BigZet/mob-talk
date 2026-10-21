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
        String done = switch (miracle) {
            case OMEN_LIGHT -> { column(world, altar, ParticleTypes.END_ROD); sound(world, altar, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.5f, 0.8f); yield "столб света"; }
            case OMEN_THUNDER -> { sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f); effect(player, StatusEffects.DARKNESS, 50, 0); yield "гром и тьма"; }
            case OMEN_LAUGH -> { sound(world, altar, SoundEvents.ENTITY_WITCH_CELEBRATE, 2f, 0.5f); burst(world, altar.up(), ParticleTypes.LARGE_SMOKE, 40); yield "хохот с неба"; }
            case IGNORE -> { burst(world, altar.up(), ParticleTypes.SMOKE, 15); sound(world, altar, SoundEvents.BLOCK_FIRE_EXTINGUISH, 1f, 0.6f); yield "бог молчит"; }
            case HEAL -> heal(player, power);
            case FEED -> feed(world, player, power);
            case WEATHER_CLEAR -> clearWeather(world, player, power);
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
            case CURSE_TOOL -> curseTool(world, player, power);
            case ACID_RAIN -> acidRain(world, player, altar, power, config);
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
            case BLIGHT -> Curses.layBlight(world, player, altar, power, config);
            case CAIN -> Curses.layCain(world, player, power, config);
            case SACRED_BOSS -> Bosses.summon(world, player, power, severity);
            case PLAGUES -> Plagues.plagues(world, player, altar, power, severity, config);
            case SODOM -> Plagues.sodom(world, player, altar, power, severity, config);
            case FLOOD -> Ultra.flood(world, player, altar, power, severity, config);
            case BABEL -> Ultra.babel(world, player, altar, power, severity, config);
            case MEMORY_WIPE -> Ultra.memoryWipe(world, player, altar, power, severity, config);
            case JUDGEMENT -> Ultra.judgement(world, player, altar, power, severity, config);
            case ETERNAL_BAN -> Banishment.banish(world, player, altar, config);
            case FLIP -> Jests.flip(world, player, power);
            case TIME_RUSH -> Jests.timeRush(world, player, power);
            case SHEEP_PARTY -> Jests.sheepParty(world, player, power);
            case FISH_RAIN -> Jests.fishRain(world, player, power);
            case CREEPER_PRANK -> Jests.creeperPrank(world, player);
            case PUMPKIN_HEAD -> Jests.pumpkinHead(world, player);
            case LEVITATE -> Jests.levitate(world, player);
            case DIZZY -> Jests.dizzy(world, player);
        };
        if (done != null) Portents.fall(world, player, altar, miracle, power);
        return done;
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
                Fx.pillar(world, player.getPos(), Fx.GOLD, 50);
                Fx.ring(world, player.getPos(), Fx.GOLD, 7);
                Fx.feathers(world, player.getPos(), power >= 3 ? 3 : 2);
            }
            case JEST -> {
                world.spawnParticles(ParticleTypes.FIREWORK, player.getX(), player.getY() + 2, player.getZ(), 80, 1.5, 1, 1.5, 0.15);
                world.spawnParticles(ParticleTypes.NOTE, altar.getX() + 0.5, altar.getY() + 1.5, altar.getZ() + 0.5, 12, 0.8, 0.5, 0.8, 1);
                sound(world, at, SoundEvents.ENTITY_EVOKER_CAST_SPELL, 1.5f, 1.3f);
                sound(world, altar, SoundEvents.ENTITY_WITCH_CELEBRATE, 2f, 0.8f);
                Fx.confetti(world, player.getPos(), power >= 2 ? 3 : 2);
            }
            case WRATH -> {
                warn(player, altar);
                bolt(world, altar.add(2, 0, 2), true);
                world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, player.getX(), player.getY() + 3, player.getZ(), 1, 0, 0, 0, 0);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 80, 2, 1, 2, 0.05);
                sound(world, at, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1.5f, 0.6f);
                Fx.shake(world, player.getPos(), 0.35f + 0.1f * power, 30);
                Fx.smoke(world, player.getPos(), 0xFF2A2224, 3);
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

    private static String clearWeather(ServerWorld world, ServerPlayerEntity player, int power) {
        if (world.getRegistryKey() != World.OVERWORLD) {
            // No sky to clear in the Nether or the End: the mercy cools the air around him instead.
            effect(player, StatusEffects.FIRE_RESISTANCE, 20 * 60 * 3 * power, 0);
            column(world, player.getBlockPos(), ParticleTypes.END_ROD);
            return "прохлада посреди чужого неба";
        }
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
        if (placed == 0) {
            // Nothing to turn into ore under his feet (sand, water, air): the vein falls from the sky as raw ore.
            Item[] raw = power >= 3 ? new Item[] { Items.DIAMOND, Items.RAW_GOLD } : new Item[] { Items.RAW_IRON, Items.COAL, Items.RAW_GOLD, Items.RAW_COPPER };
            for (int i = 0; i < wanted; i++) drop(world, player.getPos().add(world.random.nextGaussian(), 5, world.random.nextGaussian()), new ItemStack(raw[i % raw.length]));
            sound(world, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.7f);
            return "руда с неба: " + wanted + " шт.";
        }
        sound(world, player.getBlockPos(), SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 1.5f, 0.7f);
        return "жила руды в " + (4 + power) + " блоках под ногами (" + placed + " шт.)";
    }
    private static Block deepslate(Block ore) {
        Map<Block, Block> map = Map.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
                Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE);
        return map.getOrDefault(ore, ore);
    }

    private static String enchant(ServerWorld world, ServerPlayerEntity player, int power) {
        // The thing in hand, else the first enchantable thing he wears or carries, else a book.
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || !held.isEnchantable()) held = firstStack(player, ItemStack::isEnchantable);
        if (held == null) {
            ItemStack book = EnchantmentHelper.enchant(world.random, new ItemStack(Items.BOOK), 10 * power, world.getRegistryManager(), Optional.empty());
            give(world, player, book);
            sound(world, player.getBlockPos(), SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f);
            return "зачарованная книга";
        }
        ItemStack result = EnchantmentHelper.enchant(world.random, held.copy(), 10 * power, world.getRegistryManager(), Optional.empty());
        replace(player, held, result);
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
        for (ItemStack stack : player.getInventory().main)
            if (!stack.isEmpty() && stack.isDamageable() && stack.getDamage() > 0) { stack.setDamage(0); repaired++; }
        if (repaired == 0) {
            // Nothing is worn out: the mercy comes as experience instead.
            give(player.getServerWorld(), player, new ItemStack(Items.EXPERIENCE_BOTTLE, 8));
            sound(player.getServerWorld(), player.getBlockPos(), SoundEvents.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            return "чинить нечего — 8 пузырьков опыта";
        }
        player.currentScreenHandler.sendContentUpdates();
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
        if (grown > 0) return "выросло растений: " + grown;
        // No crops around: the grass around him bursts into flowers, and seeds and bone meal fall into his hands.
        int bloomed = 0;
        for (BlockPos pos : BlockPos.iterate(player.getBlockPos().add(-4, -2, -4), player.getBlockPos().add(4, 1, 4))) {
            BlockState state = world.getBlockState(pos);
            if (bloomed < 3 && state.isOf(Blocks.GRASS_BLOCK) && world.getBlockState(pos.up()).isAir()) {
                ((Fertilizable) state.getBlock()).grow(world, world.random, pos.toImmutable(), state);
                bloomed++;
            }
        }
        give(world, player, new ItemStack(Items.BONE_MEAL, 8 * power));
        give(world, player, new ItemStack(Items.WHEAT_SEEDS, 8));
        burst(world, player.getBlockPos(), ParticleTypes.HAPPY_VILLAGER, 40);
        return "сеять нечего — цветы вокруг, семена и костная мука";
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
        if (struck > 0) return "молнии поразили врагов: " + struck;
        // No enemy in sight: a ring of harmless lightning and the god's shield for the fights to come.
        int[][] ring = { { 6, 0 }, { -6, 0 }, { 0, 6 }, { 0, -6 } };
        for (int[] offset : ring) bolt(world, player.getBlockPos().add(offset[0], 0, offset[1]), true);
        effect(player, StatusEffects.RESISTANCE, 20 * 60 * 3, 0);
        effect(player, StatusEffects.STRENGTH, 20 * 60 * 3, 0);
        return "врагов нет — кольцо молний и щит на 3 мин";
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
        // The thing in hand if it has a "likeness", else the first such thing in his bags, else whatever he holds turns to dust.
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || likeness(held) == null) {
            ItemStack valuable = firstStack(player, stack -> likeness(stack) != null);
            if (valuable != null) held = valuable;
        }
        if (held.isEmpty()) held = firstStack(player, stack -> true);
        if (held == null || held.isEmpty()) return null;
        Item from = held.getItem();
        Item into = likeness(held) != null ? likeness(held) : Items.DEAD_BUSH;
        int count = Math.min(held.getCount(), 2 * power); // A jest stings; it does not empty the vault.
        held.decrement(count);
        give(world, player, new ItemStack(into, count));
        burst(world, player.getBlockPos().up(), ParticleTypes.WITCH, 30);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1f);
        return new ItemStack(from).getName().getString() + " ×" + count + " → " + new ItemStack(into).getName().getString();
    }

    /** What a treasure turns into under the god's jest; null for things without a "likeness". */
    private static Item likeness(ItemStack stack) {
        Item from = stack.getItem();
        return from == Items.DIAMOND ? Items.GLASS : from == Items.EMERALD ? Items.LIME_DYE : from == Items.GOLD_INGOT ? Items.COPPER_INGOT
                : from == Items.IRON_INGOT ? Items.BRICK : from == Items.BREAD ? Items.WHEAT_SEEDS : from == Items.COOKED_BEEF ? Items.ROTTEN_FLESH
                : from == Items.NETHERITE_INGOT ? Items.COAL : from == Items.DIAMOND_BLOCK ? Items.GLASS : from == Items.GOLD_NUGGET ? Items.IRON_NUGGET
                : stack.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) ? Items.STICK : stack.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES) ? Items.WOODEN_PICKAXE
                : stack.isIn(net.minecraft.registry.tag.ItemTags.AXES) ? Items.WOODEN_AXE : null;
    }

    private static String randomTeleport(ServerWorld world, ServerPlayerEntity player, int power) {
        if (world.getDimension().hasCeiling()) return caveTeleport(world, player, power);
        for (int attempt = 0; attempt < 16; attempt++) {
            // Far tries first; if the land out there is all water, nearer ones.
            double angle = world.random.nextDouble() * Math.PI * 2, distance = attempt < 10 ? 100 + world.random.nextInt(50 * power) : 30 + world.random.nextInt(40);
            BlockPos column = BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, 0, player.getZ() + Math.sin(angle) * distance);
            // The heightmap of a chunk nobody has loaded reads as the bottom of the world: load it first.
            world.getChunk(column.getX() >> 4, column.getZ() >> 4);
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
            if (!world.getFluidState(top.down()).isEmpty() || top.getY() <= world.getBottomY() + 1) continue;
            player.teleport(world, top.getX() + 0.5, top.getY() + 0.1, top.getZ() + 0.5, player.getYaw(), player.getPitch());
            effect(player, StatusEffects.SLOW_FALLING, 200, 0);
            sound(world, top, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
            return "заброшен за " + (int) distance + " блоков";
        }
        return null;
    }

    /** Under a roof of rock (the Nether): a safe pocket of air 50–100 blocks away, searched from the roof down. */
    private static String caveTeleport(ServerWorld world, ServerPlayerEntity player, int power) {
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = attempt < 20 ? 50 + world.random.nextInt(25 * power) : 12 + world.random.nextInt(30);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance), z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            world.getChunk(x >> 4, z >> 4);
            for (int y = world.getBottomY() + world.getLogicalHeight() - 10; y > world.getBottomY() + 8; y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir() && world.getBlockState(feet.down()).isSolidBlock(world, feet.down())
                        && world.getFluidState(feet.down()).isEmpty()) {
                    player.teleport(world, x + 0.5, y + 0.1, z + 0.5, player.getYaw(), player.getPitch());
                    sound(world, feet, SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1f, 0.6f);
                    return "заброшен за " + (int) distance + " блоков";
                }
            }
        }
        return null;
    }

    // ---- Wrath. ----

    /** The bolt into the sinner, then his own storm cloud for half a minute: thunder, near misses ever closer, the last bolt
     * into him again. On power 3 the near misses set the ground alight. */
    private static String lightning(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        // Always into the sinner himself. Where the land is protected (at the altar, by the bed) the bolt sets nothing alight,
        // but the blow and the fire on him are the same.
        strike(world, player, altar, power, config);
        if (power >= 3) Scheduler.repeat(15, 2, i -> { if (!gone(world, player)) strike(world, player, altar, power, config); });
        int steps = 60; // Every half second for 30 s.
        Scheduler.repeat(10, steps, i -> {
            if (gone(world, player)) return;
            world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 4.5, player.getZ(), 14, 1.3, 0.25, 1.3, 0.01);
            world.spawnParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 4.8, player.getZ(), 5, 1.1, 0.2, 1.1, 0.005);
            world.spawnParticles(ParticleTypes.FALLING_WATER, player.getX(), player.getY() + 4, player.getZ(), 6, 1, 0.1, 1, 0);
            if (i == steps - 1) {
                sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.5f);
                strike(world, player, altar, power, config);
            } else if (i % 8 == 4) nearMiss(world, player, altar, Math.max(2, 7 - i / 8), power, config);
        });
        return (power >= 3 ? "три молнии в грешника" : "молния в грешника") + " и грозовая туча над ним на полминуты";
    }
    private static void strike(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        BlockPos at = player.getBlockPos();
        boolean spare = Protection.spares(world, player, altar, at, config);
        bolt(world, at, spare);
        if (spare) { player.damage(world.getDamageSources().lightningBolt(), 3f + 2f * power); player.setOnFireFor(4); }
    }
    /** A harmless bolt {@code distance} blocks from him; on power 3 the ground where it lands catches fire. */
    private static void nearMiss(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int distance, int power, DivineConfig config) {
        double angle = world.random.nextDouble() * Math.PI * 2;
        BlockPos column = player.getBlockPos().add((int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance));
        BlockPos ground = surface(world, column);
        bolt(world, ground == null ? column : ground, true);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 0.7f + world.random.nextFloat() * 0.3f);
        if (power < 3 || ground == null) return;
        for (BlockPos pos : List.of(ground, ground.east(), ground.west(), ground.north(), ground.south())) {
            BlockPos spot = surface(world, pos);
            if (spot != null && !Protection.spares(world, player, altar, spot, config)) world.setBlockState(spot, Blocks.FIRE.getDefaultState());
        }
    }
    /** The air block on solid ground in this column, two blocks up or down from it; null when there is none. */
    private static BlockPos surface(ServerWorld world, BlockPos column) {
        for (int dy = 2; dy >= -2; dy--) {
            BlockPos pos = column.up(dy);
            if (world.getBlockState(pos).isAir() && world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), net.minecraft.util.math.Direction.UP)) return pos;
        }
        return null;
    }

    /** Hunger, weakness and bad luck; his cooked food rots, and now and then the thing in his hand slips from it. */
    private static String curse(ServerPlayerEntity player, int power) {
        ServerWorld world = player.getServerWorld();
        int ticks = 20 * 60 * (power + 1);
        effect(player, StatusEffects.HUNGER, ticks, power - 1);
        effect(player, StatusEffects.WEAKNESS, ticks, 0);
        effect(player, StatusEffects.SLOWNESS, ticks, 0);
        effect(player, StatusEffects.UNLUCK, ticks, power);
        if (power >= 2) effect(player, StatusEffects.MINING_FATIGUE, ticks, 0);
        burst(world, player.getBlockPos().up(), ParticleTypes.SQUID_INK, 30);
        int rotten = rot(player, power);
        if (rotten > 0) sound(world, player.getBlockPos(), SoundEvents.ENTITY_ZOMBIE_AMBIENT, 1f, 0.6f);
        // Butterfingers: a few slips spread over the curse, not one after another. Milk lifts the curse and the slips with it.
        int slips = 1 + power;
        for (int k = 0; k < slips; k++) {
            int due = ticks * (k + 1) / (slips + 1) + world.random.nextInt(200) - 100;
            Scheduler.later(due, () -> {
                if (gone(world, player) || !player.hasStatusEffect(StatusEffects.UNLUCK) || player.getMainHandStack().isEmpty()) return;
                if (player.dropSelectedItem(true)) {
                    sound(world, player.getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, 1f, 0.4f);
                    burst(world, player.getBlockPos().up(), ParticleTypes.SQUID_INK, 8);
                    Prayers.hint(player, "Проклятие: предмет выскользнул из руки");
                }
            });
        }
        return "проклятие на " + ticks / 1200 + " мин" + (rotten > 0 ? ", сгнило еды: " + rotten + " стоп." : "") + ", руки не держат";
    }
    /** Turns up to {@code stacks} stacks of cooked food in his bags into rotten flesh; returns how many rotted. */
    private static int rot(ServerPlayerEntity player, int stacks) {
        var inventory = player.getInventory();
        int rotted = 0;
        for (var list : List.of(inventory.main, inventory.offHand))
            for (int i = 0; i < list.size() && rotted < stacks; i++) {
                ItemStack stack = list.get(i);
                if (stack.isEmpty() || !COOKED.contains(stack.getItem())) continue;
                list.set(i, new ItemStack(Items.ROTTEN_FLESH, stack.getCount()));
                rotted++;
            }
        if (rotted > 0) { inventory.markDirty(); player.currentScreenHandler.sendContentUpdates(); }
        return rotted;
    }
    private static final java.util.Set<Item> COOKED = java.util.Set.of(Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.BREAD, Items.BAKED_POTATO,
            Items.PUMPKIN_PIE, Items.COOKIE, Items.RABBIT_STEW, Items.MUSHROOM_STEW, Items.BEETROOT_SOUP);

    /** The swarm comes in waves nine seconds apart, each larger than the last; on power 3 the last brings phantoms that
     * do not burn by day. */
    private static String swarm(ServerWorld world, ServerPlayerEntity player, int power) {
        int waves = power == 1 ? 2 : 3;
        int first = wave(world, player, power, 0, waves);
        if (first == 0) return null;
        for (int w = 1; w < waves; w++) {
            int index = w;
            Scheduler.later(20 * 9 * w, () -> { if (!gone(world, player)) wave(world, player, power, index, waves); });
        }
        return "рой: " + waves + " волны, первая — " + first;
    }
    private static int wave(ServerWorld world, ServerPlayerEntity player, int power, int index, int waves) {
        int spawned = 0, size = 3 + index * 2 + (power - 1);
        boolean last = index == waves - 1;
        if (power == 1) {
            for (int i = 0; i < size; i++) spawned += hostile(world, player, EntityType.SILVERFISH.create(world), 3, 0);
            if (last) for (int i = 0; i < 2; i++) spawned += hostile(world, player, EntityType.ENDERMITE.create(world), 3, 0);
            sound(world, player.getBlockPos(), SoundEvents.ENTITY_SILVERFISH_AMBIENT, 1.5f, 0.7f);
        } else {
            for (int i = 0; i < size; i++) {
                BeeEntity bee = EntityType.BEE.create(world);
                if (bee == null) continue;
                bee.setAngryAt(player.getUuid()); bee.chooseRandomAngerTime();
                spawned += hostile(world, player, bee, 3, 2);
            }
            if (index >= 1) for (int i = 0; i < index + 1; i++) spawned += hostile(world, player, EntityType.CAVE_SPIDER.create(world), 4, 0);
            sound(world, player.getBlockPos(), SoundEvents.ENTITY_BEE_LOOP_AGGRESSIVE, 2f, 0.8f);
        }
        if (power >= 3 && last) {
            for (int i = 0; i < 3; i++) {
                MobEntity phantom = EntityType.PHANTOM.create(world);
                if (phantom == null) continue;
                phantom.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 20 * 60 * 3, 0));
                spawned += hostile(world, player, phantom, 6, 10);
            }
            sound(world, player.getBlockPos(), SoundEvents.ENTITY_PHANTOM_SWOOP, 2f, 0.6f);
        }
        burst(world, player.getBlockPos().up(), ParticleTypes.ANGRY_VILLAGER, 6);
        return spawned;
    }
    private static int hostile(ServerWorld world, ServerPlayerEntity player, MobEntity mob, int spread, int above) {
        if (mob == null) return 0;
        Vec3d at = player.getPos().add(world.random.nextGaussian() * spread, above, world.random.nextGaussian() * spread);
        mob.refreshPositionAndAngles(at.x, at.y, at.z, world.random.nextFloat() * 360, 0);
        mob.setTarget(player);
        world.spawnEntity(mob);
        return 1;
    }

    /** Curses the tool (vanishing) or armour (binding) and eats half of what is left of it; on power 2 a worn armour piece
     * shares its fate. */
    private static String curseTool(ServerWorld world, ServerPlayerEntity player, int power) {
        // The tool in hand, else the first tool or armour he carries; with nothing to curse, the curse falls on him.
        ItemStack held = player.getMainHandStack();
        if (held.isEmpty() || !held.isDamageable()) held = firstStack(player, ItemStack::isDamageable);
        if (held == null) return curse(player, 1) + " (проклинать было нечего)";
        String result = "проклят " + blight(world, held);
        if (power >= 2) {
            ItemStack first = held;
            ItemStack worn = null;
            for (EquipmentSlot slot : new EquipmentSlot[] { EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET }) {
                ItemStack stack = player.getEquippedStack(slot);
                if (!stack.isEmpty() && stack != first && stack.isDamageable()) { worn = stack; break; }
            }
            if (worn != null) result += " и " + blight(world, worn);
        }
        player.currentScreenHandler.sendContentUpdates();
        burst(world, player.getBlockPos().up(), ParticleTypes.SOUL, 20);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
        sound(world, player.getBlockPos(), SoundEvents.ENTITY_ITEM_BREAK, 1f, 0.6f);
        return result;
    }
    /** Lays the curse on one item and takes half its remaining durability; returns its name and the loss. */
    private static String blight(ServerWorld world, ItemStack stack) {
        var enchantments = world.getRegistryManager().get(RegistryKeys.ENCHANTMENT);
        boolean wearable = stack.getItem() instanceof ArmorItem;
        RegistryEntry<net.minecraft.enchantment.Enchantment> curse = enchantments.entryOf(wearable ? Enchantments.BINDING_CURSE : Enchantments.VANISHING_CURSE);
        stack.addEnchantment(curse, 1);
        int left = stack.getMaxDamage() - stack.getDamage(), eaten = left / 2;
        if (eaten > 0) stack.setDamage(stack.getDamage() + eaten);
        return stack.getName().getString() + (eaten > 0 ? " (−" + eaten + " прочности)" : "");
    }

    /** Poisonous rain for a minute; where it falls near the sinner the grass burns to coarse dirt, flowers to dead bushes
     * and crops back to seedlings. */
    private static String acidRain(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        if (world.getRegistryKey() != World.OVERWORLD) {
            // No rain under a stone sky: a poisonous cloud settles round him instead.
            for (ServerPlayerEntity victim : world.getPlayers(p -> p.isAlive() && p.getBlockPos().isWithinDistance(altar, 96))) {
                var cloud = new net.minecraft.entity.AreaEffectCloudEntity(world, victim.getX(), victim.getY(), victim.getZ());
                cloud.setRadius(4f); cloud.setDuration(20 * 20); cloud.setRadiusGrowth(-0.005f);
                cloud.addEffect(new StatusEffectInstance(StatusEffects.POISON, 100, power >= 3 ? 1 : 0));
                world.spawnEntity(cloud);
            }
            return "ядовитое облако";
        }
        world.setWeather(0, 1_400, true, false);
        Scheduler.repeat(40, 30, i -> {
            for (ServerPlayerEntity victim : world.getPlayers(p -> p.isAlive() && p.getBlockPos().isWithinDistance(altar, 96)))
                if (world.hasRain(victim.getBlockPos().up())) effect(victim, StatusEffects.POISON, 60, power >= 3 ? 1 : 0);
            if (!gone(world, player)) scorch(world, player, altar, power, config);
        });
        return config.protectBlocks ? "ядовитый дождь на минуту" : "ядовитый дождь на минуту, земля под ним выгорает";
    }
    /** A few scorched patches under the rain round the sinner; never more than a handful of blocks a step. */
    private static void scorch(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        int radius = 4 + power, burnt = 0;
        for (int patch = 0; patch < 2 + power; patch++) {
            BlockPos center = player.getBlockPos().add(world.random.nextInt(radius * 2 + 1) - radius, 0, world.random.nextInt(radius * 2 + 1) - radius);
            for (BlockPos column : List.of(center, center.add(world.random.nextInt(3) - 1, 0, world.random.nextInt(3) - 1))) {
                BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
                if (Math.abs(top.getY() - player.getBlockY()) > 6 || !world.hasRain(top)) continue; // Under a roof the rain does not reach.
                if (burn(world, player, altar, top, config) | burn(world, player, altar, top.down(), config)) burnt++;
            }
        }
        if (burnt > 0) sound(world, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.4f);
    }
    /** What the acid does to one block; false when it leaves it be. */
    private static boolean burn(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos pos, DivineConfig config) {
        if (Protection.spares(world, player, altar, pos, config)) return false;
        BlockState state = world.getBlockState(pos);
        BlockState into = null;
        if (state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM)) into = Blocks.COARSE_DIRT.getDefaultState();
        else if (state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.FERN) || state.isIn(BlockTags.SMALL_FLOWERS)) into = Blocks.DEAD_BUSH.getDefaultState();
        else if (state.getBlock() instanceof CropBlock crop && crop.getAge(state) > 0) into = crop.withAge(0);
        if (into == null) return false;
        world.setBlockState(pos, into);
        world.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.1, pos.getZ() + 0.5, 5, 0.3, 0.1, 0.3, 0.01);
        return true;
    }

    /** A ring of fire round the sinner that closes in: one block nearer every two seconds, down to one block from him. */
    private static String fireRing(ServerWorld world, ServerPlayerEntity player, BlockPos altar, int power, DivineConfig config) {
        BlockPos center = player.getBlockPos();
        int start = 3 + power;
        int placed = ring(world, player, altar, center, start, config);
        for (int radius = start - 1; radius >= 1; radius--) {
            int r = radius;
            Scheduler.later(40 * (start - radius), () -> {
                if (gone(world, player)) return;
                ring(world, player, altar, center, r, config);
                if (r == 1) player.setOnFireFor(3 + power);
            });
        }
        player.setOnFireFor(2 + power);
        return (placed > 0 ? "кольцо огня" : "кольцо призрачного огня") + " сжимается от " + start + " блоков к грешнику";
    }
    /** One ring of fire of this radius; where the land is spared only flames in the air. Returns the fires laid. */
    private static int ring(ServerWorld world, ServerPlayerEntity player, BlockPos altar, BlockPos center, int radius, DivineConfig config) {
        int placed = 0, points = Math.max(8, Math.min(32, radius * 7));
        for (int step = 0; step < points; step++) {
            double angle = Math.PI * 2 * step / points;
            BlockPos pos = surface(world, center.add((int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius)));
            if (pos == null) continue;
            if (!Protection.spares(world, player, altar, pos, config)) { world.setBlockState(pos, Blocks.FIRE.getDefaultState()); placed++; }
            else world.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 6, 0.2, 0.4, 0.2, 0.01);
        }
        sound(world, center, SoundEvents.ITEM_FIRECHARGE_USE, 1.5f, 0.6f + 0.1f * (6 - Math.min(6, radius)));
        return placed;
    }

    /** True when a delayed step must stop: he died, left, or went to another world. */
    private static boolean gone(ServerWorld world, ServerPlayerEntity player) {
        return player.isRemoved() || !player.isAlive() || player.getServerWorld() != world;
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
        Fx.bolt(world, Vec3d.ofBottomCenter(pos), 0xFFB8D4FF);
        bolt.refreshPositionAfterTeleport(Vec3d.ofBottomCenter(pos));
        bolt.setCosmetic(cosmetic);
        world.spawnEntity(bolt);
    }
    /** The first stack in his hands, armour and bags that matches; null when none does. */
    static ItemStack firstStack(ServerPlayerEntity player, java.util.function.Predicate<ItemStack> wanted) {
        for (EquipmentSlot slot : EquipmentSlot.values()) { ItemStack stack = player.getEquippedStack(slot); if (!stack.isEmpty() && wanted.test(stack)) return stack; }
        for (ItemStack stack : player.getInventory().main) if (!stack.isEmpty() && wanted.test(stack)) return stack;
        return null;
    }
    /** Puts {@code result} where {@code old} lay (a hand, an armour slot or the bags). */
    static void replace(ServerPlayerEntity player, ItemStack old, ItemStack result) {
        for (EquipmentSlot slot : EquipmentSlot.values()) if (player.getEquippedStack(slot) == old) { player.equipStack(slot, result); return; }
        var main = player.getInventory().main;
        for (int i = 0; i < main.size(); i++) if (main.get(i) == old) { main.set(i, result); player.currentScreenHandler.sendContentUpdates(); return; }
        give(player.getServerWorld(), player, result);
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
