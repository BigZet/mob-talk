package io.github.divineblock.weapon;

import net.minecraft.block.Block;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;

/** What a weapon is made of, for the vanilla sword and tool classes: how long it lasts, how well it enchants, and what (if
 * anything) repairs it on an anvil. The relic weapons repair with nothing: only the god's {@code repair} mends them. */
public enum WeaponMaterial implements net.minecraft.item.ToolMaterial {
    /** The cherub's sword: it burns out and goes back to its keeper. */
    CHERUB(300, 6f, 22, Ingredient.EMPTY),
    /** Steel of the heavenly host: as diamond. */
    HOLY(1561, 7f, 15, Ingredient.EMPTY),
    /** Blackened war steel: as netherite. */
    WAR(2031, 8f, 15, Ingredient.EMPTY),
    /** An old jawbone. */
    BONE(60, 4f, 5, Ingredient.EMPTY),
    /** A whittled stake. */
    WOOD(50, 2f, 15, Ingredient.EMPTY),
    /** A lance shaft. */
    SPEAR(500, 6f, 12, Ingredient.EMPTY);

    private final int durability, enchantability;
    private final float attackDamage;
    private final Ingredient repair;

    WeaponMaterial(int durability, float attackDamage, int enchantability, Ingredient repair) {
        this.durability = durability; this.attackDamage = attackDamage; this.enchantability = enchantability; this.repair = repair;
    }

    @Override public int getDurability() { return durability; }
    @Override public float getMiningSpeedMultiplier() { return 1f; }
    @Override public float getAttackDamage() { return attackDamage; }
    @Override public TagKey<Block> getInverseTag() { return BlockTags.INCORRECT_FOR_WOODEN_TOOL; }
    @Override public int getEnchantability() { return enchantability; }
    @Override public Ingredient getRepairIngredient() { return repair; }
}
