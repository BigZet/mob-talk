package io.github.divineblock.weapon;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.DivineState;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

/** The weapons: registered here, each with its own class for what it does; crafted from the bosses' relics (recipes in
 * {@code data/divineblock/recipe/}). {@link #enabled} is the F9 switch: a weapon switched off does nothing in the hand but say
 * «бог не велит» (it can still be crafted and kept). {@link #power} scales every weapon's own extra
 * harm by the «Сила оружия» setting. */
public final class DivineWeapons {
    public static final Map<WeaponKind, Item> ITEMS = new EnumMap<>(WeaponKind.class);
    public static final Item SLING_STONE = new Item(new Item.Settings().maxCount(64));
    public static final Item DAVID_SLING = new SlingItem(new Item.Settings().maxDamage(240));
    public static final Item CHERUB_SWORD = new CherubSwordItem(new Item.Settings().rarity(Rarity.EPIC).fireproof());
    public static final Item ARCHISTRATEGOS_SWORD = new ArchistrategosSwordItem(new Item.Settings().rarity(Rarity.EPIC));
    public static final Item GEORGE_SPEAR = new GeorgeSpearItem(new Item.Settings().rarity(Rarity.RARE).maxDamage(500));
    public static final Item NIMROD_BOW = new NimrodBowItem(new Item.Settings().rarity(Rarity.RARE).maxDamage(520));
    public static final Item SAMSON_JAWBONE = new SamsonJawboneItem(new Item.Settings().rarity(Rarity.UNCOMMON));
    public static final Item GIDEON_JAR = new GideonJarItem(new Item.Settings().maxCount(16));
    public static final Item WAR_SWORD = new WarSwordItem(new Item.Settings().rarity(Rarity.EPIC).fireproof());
    public static final Item JERICHO_HORN = new JerichoHornItem(new Item.Settings().rarity(Rarity.RARE).maxDamage(10));
    public static final Item ASPEN_STAKE = new AspenStakeItem(new Item.Settings());

    public static final EntityType<SlingStoneEntity> SLING_STONE_ENTITY = Registry.register(Registries.ENTITY_TYPE, Identifier.of(DivineBlockMod.ID, "sling_stone"),
            EntityType.Builder.<SlingStoneEntity>create(SlingStoneEntity::new, SpawnGroup.MISC).dimensions(0.25f, 0.25f).maxTrackingRange(4).trackingTickInterval(10)
                    .build(DivineBlockMod.ID + ":sling_stone"));
    public static final EntityType<ThrownSpearEntity> THROWN_SPEAR = Registry.register(Registries.ENTITY_TYPE, Identifier.of(DivineBlockMod.ID, "thrown_spear"),
            EntityType.Builder.<ThrownSpearEntity>create(ThrownSpearEntity::new, SpawnGroup.MISC).dimensions(0.5f, 0.5f).maxTrackingRange(4).trackingTickInterval(20)
                    .build(DivineBlockMod.ID + ":thrown_spear"));
    /** «Смятение» of Gideon's pitchers: a mob under it turns on the nearest other mob (see {@link WeaponHooks}). */
    public static final RegistryEntry<StatusEffect> ROUT = Registry.registerReference(Registries.STATUS_EFFECT, Identifier.of(DivineBlockMod.ID, "rout"), new RoutEffect());

    private DivineWeapons() { }

