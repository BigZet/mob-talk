package io.github.divineblock.boss;

import io.github.divineblock.DivineBlockMod;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;

/** The bosses' own afflictions. Their rules live here and in the mixins:
 * <ul>
 * <li>«Обет молчания» (Чёрный Схимник): no eating, drinking (milk too) or totem of undying; prayers, spoken or typed, are not heard.</li>
 * <li>«Звон в ушах» (Звонарь): the client shakes the camera, muffles every sound and makes the crosshair wobble.</li>
 * <li>«Опаление» (Серафим, Архангел): any healing burns instead, a little at a time.</li>
 * <li>«Взор лика» (Безликий лик): looking at the icon hurts, and the camera is drawn toward it.</li>
 * <li>«Панихида» (Плакальщица): maximum health drains by a heart every ten seconds, down to three hearts; the loss stays until
 * the mourner is killed or the player kneels at an altar, even if the effect itself wears off.</li>
 * </ul> */
public final class BossEffects {
    public static final RegistryEntry<StatusEffect> VOW_OF_SILENCE = register("vow_of_silence", new SacredEffect(0x14121A));
    public static final RegistryEntry<StatusEffect> RINGING = register("ringing", new SacredEffect(0xB0883A));
    public static final RegistryEntry<StatusEffect> SCORCH = register("scorch", new SacredEffect(0xFF7A1E));
    public static final RegistryEntry<StatusEffect> GAZE = register("gaze", new SacredEffect(0xE8C66A));
    public static final RegistryEntry<StatusEffect> PANIKHIDA = register("panikhida", new Panikhida());
    // The biblical bosses. Their rules: here when shared and simple, otherwise in the boss's own registerHooks().
    /** Левиафан: in water the player sinks and cannot swim up (client, {@code BossClient}). */
    public static final RegistryEntry<StatusEffect> DEPTHS = register("depths", new SacredEffect(0x1C3550));
    /** Бегемот: no jumping (mixin on jump). */
    public static final RegistryEntry<StatusEffect> DUST = register("dust", new SacredEffect(0x7A6040));
    /** Голиаф: stunned by the giant's blow. */
    public static final RegistryEntry<StatusEffect> STUN = register("stun", new SacredEffect(0x9A8A70));
    /** Вороной всадник: food rots in the inventory. */
    public static final RegistryEntry<StatusEffect> FAMINE = register("famine", new SacredEffect(0x2A2A20));
    /** Ангел-губитель: a deadly mark that fades only under a blood-red doorway. */
    public static final RegistryEntry<StatusEffect> FIRSTBORN_MARK = register("firstborn_mark", new SacredEffect(0x8A0A10));
    /** Херувим: cast out of the guarded place, cannot come back for a while. */
    public static final RegistryEntry<StatusEffect> EXILE = register("exile", new SacredEffect(0xFFB040));
    /** Зверь: no trading with villagers. */
    public static final RegistryEntry<StatusEffect> MARK_OF_BEAST = register("mark_of_beast", new SacredEffect(0x5A0A0A));
    /** Нимрод: chat, subtitles and item names come scrambled (client). */
    public static final RegistryEntry<StatusEffect> CONFUSION = register("confusion_of_tongues", new SacredEffect(0xC8A050));

    static final Identifier PANIKHIDA_LOSS = Identifier.of(DivineBlockMod.ID, "panikhida_loss");
    /** Panikhida never leaves less than this much maximum health. */
    static final double PANIKHIDA_FLOOR = 6;

    private BossEffects() { }

    private static RegistryEntry<StatusEffect> register(String id, StatusEffect effect) {
        return Registry.registerReference(Registries.STATUS_EFFECT, Identifier.of(DivineBlockMod.ID, id), effect);
    }

    static void register() {
        // The mark of the beast: no buying or selling (Revelation 13:17).
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!player.hasStatusEffect(MARK_OF_BEAST) || !(entity instanceof net.minecraft.entity.passive.MerchantEntity)) return net.minecraft.util.ActionResult.PASS;
            if (!world.isClient) player.sendMessage(Text.literal("Начертание зверя: ни купить, ни продать").formatted(Formatting.DARK_RED), true);
            return net.minecraft.util.ActionResult.FAIL;
        });
        // The vow: nothing to eat or drink, milk included (it would wash the vow away).
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (!silenced(player) || !(stack.contains(DataComponentTypes.FOOD) || stack.getUseAction() == UseAction.DRINK)) return TypedActionResult.pass(stack);
            if (!world.isClient) player.sendMessage(Text.literal("Обет молчания: ни есть, ни пить").formatted(Formatting.DARK_GRAY), true);
            return TypedActionResult.fail(stack);
        });
    }

    public static boolean silenced(LivingEntity entity) { return entity != null && entity.hasStatusEffect(VOW_OF_SILENCE); }
    public static boolean scorched(LivingEntity entity) { return entity != null && entity.hasStatusEffect(SCORCH); }

    public static void afflict(LivingEntity victim, RegistryEntry<StatusEffect> effect, int ticks) {
        if (victim != null && victim.isAlive()) victim.addStatusEffect(new StatusEffectInstance(effect, ticks, 0, false, true, true));
    }

    /** Lifts the Panikhida: the effect and the health it took. At an altar, or when the mourner dies. */
    public static boolean lift(LivingEntity entity) {
        boolean had = entity.hasStatusEffect(PANIKHIDA);
        entity.removeStatusEffect(PANIKHIDA);
        EntityAttributeInstance health = entity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null && health.getModifier(PANIKHIDA_LOSS) != null) { health.removeModifier(PANIKHIDA_LOSS); had = true; }
        return had;
    }
    /** Lifts the Panikhida from every player on the server (the mourner is dead). */
    public static void liftAll(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList())
            if (lift(player)) player.sendMessage(Text.literal("Панихида отпета: силы возвращаются").formatted(Formatting.GOLD), true);
    }
    /** The health the Panikhida took, in half-hearts (0 when none). */
    public static double panikhidaLoss(LivingEntity entity) {
        EntityAttributeInstance health = entity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        EntityAttributeModifier loss = health == null ? null : health.getModifier(PANIKHIDA_LOSS);
        return loss == null ? 0 : -loss.value();
    }

    /** A plain harmful effect: its rules live in {@link BossEffects} and the mixins. */
    static class SacredEffect extends StatusEffect {
        SacredEffect(int color) { super(StatusEffectCategory.HARMFUL, color); }
    }

    /** Every ten seconds the funeral takes a heart of maximum health, down to {@link #PANIKHIDA_FLOOR}. */
    static final class Panikhida extends SacredEffect {
        Panikhida() { super(0x4A4A58); }
        @Override public boolean canApplyUpdateEffect(int duration, int amplifier) { return duration % 200 == 0; }
        @Override public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
            if (entity.getWorld().isClient || !(entity instanceof PlayerEntity)) return true;
            EntityAttributeInstance health = entity.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
            if (health == null) return true;
            double loss = panikhidaLoss(entity), without = health.getValue() + loss;
            double next = Math.min(loss + 2 * (amplifier + 1), Math.max(0, without - PANIKHIDA_FLOOR));
            if (next > loss) {
                health.overwritePersistentModifier(new EntityAttributeModifier(PANIKHIDA_LOSS, -next, EntityAttributeModifier.Operation.ADD_VALUE));
                if (entity.getHealth() > entity.getMaxHealth()) entity.setHealth(entity.getMaxHealth());
            }
            return true;
        }
    }
}
