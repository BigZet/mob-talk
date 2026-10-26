package io.github.mobtalk.game;

import io.github.mobtalk.core.*;
import net.minecraft.entity.*;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.WardenEntity;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.entity.passive.*;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.LightType;
import net.minecraft.world.RaycastContext;
import java.util.*;

/** Bounded, loaded-only and perceptible facts captured exclusively on the server thread. */
final class MobContextCapture {
    static MobSnapshot capture(MobGameBridge bridge, ServerWorld world, MobEntity mob, ServerPlayerEntity player) {
        boolean blind = mob instanceof WardenEntity;
        String biome = blind ? "unknown (blind)" : world.getBiome(mob.getBlockPos()).getKey()
                .map(key -> key.getValue().toString()).orElse("unknown");
        long day = Math.floorMod(world.getTimeOfDay(), 24_000);
        String time = blind ? "unknown (blind)" : day < 1000 ? "dawn" : day < 12000 ? "day" : day < 13000 ? "dusk" : "night";
        String weather = blind ? "unknown (blind)" : world.isThundering() ? "thunderstorm" : world.isRaining() ? "rain" : "clear";
        Map<String, Object> mobDetails = new LinkedHashMap<>(living(mob, !blind));
        mobDetails.put("baby", mob.isBaby());
        mobDetails.put("position", position(mob.getBlockPos()));
        if (mob.hasCustomName()) mobDetails.put("customName", mob.getCustomName().getString());
        mobDetails.put("aiDisabled", mob.isAiDisabled());
        mobDetails.put("canAttackPlayer", bridge.actions.canAttack(mob, player));
        mobDetails.put("fighter", MobActionController.fighter(mob));
        mobDetails.put("attackDamage", MobActionController.attackDamage(mob));
        mobDetails.put("difficulty", world.getDifficulty().getName());
        mobDetails.put("riding", mob.hasVehicle());
        mobDetails.put("leashed", mob.isLeashed());
        mobDetails.put("currentTarget", mob.getTarget() == null ? "none" : mob.getTarget().getUuid().toString());
        mobDetails.put("lastAttacker", mob.getAttacker() == null ? "none" : mob.getAttacker().getUuid().toString());
        if (mob instanceof Angerable angry) {
            mobDetails.put("angerTicks", angry.getAngerTime());
            mobDetails.put("angryAt", angry.getAngryAt() == null ? "none" : angry.getAngryAt().toString());
        }
        mob.getBrain().getFirstPossibleNonCoreActivity().ifPresent(activity -> mobDetails.put("activity", activity.getId()));
        ActionIntent active = bridge.memory.action(mob.getUuid());
        mobDetails.put("activeAction", Map.of("type", active.type().name(), "target", active.target()));
        mobDetails.put("truce", MobGameBridge.truce(mob, player));
        List<String> sparing = bridge.actions.friends.sparing(mob);
        if (!sparing.isEmpty()) mobDetails.put("sparing", sparing);
        MobCard card = bridge.memory.card(mob.getUuid(), player.getUuid());
        String type = MobGameBridge.type(mob);
        // A merchant's goods are its vanilla offers (same window on right-click and on B); the catalog deals would be a second,
        // contradicting price list. Its favours stay.
        Map<String, Object> trading = new LinkedHashMap<>(Trading.view(type, bridge.memory.tradeStates(mob.getUuid(), player.getUuid()),
                bridge.memory.favor(mob.getUuid(), player.getUuid()), bridge.memory.favorsDone(mob.getUuid(), player.getUuid()),
                card == null ? 0 : card.relationship(), System.currentTimeMillis(),
                Math.floorDiv(world.getTimeOfDay(), 24_000L), MobTrading.inventoryCounts(player), MobTrading.itemNames(type), !(mob instanceof MerchantEntity),
                bridge.memory.neighbourFavors(mob.getUuid(), player.getUuid())));
        if (!trading.isEmpty()) {
            // An empty view stays empty: the LLM client reads "trades nothing" from it.
            MobMerchant window = MobMerchant.of(player, mob);
            trading.put("windowOpen", window != null);
            trading.put("selectedDeal", window == null ? "" : window.selectedDeal());
        }
        mobDetails.put("trading", Map.copyOf(trading));
        if (mob instanceof TameableEntity tameable) {
            mobDetails.put("tamed", tameable.isTamed());
            mobDetails.put("sitting", tameable.isSitting());
            mobDetails.put("owner", tameable.getOwnerUuid() == null ? "none" : tameable.getOwnerUuid().toString());
        }
        if (mob instanceof AbstractHorseEntity horse) {
            mobDetails.put("tamed", horse.isTame());
            mobDetails.put("owner", horse.getOwnerUuid() == null ? "none" : horse.getOwnerUuid().toString());
        }
        if (mob instanceof AnimalEntity animal) mobDetails.put("inLove", animal.isInLove());
        if (mob instanceof PassiveEntity passive) mobDetails.put("breedingAge", passive.getBreedingAge());
        // The shape of the place: the flood over the blocks around it says whether there is a way out at all.
        PlaceCapture.Result place = blind || !bridge.config.placeAnalysis ? null : PlaceCapture.capture(world, mob, player, bridge.changes);
        if (place != null) PlaceCapture.stash(mob.getUuid(), place.grid());
        if (mob instanceof net.minecraft.entity.mob.PathAwareEntity pathAware) {
            mobDetails.put("panicking", pathAware.isPanicking());
            mobDetails.put("trapped", place != null && place.report().trapped || trapped(world, pathAware));
        }
        if (mob instanceof VillagerEntity villager) {
            mobDetails.put("profession", Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).toString());
            mobDetails.put("level", villager.getVillagerData().getLevel());
            mobDetails.put("reputation", villager.getReputation(player));
            rememberPlace(mobDetails, "jobSite", villager, MemoryModuleType.JOB_SITE);
            rememberPlace(mobDetails, "home", villager, MemoryModuleType.HOME);
            rememberPlace(mobDetails, "meetingPoint", villager, MemoryModuleType.MEETING_POINT);
            mobDetails.put("bargaining", bridge.bargaining.capture(villager, player));
            Map<String, Object> fine = bridge.officials.fineFor(villager, player);
            if (fine != null) mobDetails.put("officialFine", fine);
        }
        if (mob instanceof net.minecraft.entity.mob.ZombieVillagerEntity villager) {
            mobDetails.put("profession", Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).toString());
            mobDetails.put("level", villager.getVillagerData().getLevel());
            mobDetails.put("beingCured", villager.isConverting());
        }
        if (mob instanceof MerchantEntity merchant) {
            mobDetails.put("offers", merchant.getOffers().stream().limit(12).map(offer -> Map.<String, Object>of(
                    "buy", item(offer.getDisplayedFirstBuyItem()), "secondBuy", item(offer.getDisplayedSecondBuyItem()),
                    "sell", item(offer.getSellItem()), "uses", offer.getUses(), "maxUses", offer.getMaxUses(),
                    "available", !offer.isDisabled())).toList());
            mobDetails.put("tradingWithPlayer", merchant.getCustomer() == player);
        }
        // The village structure this villager or golem lives in, for the world memory only (MobPrompt leaves it out of the prompt).
        if (bridge.config.villageSites && (mob instanceof VillagerEntity || mob instanceof net.minecraft.entity.passive.IronGolemEntity)) {
            io.github.mobtalk.core.ConversationStore.Site site = VillageSites.of(world, mob);
            if (site != null) mobDetails.put("villageSite", VillageSites.details(site));
        }
        speciesState(mob, mobDetails);
        if (mob instanceof VariantHolder<?> variant) {
            Object value = variant.getVariant();
            mobDetails.put("variant", value instanceof RegistryEntry<?> entry
                    ? entry.getKey().map(key -> key.getValue().toString()).orElse("unknown") : String.valueOf(value));
        }

        Map<String, Object> playerDetails = new LinkedHashMap<>();
        if (!blind) {
            playerDetails.putAll(living(player, true));
            playerDetails.put("name", MobGameBridge.playerName(player));
            playerDetails.put("hunger", player.getHungerManager().getFoodLevel());
            playerDetails.put("saturation", player.getHungerManager().getSaturationLevel());
            playerDetails.put("creative", player.isCreative());
            playerDetails.put("spectator", player.isSpectator());
            playerDetails.put("sneaking", player.isSneaking());
            playerDetails.put("sprinting", player.isSprinting());
            playerDetails.put("experienceLevel", player.experienceLevel);
            playerDetails.put("positionRelativeToMob", vector(player.getPos().subtract(mob.getPos())));
            playerDetails.put("directionFromMob", direction(mob.getPos(), player.getPos()));
            playerDetails.put("lookVector", vector(player.getRotationVector()));
            playerDetails.put("lookDirection", direction(Vec3d.ZERO, player.getRotationVector().multiply(10)));
            playerDetails.put("lookingAtMob", player.getRotationVector().dotProduct(
                    mob.getEyePos().subtract(player.getEyePos()).normalize()) > 0.95);
            playerDetails.put("entityUnderReticle", reticleEntity(world, mob, player));
        } else playerDetails.put("visualState", "unknown: this mob is blind");

        List<ObservedEntity> observed = new ArrayList<>();
        List<String> nearby = new ArrayList<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        if (blind) {
            List<Map<String, Object>> sounds = new ArrayList<>();
            Set<UUID> included = new HashSet<>();
            for (MobGameBridge.Heard event : bridge.heard(mob)) {
                sounds.add(Map.of("event", event.event(), "distance", round(mob.getPos().distanceTo(event.position())),
                        "direction", direction(mob.getPos(), event.position())));
                Entity source = event.source() == null ? null : world.getEntity(event.source());
                if (source instanceof LivingEntity && source != mob && source != player && source.isAlive()
                        && included.add(source.getUuid()) && observed.size() < 16) {
                    observed.add(new ObservedEntity("e" + (observed.size() + 1), source.getUuid(), "unknown (heard)",
                            "Источник звука", round(mob.getPos().distanceTo(event.position())), direction(mob.getPos(), event.position()),
                            false, Map.of("audible", true, "sound", event.event())));
                }
            }
            environment.put("perception", "blind; only own state, attributed interactions and recent sounds are known");
            environment.put("recentSounds", List.copyOf(sounds));
        } else {
            List<LivingEntity> entities = world.getEntitiesByClass(LivingEntity.class, mob.getBoundingBox().expand(24),
                    entity -> entity != mob && entity != player && entity.isAlive() && !entity.isRemoved()
                            && entity.squaredDistanceTo(mob) <= 24 * 24 && mob.canSee(entity));
            entities.sort(Comparator.comparingDouble(mob::squaredDistanceTo));
            for (LivingEntity entity : entities.stream().limit(16).toList()) {
                String key = "e" + (observed.size() + 1);
                boolean caged = entity.getCommandTags().contains(ParrotJailNews.CAGED_TAG);
                observed.add(new ObservedEntity(key, entity.getUuid(), MobGameBridge.type(entity), entity.getName().getString(),
                        round(mob.distanceTo(entity)), direction(mob.getPos(), entity.getPos()), true, caged ? caged(entity, bridge) : living(entity, true)));
                nearby.add(key + ": " + MobGameBridge.type(entity) + " " + entity.getName().getString() + (caged ? " (в клетке)" : ""));
            }
            environment.put("light", Map.of("block", world.getLightLevel(LightType.BLOCK, mob.getBlockPos()),
                    "sky", world.getLightLevel(LightType.SKY, mob.getBlockPos())));
            environment.put("rainingHere", world.hasRain(mob.getBlockPos()));
            environment.put("timeOfDayTicks", day);
            environment.put("blockAtMob", Registries.BLOCK.getId(world.getBlockState(mob.getBlockPos()).getBlock()).toString());
            environment.put("blockBelowMob", Registries.BLOCK.getId(world.getBlockState(mob.getBlockPos().down()).getBlock()).toString());
            // Grouped objects (hazards, doors, water, walls) replace the raw feature list; plants and workstations keep their
            // own short lists, since villagers and animals are asked about them.
            List<Map<String, Object>> features = features(world, mob);
            if (place != null) environment.put("place", PlaceCapture.details(place));
            environment.put("vegetation", features.stream().filter(feature -> "vegetation".equals(feature.get("kind"))).toList());
            environment.put("workBlocks", features.stream().filter(feature -> "workstation".equals(feature.get("kind"))).toList());
            environment.put("droppedItems", droppedItems(world, mob));
            environment.put("shelter", shelter(world, mob));
        }
        environment.put("dimension", world.getRegistryKey().getValue().toString());
        String wedding = bridge.wedding.contextFor(world, mob);
        if (!wedding.isEmpty()) environment.put("wedding", wedding);
        return new MobSnapshot(mob.getUuid(), MobGameBridge.type(mob), mob.getName().getString(), mob.getHealth(), mob.getMaxHealth(),
                world.getRegistryKey().getValue().toString(), biome, time, weather, blind ? -1 : round(mob.distanceTo(player)),
                blind ? "unknown (blind)" : Registries.ITEM.getId(player.getMainHandStack().getItem()).toString(), List.copyOf(nearby),
                bridge.memory.events(mob.getUuid()), player.getUuid(), Map.copyOf(playerDetails), Map.copyOf(mobDetails),
                Map.copyOf(environment), List.copyOf(observed), bridge.actions.available(mob, player), bridge.memory.revision(mob.getUuid()));
    }

    /** A walking mob that cannot path 10 blocks away in any of four directions is shut in: a pit, a pen, a cell. */
    static boolean trapped(ServerWorld world, net.minecraft.entity.mob.PathAwareEntity mob) {
        if (mob.isAiDisabled() || !(mob.getNavigation() instanceof net.minecraft.entity.ai.pathing.MobNavigation)) return false;
        // In bed the path finder starts from the bed and finds no way out: «выпусти меня из камеры» from a wife at home.
        if (mob.isSleeping()) return false;
        BlockPos origin = mob.getBlockPos();
        for (int[] step : new int[][]{{10, 0}, {-10, 0}, {0, 10}, {0, -10}}) {
            BlockPos away = origin.add(step[0], 0, step[1]);
            if (!world.getChunkManager().isChunkLoaded(away.getX() >> 4, away.getZ() >> 4)) return false;
            // Any surface near that column counts: the path finder settles on the reachable height itself.
            for (int dy : new int[]{0, 2, -2, 4, -4}) {
                net.minecraft.entity.ai.pathing.Path path = mob.getNavigation().findPathTo(away.up(dy), 1);
                if (path != null && path.reachesTarget()) return false;
            }
        }
        return true;
    }

    /** A parrot in a Parrot Cages cage; a jailed one is a stream viewer's parrot (its name is the viewer's nick) the streamer
     * locked up for a while — the villagers may poke fun at it when {@code villageParrotJokes} is on. */
    private static Map<String, Object> caged(LivingEntity entity, MobGameBridge bridge) {
        Map<String, Object> result = new LinkedHashMap<>(living(entity, false));
        result.put("inCage", true);
        if (entity.getCommandTags().contains(ParrotJailNews.JAILED_TAG)) {
            result.put("jailed", "stream viewer " + entity.getName().getString() + "'s parrot, jailed in the cage by the streamer");
            if (bridge.config.villageParrotJokes) result.put("villagersMayJoke", true);
        }
        return Map.copyOf(result);
    }

    static Map<String, Object> living(LivingEntity entity, boolean equipment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("health", entity.getHealth()); result.put("maxHealth", entity.getMaxHealth());
        result.put("armor", entity.getArmor()); result.put("onFire", entity.isOnFire());
        result.put("inWater", entity.isTouchingWater()); result.put("submerged", entity.isSubmergedInWater());
        result.put("onGround", entity.isOnGround()); result.put("air", entity.getAir());
        result.put("swimming", entity.isSwimming()); result.put("fallFlying", entity.isFallFlying());
        result.put("freezing", entity.isFrozen()); result.put("frozenTicks", entity.getFrozenTicks());
        result.put("velocity", vector(entity.getVelocity()));
        result.put("moving", entity.getVelocity().horizontalLengthSquared() > 0.0001
                || entity.hasVehicle() && entity.getVehicle().getVelocity().horizontalLengthSquared() > 0.0001);
        result.put("riding", entity.hasVehicle());
        result.put("vehicle", entity.getVehicle() == null ? "none" : entitySummary(entity.getVehicle()));
        result.put("passengers", entity.getPassengerList().stream().limit(16).map(MobContextCapture::entitySummary).toList());
        result.put("effects", entity.getStatusEffects().stream().map(effect -> Map.of("id",
                effect.getEffectType().getKey().map(key -> key.getValue().toString()).orElse("unknown"),
                "level", effect.getAmplifier() + 1, "ticks", effect.getDuration())).toList());
        if (equipment) {
            Map<String, Object> equipped = new LinkedHashMap<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) equipped.put(slot.getName(), item(entity.getEquippedStack(slot)));
            result.put("equipment", Map.copyOf(equipped));
            result.put("mainHand", item(entity.getMainHandStack())); result.put("offHand", item(entity.getOffHandStack()));
        }
        return Map.copyOf(result);
    }
    private static Map<String, Object> item(ItemStack stack) {
        List<Map<String, Object>> enchantments = new ArrayList<>();
        for (var entry : stack.getEnchantments().getEnchantmentEntries())
            enchantments.add(Map.of("id", entry.getKey().getKey().map(key -> key.getValue().toString()).orElse("unknown"),
                    "level", entry.getIntValue()));
        return Map.of("id", Registries.ITEM.getId(stack.getItem()).toString(), "name", stack.getName().getString(),
                "count", stack.getCount(), "damage", stack.getDamage(), "maxDamage", stack.getMaxDamage(),
                "enchantments", List.copyOf(enchantments));
    }
    private static void rememberPlace(Map<String, Object> details, String key, VillagerEntity villager,
            MemoryModuleType<GlobalPos> memory) {
        villager.getBrain().getOptionalRegisteredMemory(memory).ifPresent(place -> details.put(key,
                Map.of("dimension", place.dimension().getValue().toString(), "position", position(place.pos()))));
    }

    /** Only public native state is captured: no NBT dump or reflective access to private species data. */
    private static void speciesState(MobEntity mob, Map<String, Object> details) {
        if (mob instanceof PandaEntity panda) {
            details.put("nativeTemperament", panda.getProductGene().name().toLowerCase(Locale.ROOT));
            details.put("lazy", panda.isLazy()); details.put("worried", panda.isWorried());
            details.put("playful", panda.isPlayful()); details.put("weak", panda.isWeak()); details.put("brown", panda.isBrown());
            details.put("sitting", panda.isSitting()); details.put("eating", panda.isEating());
            details.put("playing", panda.isPlaying()); details.put("lyingOnBack", panda.isLyingOnBack());
            details.put("sneezing", panda.isSneezing()); details.put("scaredByThunder", panda.isScaredByThunderstorm());
        }
        if (mob instanceof BeeEntity bee) {
            details.put("hasNectar", bee.hasNectar()); details.put("hasStung", bee.hasStung());
            details.put("hive", bee.getHivePos() == null ? "unknown" : position(bee.getHivePos()));
            details.put("rememberedFlower", bee.getFlowerPos() == null ? "unknown" : position(bee.getFlowerPos()));
        }
        if (mob instanceof FrogEntity) details.put("lifeStage", "frog");
        if (mob instanceof TadpoleEntity) details.put("lifeStage", "tadpole");
        if (mob instanceof Bucketable bucketable) details.put("fromBucket", bucketable.isFromBucket());
        if (mob instanceof SheepEntity sheep) {
            details.put("woolColor", sheep.getColor().getName()); details.put("sheared", sheep.isSheared());
        }
        if (mob instanceof GoatEntity goat) {
            details.put("screaming", goat.isScreaming()); details.put("leftHorn", goat.hasLeftHorn()); details.put("rightHorn", goat.hasRightHorn());
        }
        if (mob instanceof FoxEntity fox) {
            details.put("sitting", fox.isSitting()); details.put("walking", fox.isWalking()); details.put("chasing", fox.isChasing());
        }
        if (mob instanceof WolfEntity wolf) { details.put("furWet", wolf.isFurWet()); details.put("begging", wolf.isBegging()); }
        if (mob instanceof CatEntity cat) details.put("sleepingPose", cat.isInSleepingPose());
        if (mob instanceof CamelEntity camel) { details.put("sitting", camel.isSitting()); details.put("dashing", camel.isDashing()); }
        if (mob instanceof AbstractHorseEntity horse) details.put("saddled", horse.isSaddled());
        if (mob instanceof AxolotlEntity axolotl) details.put("playingDead", axolotl.isPlayingDead());
        if (mob instanceof PolarBearEntity bear) details.put("warning", bear.isWarning());
        if (mob instanceof net.minecraft.entity.mob.CreeperEntity creeper) {
            details.put("ignited", creeper.isIgnited()); details.put("fuseSpeed", creeper.getFuseSpeed());
            details.put("charged", creeper.shouldRenderOverlay());
        }
        if (mob instanceof net.minecraft.entity.mob.ZombieEntity zombie) details.put("convertingInWater", zombie.isConvertingInWater());
        if (mob instanceof net.minecraft.entity.mob.PiglinEntity piglin) details.put("dancing", piglin.isDancing());
        if (mob instanceof net.minecraft.entity.mob.EndermanEntity enderman) {
            details.put("angry", enderman.isAngry()); details.put("provoked", enderman.isProvoked());
        }
        if (mob instanceof WardenEntity warden) details.put("anger", warden.getAnger());
        if (mob instanceof net.minecraft.entity.mob.SlimeEntity slime) details.put("size", slime.getSize());
        if (mob instanceof net.minecraft.entity.boss.dragon.EnderDragonEntity dragon)
            details.put("nativePhase", dragon.getPhaseManager().getCurrent().getType().toString());
        if (mob instanceof net.minecraft.entity.boss.WitherEntity wither) details.put("invulnerableTicks", wither.getInvulnerableTimer());
    }

    private static Map<String, Object> reticleEntity(ServerWorld world, MobEntity observer, ServerPlayerEntity player) {
        double range = Math.min(8, player.getEntityInteractionRange());
        Vec3d start = player.getEyePos();
        Vec3d end = start.add(player.getRotationVector().multiply(range));
        var block = world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, player));
        if (block.getType() != HitResult.Type.MISS) end = block.getPos();
        var hit = ProjectileUtil.raycast(player, start, end, player.getBoundingBox().stretch(end.subtract(start)).expand(1),
                entity -> !entity.isSpectator() && entity.canHit(), start.squaredDistanceTo(end));
        if (hit == null || hit.getEntity() != observer && !observer.canSee(hit.getEntity())) return Map.of("type", "none");
        Map<String, Object> result = new LinkedHashMap<>(entitySummary(hit.getEntity()));
        result.put("distanceFromPlayer", round(player.distanceTo(hit.getEntity())));
        result.put("isSelectedMob", hit.getEntity() == observer);
        return Map.copyOf(result);
    }

    private static List<Map<String, Object>> droppedItems(ServerWorld world, MobEntity mob) {
        List<ItemEntity> items = world.getEntitiesByClass(ItemEntity.class, mob.getBoundingBox().expand(8),
                entity -> entity.isAlive() && !entity.isRemoved() && entity.squaredDistanceTo(mob) <= 64 && mob.canSee(entity));
        items.sort(Comparator.comparingDouble(mob::squaredDistanceTo));
        return items.stream().limit(8).map(entity -> Map.<String, Object>of("item", item(entity.getStack()),
                "distance", round(mob.distanceTo(entity)), "direction", direction(mob.getPos(), entity.getPos()))).toList();
    }

    private static Map<String, Object> shelter(ServerWorld world, MobEntity mob) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openSky", world.isSkyVisible(BlockPos.ofFloored(mob.getEyePos())));
        result.put("rainReachesHere", world.hasRain(mob.getBlockPos()));
        for (int height = 1; height <= 8; height++) {
            BlockPos pos = BlockPos.ofFloored(mob.getEyePos()).up(height);
            if (!world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) break;
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && !state.getCollisionShape(world, pos).isEmpty()) {
                result.put("overheadBlock", Registries.BLOCK.getId(state.getBlock()).toString());
                result.put("overheadDistance", height); break;
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> entitySummary(Entity entity) {
        return Map.of("id", entity.getUuid().toString(), "type", MobGameBridge.type(entity), "name", entity.getName().getString());
    }
    private static Map<String, Object> position(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }
    private static Map<String, Object> vector(Vec3d vector) {
        return Map.of("x", Math.round(vector.x * 1000) / 1000.0, "y", Math.round(vector.y * 1000) / 1000.0,
                "z", Math.round(vector.z * 1000) / 1000.0);
    }
    private static List<Map<String, Object>> features(ServerWorld world, MobEntity mob) {
        Map<String, List<Map<String, Object>>> kinds = new LinkedHashMap<>();
        Set<String> categories = new HashSet<>();
        for (BlockPos mutable : BlockPos.iterateOutwards(mob.getBlockPos(), 8, 8, 8)) {
            BlockPos pos = mutable.toImmutable();
            if (mob.getPos().squaredDistanceTo(Vec3d.ofCenter(pos)) > 64
                    || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            BlockState state = world.getBlockState(pos);
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            String kind = featureKind(state, id);
            if (kind == null) continue;
            if (categories.contains(id)) continue;
            List<Map<String, Object>> group = kinds.computeIfAbsent(kind, ignored -> new ArrayList<>());
            if (group.size() == 2) continue;
            var hit = world.raycast(new RaycastContext(mob.getEyePos(), Vec3d.ofCenter(pos), RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.ANY, mob));
            if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(pos)) continue;
            categories.add(id);
            // Only the state a creature would mention: open, lit, grown; the rest is noise for the model.
            Map<String, String> properties = new LinkedHashMap<>();
            state.getEntries().forEach((property, value) -> { if (Set.of("open", "lit", "age", "powered").contains(property.getName())) properties.put(property.getName(), String.valueOf(value)); });
            group.add(Map.of("block", id, "kind", kind, "state", Map.copyOf(properties),
                    "distance", round(mob.getPos().distanceTo(Vec3d.ofCenter(pos))),
                    "direction", direction(mob.getPos(), Vec3d.ofCenter(pos))));
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < 2; index++)
            for (String kind : List.of("hazard", "shelter", "workstation", "vegetation", "storage", "water", "portal", "resource")) {
                List<Map<String, Object>> group = kinds.getOrDefault(kind, List.of());
                if (group.size() > index) result.add(group.get(index));
                if (result.size() == 8) return List.copyOf(result);
            }
        return List.copyOf(result);
    }
    private static String featureKind(BlockState state, String id) {
        if (id.contains("lava") || state.isIn(BlockTags.FIRE) || id.contains("campfire") || id.equals("minecraft:magma_block")) return "hazard";
        if (state.isIn(BlockTags.BEDS) || id.contains("door")) return "shelter";
        if (WORKSTATIONS.contains(id)) return "workstation";
        if (state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.LEAVES) || state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.CROPS)
                || id.contains("grass") || id.contains("fern") || id.contains("bamboo") || id.contains("mushroom")
                || id.equals("minecraft:sugar_cane") || id.equals("minecraft:cactus") || id.equals("minecraft:vine")) return "vegetation";
        if (id.contains("chest") || id.contains("shulker_box")) return "storage";
        if (id.equals("minecraft:water")) return "water";
        if (id.contains("portal")) return "portal";
        if (id.contains("ore") || id.equals("minecraft:farmland")) return "resource";
        return null;
    }
    private static final Set<String> WORKSTATIONS = Set.of("minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace",
            "minecraft:smoker", "minecraft:brewing_stand", "minecraft:enchanting_table", "minecraft:anvil", "minecraft:chipped_anvil",
            "minecraft:damaged_anvil", "minecraft:smithing_table", "minecraft:stonecutter", "minecraft:cartography_table",
            "minecraft:fletching_table", "minecraft:loom", "minecraft:grindstone", "minecraft:composter", "minecraft:barrel", "minecraft:lectern");
    static String direction(Vec3d from, Vec3d to) {
        Vec3d delta = to.subtract(from);
        String horizontal = Math.abs(delta.x) > Math.abs(delta.z) ? delta.x > 0 ? "east" : "west" : delta.z > 0 ? "south" : "north";
        return horizontal + (delta.y > 2 ? ", above" : delta.y < -2 ? ", below" : "");
    }
    private static double round(double value) { return Math.round(value * 10) / 10.0; }
}
