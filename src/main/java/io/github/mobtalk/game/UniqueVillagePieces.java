package io.github.mobtalk.game;

import io.github.mobtalk.game.mixin.SinglePoolElementAccessor;
import net.minecraft.structure.PoolStructurePiece;
import net.minecraft.structure.pool.SinglePoolElement;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.Identifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Buildings a village has at most one of (the bar; the casino may join): the jigsaw generator skips such a template once a piece
 * of it stands in the village being built ({@link io.github.mobtalk.game.mixin.UniqueVillagePieceMixin}). Register the
 * template's id before the world generates. Worldgen threads read this. */
public final class UniqueVillagePieces {
    private UniqueVillagePieces() { }
    private static final Set<Identifier> TEMPLATES = ConcurrentHashMap.newKeySet();

    public static void register(Identifier template) { TEMPLATES.add(template); }

    /** The template of a single-template element, null for any other. */
    static Identifier template(StructurePoolElement element) {
        if (!(element instanceof SinglePoolElement single)) return null;
        return ((SinglePoolElementAccessor) single).mobtalk$location().left().orElse(null);
    }

    public static boolean unique(StructurePoolElement element) {
        if (TEMPLATES.isEmpty()) return false;
        Identifier id = template(element);
        return id != null && TEMPLATES.contains(id);
    }

    /** The candidates for the next piece without the unique buildings the village already has. */
    public static List<StructurePoolElement> without(List<StructurePoolElement> candidates, List<?> placed) {
        if (TEMPLATES.isEmpty()) return candidates;
        Set<Identifier> standing = new java.util.HashSet<>();
        for (Object piece : placed)
            if (piece instanceof PoolStructurePiece pool && unique(pool.getPoolElement())) standing.add(template(pool.getPoolElement()));
        if (standing.isEmpty()) return candidates;
        List<StructurePoolElement> out = new java.util.ArrayList<>(candidates.size());
        for (StructurePoolElement element : candidates) {
            Identifier id = unique(element) ? template(element) : null;
            if (id == null || !standing.contains(id)) out.add(element);
        }
        return out;
    }
}
