package io.github.mobtalk.core.place;

/** What a block means for a creature's body: whether it can be walked through, seen through, dug through, and whether it hurts.
 * The ordinal is the class id in the voxel grid, in the dataset files and in the classifier's input channels, so the order is
 * part of the weights format: append, never reorder. */
public enum Material {
    /** Nothing in the way: air, torches, rails, signs, snow dust. */
    AIR(true, "воздух"),
    /** Ordinary diggable solid: stone, dirt, sand, bricks. */
    SOLID(false, "камень"),
    /** Bedrock, obsidian, reinforced deepslate, barriers: no pick a creature could imagine gets through. */
    UNBREAKABLE(false, "бедрок"),
    /** Glass, panes, iron bars: see through, cannot pass. */
    GLASS(false, "стекло"),
    /** Water: swim, drift with the current. */
    WATER(true, "вода"),
    /** Lava: death. */
    LAVA(false, "лава"),
    /** An open door, trapdoor or gate: walk through. */
    DOOR_OPEN(true, "открытая дверь"),
    /** A closed door, trapdoor or gate: a wall for most creatures, a way out for those who open doors. */
    DOOR_CLOSED(false, "закрытая дверь"),
    /** Fences, walls, closed gates by shape: too tall to jump. */
    FENCE(false, "забор"),
    /** Ladders, vines, scaffolding: climb. */
    CLIMBABLE(true, "лестница"),
    /** Fire, magma, cactus, berry bush, dripstone: passable or not, a creature keeps off it. */
    HAZARD(false, "огонь"),
    /** Planks, logs, wooden stairs: what houses and trees are made of. */
    WOOD(false, "дерево"),
    LEAVES(false, "листва"),
    /** Crops, grass, flowers, saplings: walk through. */
    PLANT(true, "трава"),
    FARMLAND(false, "грядка"),
    /** Pistons, dispensers, hoppers, TNT, observers: somebody built a mechanism here. */
    MACHINE(false, "механизм");

    public final boolean passable;
    /** The class name a Russian prompt uses when the block itself has no better name. */
    public final String russian;
    Material(boolean passable, String russian) { this.passable = passable; this.russian = russian; }

    /** How many input channels the classifier sees: one per material. */
    public static final int COUNT = values().length;
    private static final Material[] BY_ID = values();
    public static Material of(int id) { return id < 0 || id >= BY_ID.length ? AIR : BY_ID[id]; }
}
