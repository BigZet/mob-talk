package io.github.mobtalk.game;

import io.github.mobtalk.core.MobTalkConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.FuzzyTargeting;
import net.minecraft.entity.ai.NoPenaltyTargeting;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** A city bandit (design: docs/bandits.md): a man in a balaclava or a cap who comes out at night, picks a passer-by and says "your
 * money or your life". He is a monster to the game (the village's alarm, guards, archers and golem know what to do with him) but
 * keeps his knife hidden while he only prowls or asks: the defence notices him once he strikes or runs with loot.
 * <ul><li>PROWL — wanders, looks for a player who is about; at times picks a villager instead;</li>
 * <li>DEMAND — walks up to the player and waits for emeralds (a right click with them, or a gift in the conversation);</li>
 * <li>FIGHT — the player said no, ran or struck: his blows snatch emeralds or a thing, and with loot he runs;</li>
 * <li>FLEE — runs off with the loot and vanishes out of sight; killed, he drops it;</li>
 * <li>LEAVE — paid, or dawn: walks off and vanishes.</li></ul>
 * The gang ({@link #gang}) shares the mark and the demand; {@link BanditController} spawns them and holds the night's rules. */
public final class BanditEntity extends HostileEntity {
    public enum Mode { PROWL, DEMAND, FIGHT, FLEE, LEAVE }

    public static final int SKINS = 6;
    private static final TrackedData<Integer> SKIN = DataTracker.registerData(BanditEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final String[] NICKS = {"Косой", "Штопор", "Сиплый", "Гвоздь", "Жмых", "Лысый", "Шнырь", "Валет", "Кабан", "Фикса",
            "Цыган", "Бугор", "Хорёк", "Сухарь", "Дрын", "Кирпич"};

    private Mode mode = Mode.PROWL;
    private UUID mark, gang = UUID.randomUUID();
    private int demand, paid, haggled;
    private long deadline, reminded, fled, lastSeen;
    private Vec3d askedAt;
    private final SimpleInventory loot = new SimpleInventory(9);

    public BanditEntity(EntityType<? extends HostileEntity> type, World world) {
        super(type, world);
        experiencePoints = 8;
        if (getNavigation() instanceof net.minecraft.entity.ai.pathing.MobNavigation navigation) { navigation.setCanPathThroughDoors(true); navigation.setCanEnterOpenDoors(true); }
    }

    public static DefaultAttributeContainer.Builder attributes() {
        return HostileEntity.createHostileAttributes().add(EntityAttributes.GENERIC_MAX_HEALTH, 24).add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 4).add(EntityAttributes.GENERIC_FOLLOW_RANGE, 32);
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) { super.initDataTracker(builder); builder.add(SKIN, 0); }
    public int skin() { return Math.floorMod(dataTracker.get(SKIN), SKINS); }

    @Override protected void initGoals() {
        goalSelector.add(0, new SwimGoal(this));
        goalSelector.add(1, new net.minecraft.entity.ai.goal.LongDoorInteractGoal(this, true)); // through stairwell doors, shutting them behind
        goalSelector.add(2, new MeleeAttackGoal(this, 1.15, false));
        goalSelector.add(7, new WanderAroundFarGoal(this, 0.8));
        goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 10f));
        goalSelector.add(9, new LookAroundGoal(this));
        targetSelector.add(1, new RevengeGoal(this, BanditEntity.class).setGroupRevenge());
    }

    /** A new bandit: his face, his nick, his knife or hatchet; the gang he walks with. */
    void dress(UUID gang, int skin) {
        this.gang = gang;
        dataTracker.set(SKIN, Math.floorMod(skin, SKINS));
        setCustomName(Text.literal("Бандит " + NICKS[random.nextInt(NICKS.length)]));
        setCustomNameVisible(false);
        equipStack(EquipmentSlot.MAINHAND, new ItemStack(random.nextInt(3) == 0 ? Items.STONE_AXE : Items.IRON_SWORD));
        setEquipmentDropChance(EquipmentSlot.MAINHAND, 0.05f);
    }

    /** A spawn egg or a plain /summon: a bandit of his own gang, dressed like one. */
    @Override public net.minecraft.entity.EntityData initialize(net.minecraft.world.ServerWorldAccess world, net.minecraft.world.LocalDifficulty difficulty,
                                                               net.minecraft.entity.SpawnReason reason, net.minecraft.entity.EntityData data) {
        net.minecraft.entity.EntityData result = super.initialize(world, difficulty, reason, data);
        dress(UUID.randomUUID(), random.nextInt(SKINS));
        MobTalkConfig config = BanditController.config(world.toServerWorld().getServer());
        if (config != null) tune(config);
        return result;
    }

    void tune(MobTalkConfig config) {
        var health = getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null && health.getBaseValue() != config.banditHealth) { health.setBaseValue(config.banditHealth); setHealth(config.banditHealth); }
        var damage = getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
        if (damage != null) damage.setBaseValue(config.banditDamage);
    }

    public Mode mode() { return mode; }
    public UUID mark() { return mark; }
    public UUID gang() { return gang; }
    public int demand() { return demand; }
    public int lootCount() { int n = 0; for (int i = 0; i < loot.size(); i++) n += loot.getStack(i).getCount(); return n; }
    /** Hidden from the village's defence: he only prowls or asks, his knife is in his pocket. */
    public boolean lurking() { return mode == Mode.PROWL || mode == Mode.DEMAND || mode == Mode.LEAVE; }

    // ---- The gang's decisions (from BanditController) ----

    void ask(ServerPlayerEntity player, int amount, long now, int seconds) {
        mode = Mode.DEMAND; mark = player.getUuid(); demand = amount; paid = 0; haggled = 0; deadline = now + seconds * 20L; reminded = now; setTarget(null);
        askedAt = player.getPos();
    }

    /** The player this gang came out for: they walk to him before anyone else. */
    void stalk(UUID player) { mark = player; }

    /** Whether the player ran off from where he was stopped (more than {@code blocks} away from that spot). */
    boolean ranOff(PlayerEntity player, double blocks) { return askedAt != null && player.getPos().squaredDistanceTo(askedAt) > blocks * blocks; }

    /** The police track's mark: a mob with it is a criminal the police chase and take away. */
    public static final String WANTED = "mobtalk_wanted";

    void fight(LivingEntity victim) {
        mode = Mode.FIGHT; mark = victim.getUuid(); setTarget(victim); addCommandTag(WANTED);
    }

    void leave() { mode = Mode.LEAVE; setTarget(null); fled = getWorld().getTime(); }

    void flee() { mode = Mode.FLEE; setTarget(null); fled = getWorld().getTime(); addCommandTag(WANTED); }

    /** Killed by a player: his gang will remember (BanditController's grudge). */
    @Override public void onDeath(DamageSource source) {
        super.onDeath(source);
        if (getWorld() instanceof ServerWorld world && source.getAttacker() instanceof ServerPlayerEntity player) {
            BanditController controller = BanditController.of(world.getServer());
            if (controller != null) controller.killed(world, player, this);
        }
    }

    /** The bandits of his gang within a street of him. */
    List<BanditEntity> mates() {
        return getWorld().getEntitiesByClass(BanditEntity.class, getBoundingBox().expand(32), b -> b.isAlive() && b.gang.equals(gang));
    }

    // ---- Paying ----

    @Override protected ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);
        if (held.isOf(Items.EMERALD) && player instanceof ServerPlayerEntity server && mode == Mode.DEMAND && server.getUuid().equals(mark)) {
            pay(server, held);
            return ActionResult.SUCCESS;
        }
        return super.interactMob(player, hand);
    }

    /** The player hands over emeralds from this stack: as much as is still owed; the whole debt paid, the gang goes. */
    String pay(ServerPlayerEntity player, ItemStack stack) {
        BanditController controller = BanditController.of(player.getServer());
        if (mode != Mode.DEMAND || !player.getUuid().equals(mark)) return "Бандит ничего у вас не просит";
        if (!stack.isOf(Items.EMERALD)) {
            say(player, "Мне это не нужно. Изумруды давай.");
            return "Бандит не взял подарок: ему нужны изумруды";
        }
        int give = Math.min(stack.getCount(), demand - paid);
        if (give <= 0) return "";
        loot.addStack(stack.split(give));
        paid += give;
        getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.HOSTILE, 0.8f, 0.8f);
        if (paid < demand) {
            say(player, "Ещё " + (demand - paid) + ". Не жмись.");
            for (BanditEntity mate : mates()) mate.paid = paid;
            return "Отдано " + give + ", бандит ждёт ещё " + (demand - paid);
        }
        say(player, controller == null ? "Приятно иметь дело." : controller.thanks(random));
        if (controller != null) controller.paid((ServerWorld) getWorld(), player, this);
        for (BanditEntity mate : mates()) mate.leave();
        leave();
        return "Бандиты взяли " + demand + " изумр. и уходят";
    }

    // ---- Robbing ----

    @Override public boolean tryAttack(net.minecraft.entity.Entity target) {
        boolean hit = super.tryAttack(target);
        if (hit && getWorld() instanceof ServerWorld world && mode == Mode.FIGHT) {
            MobTalkConfig config = BanditController.config(world.getServer());
            if (config != null && random.nextInt(100) < config.banditRobChance) {
                if (target instanceof ServerPlayerEntity player && snatch(player, config)) { BanditController.whistle(this); for (BanditEntity mate : mates()) mate.flee(); flee(); } // "ноги в руки": the whole gang runs
                else if (target instanceof VillagerEntity villager && config.banditsRobVillagers) {
                    BanditController controller = BanditController.of(world.getServer());
                    if (controller != null) controller.robbedVillager(this, villager);
                    flee();
                }
            }
        }
        return hit;
    }

    /** One grab at the player's pockets: emeralds first (as many as the gang asked, at least a few), else any one thing he carries
     * apart from what he wears. Told to the player on the action bar and in the chat. */
    boolean snatch(ServerPlayerEntity player, MobTalkConfig config) {
        PlayerInventory inventory = player.getInventory();
        int want = Math.max(demand - paid, Math.max(1, emeralds(player) * config.banditDemandPercent / 100));
        int taken = 0;
        for (int i = 0; i < inventory.main.size() && taken < want; i++) {
            ItemStack stack = inventory.main.get(i);
            if (!stack.isOf(Items.EMERALD)) continue;
            ItemStack grab = stack.split(Math.min(stack.getCount(), want - taken));
            taken += grab.getCount();
            loot.addStack(grab);
        }
        Text what;
        if (taken > 0) what = Text.literal(taken + " изумр.");
        else {
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < inventory.main.size(); i++) if (!inventory.main.get(i).isEmpty()) slots.add(i);
            if (slots.isEmpty()) return false;
            int slot = slots.get(random.nextInt(slots.size()));
            ItemStack grab = inventory.main.get(slot);
            int count = grab.getMaxCount() > 1 ? Math.max(1, (grab.getCount() + 1) / 2) : 1;
            ItemStack stolen = grab.split(count);
            what = Text.literal(stolen.getCount() > 1 ? stolen.getCount() + " × " : "").append(stolen.getName());
            loot.addStack(stolen);
        }
        player.sendMessage(Text.literal(getName().getString() + " вырвал у вас: ").formatted(Formatting.RED).append(what), true);
        player.sendMessage(Text.literal("<" + getName().getString() + "> ").append(Text.literal(random.nextBoolean() ? "Опа, моё! Ноги в руки!" : "Есть! Шухер, пацаны!")), false);
        player.playSoundToPlayer(SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.HOSTILE, 1f, 0.6f);
        BanditController controller = BanditController.of(player.getServer());
        if (controller != null) controller.robbed(this, player, what.getString());
        return true;
    }

    static int emeralds(PlayerEntity player) {
        int n = 0;
        for (ItemStack stack : player.getInventory().main) if (stack.isOf(Items.EMERALD)) n += stack.getCount();
        return n;
    }

    /** What lies on the ground near him after a fight (the player's dropped things): emeralds first, then anything small. */
    void pickUpSpoils() {
        for (ItemEntity item : getWorld().getEntitiesByClass(ItemEntity.class, getBoundingBox().expand(4, 2, 4), e -> e.isAlive() && !e.cannotPickup())) {
            ItemStack rest = loot.addStack(item.getStack().copy());
            if (rest.isEmpty()) item.discard(); else item.setStack(rest);
        }
    }

    // ---- Death: the loot falls ----

    @Override protected void dropEquipment(ServerWorld world, DamageSource source, boolean causedByPlayer) {
        super.dropEquipment(world, source, causedByPlayer);
        for (int i = 0; i < loot.size(); i++) {
            ItemStack stack = loot.removeStack(i);
            if (!stack.isEmpty()) dropStack(stack);
        }
    }

    @Override public boolean canImmediatelyDespawn(double distance) { return lootCount() == 0 || mode == Mode.LEAVE; }

    // ---- Every tick (server) ----

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world)) return;
        BanditController controller = BanditController.of(world.getServer());
        if (controller != null && age % 5 == 0) controller.think(world, this);
    }

    /** Running off: away from the mark, faster; vanishes once no player sees him for a few seconds. */
    void runOff(ServerWorld world, Vec3d from) {
        if (getNavigation().isIdle()) {
            Vec3d to = from == null ? NoPenaltyTargeting.find(this, 20, 7) : NoPenaltyTargeting.findFrom(this, 24, 7, from);
            if (to == null && from != null) to = NoPenaltyTargeting.findFrom(this, 10, 7, from); // a yard closed in: a shorter dash
            if (to == null) to = FuzzyTargeting.find(this, 10, 7);
            if (to != null) getNavigation().startMovingTo(to.x, to.y, to.z, mode == Mode.FLEE ? 1.35 : 0.9);
        }
        boolean seen = false;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (player.isSpectator() || player.squaredDistanceTo(this) > 48 * 48) continue;
            if (player.squaredDistanceTo(this) < 12 * 12 || player.canSee(this)) { seen = true; break; }
        }
        long now = world.getTime();
        if (seen) lastSeen = now;
        if (!seen && now - lastSeen > 60 && now - fled > 60) {
            world.playSound(null, getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_NODAMAGE, SoundCategory.HOSTILE, 0.3f, 1f);
            discard();
        }
    }

    boolean late(long now) { return mode == Mode.DEMAND && now > deadline; }
    boolean remind(long now) {
        if (mode != Mode.DEMAND || now - reminded < (deadline - reminded) / 2 || now - reminded < 160) return false;
        reminded = now; return true;
    }
    int owed() { return demand - paid; }
    int paidSoFar() { return paid; }
    /** How long (ticks) he has hung back from a player with a light; {@link #unshy} when the light is gone. */
    long shy(long now) { if (shySince < 0) shySince = now; return now - shySince; }
    void unshy() { shySince = -1; }
    private long shySince = -1;
    /** A few steps back into the dark, away from {@code from}. */
    void backOff(Vec3d from) {
        Vec3d to = NoPenaltyTargeting.findFrom(this, 8, 4, from);
        if (to != null) getNavigation().startMovingTo(to.x, to.y, to.z, 1.0);
    }
    /** The haggle moves already tried with this gang ({@code BanditHaggle.State#used}). */
    int haggled() { return haggled; }
    /** A haggle moved the price: the new whole demand, more (or less) time to pay, the moves tried. */
    void rebargain(int demand, int seconds, int used) {
        this.demand = Math.max(paid + 1, demand); haggled = used;
        deadline = Math.max(getWorld().getTime() + 60, deadline + seconds * 20L);
    }
    /** The clock of the demand stops for a while (the player is talking to him). */
    void hold(int ticks) { deadline += ticks; reminded += ticks; }

    /** A line in the chat from him to the players near. */
    void say(ServerPlayerEntity player, String line) {
        Text text = Text.literal("<" + getName().getString() + "> ").append(Text.literal(line));
        for (ServerPlayerEntity near : ((ServerWorld) getWorld()).getPlayers(p -> p.squaredDistanceTo(this) < 24 * 24))
            near.sendMessage(text, false);
        if (player != null && player.squaredDistanceTo(this) >= 24 * 24) player.sendMessage(text, false);
        getWorld().playSound(null, getBlockPos(), SoundEvents.ENTITY_PILLAGER_AMBIENT, SoundCategory.HOSTILE, 0.6f, 1.1f);
    }

    // ---- Sounds: a man, not a pillager ----

    @Override protected SoundEvent getAmbientSound() { return null; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ENTITY_PLAYER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_PLAYER_DEATH; }

    // ---- Saving ----

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("Skin", skin());
        nbt.putString("Mode", mode.name());
        nbt.putUuid("Gang", gang);
        if (mark != null) nbt.putUuid("Mark", mark);
        nbt.putInt("Demand", demand); nbt.putInt("Paid", paid); nbt.putInt("Haggled", haggled);
        nbt.put("Loot", loot.toNbtList(getRegistryManager()));
    }

    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        dataTracker.set(SKIN, nbt.getInt("Skin"));
        try { mode = Mode.valueOf(nbt.getString("Mode")); } catch (IllegalArgumentException e) { mode = Mode.PROWL; }
        if (mode == Mode.DEMAND || mode == Mode.FIGHT) mode = Mode.PROWL; // a deadline does not survive a reload
        if (nbt.containsUuid("Gang")) gang = nbt.getUuid("Gang");
        mark = nbt.containsUuid("Mark") ? nbt.getUuid("Mark") : null;
        demand = nbt.getInt("Demand"); paid = nbt.getInt("Paid"); haggled = nbt.getInt("Haggled");
        loot.readNbtList(nbt.getList("Loot", 10), getRegistryManager());
    }

    /** Villagers in reach that are not defenders or travellers: whom he could mug. */
    List<VillagerEntity> prey(double range) {
        return getWorld().getEntitiesByClass(VillagerEntity.class, new Box(getBlockPos()).expand(range, 6, range),
                v -> v.isAlive() && !v.isBaby() && !DefenseContent.defender(v) && !DefenseContent.traveller(v) && !v.isSleeping());
    }

    @Override public boolean canTarget(LivingEntity target) {
        // Never each other, never the village's defenders while he is only prowling.
        if (target instanceof BanditEntity) return false;
        return super.canTarget(target);
    }
}
