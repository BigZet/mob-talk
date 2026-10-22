package io.github.divineblock.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.util.math.Direction;

/** Shapes shared by the church and its yard: kokoshnik gables, tents smoothed with stairs, onion domes with ribs or spirals.
 * Everything is drawn in the church's own coordinates on a {@link ChurchBuilder.Canvas}; nothing here is random, so a shape
 * comes out the same in every chunk it is drawn for. */
final class Ornament {
    private Ornament() { }

    /** What block a dome puts at a point: dx, dz from the axis, h the layer from the bottom. */
    interface Skin { BlockState at(int dx, int dz, int h); }

    /** A kokoshnik: a keeled half-round gable standing in a vertical plane. alongX: the gable runs along x at z = plane (else
     * along z at x = plane); center and half width along it; the body fills from y0 up to the curve, the curve itself is drawn
     * in trim stairs rising to a pointed top. */
    static void kokoshnik(ChurchBuilder.Canvas canvas, boolean alongX, int plane, int center, int halfWidth, int y0, int height,
                          BlockState body, Block trim, BlockState tip) {
        for (int o = -halfWidth; o <= halfWidth; o++) {
            double t = Math.abs(o) / (halfWidth + 0.5);
            int h = (int) Math.round(height * Math.sqrt(Math.max(0, 1 - t * t)));
            if (o == 0) h = Math.max(h, height);
            for (int y = y0; y < y0 + h; y++) put(canvas, alongX, plane, center + o, y, body);
            BlockState top;
            if (o == 0) top = tip;
            else {
                Direction toCenter = alongX ? (o < 0 ? Direction.EAST : Direction.WEST) : (o < 0 ? Direction.SOUTH : Direction.NORTH);
                top = ChurchBuilder.stair(trim, toCenter, StairShape.STRAIGHT);
            }
            put(canvas, alongX, plane, center + o, y0 + h, top);
        }
    }
    private static void put(ChurchBuilder.Canvas canvas, boolean alongX, int plane, int along, int y, BlockState state) {
        if (alongX) canvas.set(along, y, plane, state); else canvas.set(plane, y, along, state);
    }

    /** A tent (or a cone) from radius r0 at y0 to r1 at y1: full blocks inside, stairs on the edge of every layer that the
     * layer above no longer covers, so the slope reads smooth. octagon: an eight-sided tent instead of a round one. */
    static void tent(ChurchBuilder.Canvas canvas, int cx, int cz, int y0, double r0, int y1, double r1, BlockState full, Block stairs, boolean octagon) {
        int span = (int) Math.ceil(Math.max(r0, r1)) + 1;
        for (int y = y0; y <= y1; y++) {
            double r = y1 == y0 ? r0 : r0 + (r1 - r0) * (y - y0) / (double) (y1 - y0);
            double above = y == y1 ? -1 : r0 + (r1 - r0) * (y + 1 - y0) / (double) (y1 - y0);
            for (int dx = -span; dx <= span; dx++)
                for (int dz = -span; dz <= span; dz++) {
                    if (!inside(dx, dz, Math.max(0.5, r), octagon)) continue;
                    boolean covered = above > 0 && inside(dx, dz, Math.max(0.5, above), octagon);
                    canvas.set(cx + dx, y, cz + dz, covered || dx == 0 && dz == 0 ? full : edgeStair(stairs, dx, dz));
                }
        }
    }
    static boolean inside(int dx, int dz, double r, boolean octagon) {
        return octagon ? ChurchBuilder.inOctagon(dx, dz, r) : Math.hypot(dx, dz) <= r + 0.15;
    }
    /** A stair on the rim of a round shape, its high side towards the middle; the four diagonals get outer corners. */
    static BlockState edgeStair(Block stairs, int dx, int dz) {
        if (Math.abs(dx) == Math.abs(dz)) {
            if (dz > 0 && dx > 0) return ChurchBuilder.stair(stairs, Direction.NORTH, StairShape.OUTER_LEFT);
            if (dz > 0) return ChurchBuilder.stair(stairs, Direction.NORTH, StairShape.OUTER_RIGHT);
            if (dx > 0) return ChurchBuilder.stair(stairs, Direction.SOUTH, StairShape.OUTER_RIGHT);
            return ChurchBuilder.stair(stairs, Direction.SOUTH, StairShape.OUTER_LEFT);
        }
        Direction toCenter = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
        return ChurchBuilder.stair(stairs, toCenter, StairShape.STRAIGHT);
    }

    /** A ring of upside-down stairs around a round shape: a cornice that steps out under whatever stands on it. */
    static void cornice(ChurchBuilder.Canvas canvas, int cx, int cz, int y, double inner, double outer, Block stairs) {
        int span = (int) Math.ceil(outer) + 1;
        for (int dx = -span; dx <= span; dx++)
            for (int dz = -span; dz <= span; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= inner + 0.15 || d > outer + 0.15) continue;
                Direction toCenter = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                canvas.set(cx + dx, y, cz + dz, ChurchBuilder.stair(stairs, toCenter, StairShape.STRAIGHT, BlockHalf.TOP));
            }
    }

    /** An onion dome on a drum of radius drum: swelling to its widest a third of the way up, then drawn in to a point. */
    static void onion(ChurchBuilder.Canvas canvas, int cx, int cz, int y0, double drum, double widest, int height, Skin skin) {
        for (int h = 0; h < height; h++) {
            double t = (h + 0.5) / height, r;
            if (t < 0.35) r = drum + (widest - drum) * Math.sin(t / 0.35 * Math.PI / 2);
            else r = widest * Math.pow(Math.cos((t - 0.35) / 0.65 * Math.PI / 2), 1.2);
            int span = (int) Math.ceil(r) + 1;
            for (int dx = -span; dx <= span; dx++)
                for (int dz = -span; dz <= span; dz++)
                    if (Math.hypot(dx, dz) <= Math.max(0.45, r + 0.2)) canvas.set(cx + dx, y0 + h, cz + dz, skin.at(dx, dz, h));
        }
    }

    /** Ribs: every block whose angle falls on one of n ribs is the rib's block, the rest the field. */
    static Skin ribs(int n, BlockState field, BlockState rib) {
        return (dx, dz, h) -> {
            if (dx == 0 && dz == 0) return field;
            double a = (Math.atan2(dz, dx) / (2 * Math.PI) + 1) * n;
            double f = a - Math.floor(a);
            return f < 0.22 || f > 0.78 ? rib : field;
        };
    }
    /** Two colours wound round the dome in n spiral bands. */
    static Skin spiral(int n, double twist, BlockState a, BlockState b) {
        return (dx, dz, h) -> {
            double turn = (Math.atan2(dz, dx) / (2 * Math.PI) + 1) * n + h * twist;
            return ((int) Math.floor(turn) & 1) == 0 ? a : b;
        };
    }
    /** One colour sown with golden stars (a fixed scatter: no randomness). */
    static Skin stars(BlockState field, BlockState star) {
        return (dx, dz, h) -> Math.floorMod(dx * 7 + dz * 13 + h * 5, 9) == 0 ? star : field;
    }
}