    /** Called once from the mod initializer. */
    public static void register() {
        item(WeaponKind.DAVID_SLING, DAVID_SLING);
        Registry.register(Registries.ITEM, Identifier.of(DivineBlockMod.ID, "sling_stone"), SLING_STONE);
        item(WeaponKind.CHERUB_SWORD, CHERUB_SWORD);
        item(WeaponKind.ARCHISTRATEGOS_SWORD, ARCHISTRATEGOS_SWORD);
        item(WeaponKind.GEORGE_SPEAR, GEORGE_SPEAR);
        item(WeaponKind.NIMROD_BOW, NIMROD_BOW);
        item(WeaponKind.SAMSON_JAWBONE, SAMSON_JAWBONE);
        item(WeaponKind.GIDEON_JAR, GIDEON_JAR);
        item(WeaponKind.WAR_SWORD, WAR_SWORD);
        item(WeaponKind.JERICHO_HORN, JERICHO_HORN);
        item(WeaponKind.ASPEN_STAKE, ASPEN_STAKE);
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> { ITEMS.values().forEach(entries::add); entries.add(SLING_STONE); });
        ItemGroupEvents.modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(DivineBlockMod.ID, "main")))
                .register(entries -> { ITEMS.values().forEach(entries::add); entries.add(SLING_STONE); });
        WeaponHooks.register();
        WeaponCommands.register();
    }

    private static void item(WeaponKind kind, Item item) {
        Registry.register(Registries.ITEM, Identifier.of(DivineBlockMod.ID, kind.id), item);
        ITEMS.put(kind, item);
    }

    public static Item item(WeaponKind kind) { return ITEMS.get(kind); }

    public static WeaponKind kindOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        for (Map.Entry<WeaponKind, Item> entry : ITEMS.entrySet()) if (stack.isOf(entry.getValue())) return entry.getKey();
        return null;
    }

    public static List<String> ids() { return java.util.Arrays.stream(WeaponKind.values()).map(kind -> kind.id).toList(); }

    public static boolean enabled(WeaponKind kind, DivineConfig config) { return kind != null && !config.disabledWeapons.contains(kind.id); }
    public static boolean enabled(WeaponKind kind) { return enabled(kind, DivineBlockMod.config()); }

    /** The «Сила оружия» setting as a multiplier for the weapons' own extra harm (not the vanilla base damage). */
    public static float power() { return DivineBlockMod.config().weaponPower / 100f; }

    /** A switched-off weapon in a player's hand: tells them and returns false; otherwise true. */
    static boolean allowed(PlayerEntity player, WeaponKind kind) {
        if (enabled(kind)) return true;
        if (player instanceof ServerPlayerEntity server) server.sendMessage(Text.literal("Бог не велит: " + kind.title + " выключен в настройках").formatted(Formatting.GOLD), true);
        return false;
    }

    /** The god's word for a player: «святой», «праведник», «обычный смертный», «грешник» or «проклятый». */
    static String standing(LivingEntity who) {
        if (!(who instanceof ServerPlayerEntity player) || player.getServer() == null) return "обычный смертный";
        return DivineState.of(player.getServer()).ledger.soul(player.getUuid()).righteousness.label();
    }
    static boolean righteous(LivingEntity who) { String s = standing(who); return s.equals("святой") || s.equals("праведник"); }
    static boolean saint(LivingEntity who) { return standing(who).equals("святой"); }
    static boolean sinner(LivingEntity who) { String s = standing(who); return s.equals("грешник") || s.equals("проклятый"); }

    /** Extra harm from a weapon's own rule, dealt on top of the blow and scaled by {@link #power}; never more than the
     * victim's whole health in one go. Bosses take it through their own armour ({@code SacredBoss.damage}). */
    static void extra(LivingEntity victim, LivingEntity attacker, float amount) {
        if (victim == null || !victim.isAlive() || amount <= 0) return;
        float scaled = amount * power();
        DamageSource source = attacker instanceof PlayerEntity player ? victim.getDamageSources().playerAttack(player) : victim.getDamageSources().mobAttack(attacker);
        victim.timeUntilRegen = 0; // the blow just landed; the extra would otherwise be swallowed by invulnerability frames
        victim.damage(source, Math.min(scaled, victim.getMaxHealth()));
    }

    static final class RoutEffect extends StatusEffect {
        RoutEffect() { super(StatusEffectCategory.HARMFUL, 0xF0D080); }
    }
}
