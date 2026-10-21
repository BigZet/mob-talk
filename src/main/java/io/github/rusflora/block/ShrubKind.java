package io.github.rusflora.block;

/** The shrubs of the Russian middle belt, each with its own shape, season cycle and harvest. Shapes follow the references in
 * docs/references.md: hazel a tall vase of many stems, dog rose a low thorny mound, bird cherry a tall round crown on bare
 * stems, red elder a round understorey bush, juniper a dark cone or a low mat, willow a grey dome by the water, lilac a broad
 * village bush. */
public enum ShrubKind {
    HAZEL("hazel", Shape.VASE, true, "hazelnut", false),
    DOG_ROSE("dog_rose", Shape.MOUND, true, "rose_hip", true),
    BIRD_CHERRY("bird_cherry", Shape.CROWN, true, "bird_cherry", false),
    RED_ELDER("red_elder", Shape.ROUND, true, "red_elderberry", false),
    JUNIPER("juniper", Shape.CONE, false, "juniper_berry", false),
    WILLOW("willow", Shape.DOME, true, null, false),
    LILAC("lilac", Shape.BROAD, true, null, false),
    WHITE_LILAC("white_lilac", Shape.BROAD, true, null, false);

    public enum Shape { VASE, MOUND, CROWN, ROUND, CONE, DOME, BROAD }

    public final String id;
    public final Shape shape;
    /** Stage 1 shows flowers or catkins. */
    public final boolean flowers;
    /** Stage 2 carries this fruit (item id), picked with an empty hand; null for shrubs that only flower. */
    public final String fruit;
    /** Pricks whoever pushes through it. */
    public final boolean thorny;

    ShrubKind(String id, Shape shape, boolean flowers, String fruit, boolean thorny) {
        this.id = id; this.shape = shape; this.flowers = flowers; this.fruit = fruit; this.thorny = thorny;
    }

    public boolean fruits() { return fruit != null; }
}
