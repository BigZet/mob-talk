package io.github.mobtalk.game.city;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;

/** The plan of one city (spec §6.2, stage 2): the frame of streets and the blocks between them, in the city's own coordinates
 * (x east, z south, from the centre). The streets are a function of the coordinates, asked column by column; the houses and signs
 * are laid out once. Everything comes from the city's seed, so a chunk generated today and one generated next week agree.
 *
 * <p>The frame: an avenue (проспект) north to south through the centre, a boulevard west to east, a square of 60×60 where they
 * cross, streets every 125 blocks, a ring road round the built-up square, and the avenue and the boulevard leaving the city as
 * roads to the edge of the biome (the exits). Between the streets lie the blocks (кварталы): four in the centre for the public
 * buildings (stage 4, a sign for now), a school and a park in every quarter, and the rest living blocks with 1-464 houses round a
 * yard. */
public final class CityPlan {
    public enum Surface { WILD, LAWN, ROAD, LINE_X, LINE_Z, KERB, SIDEWALK, PLAZA, ALLEY }
    public enum Kind { CENTRE, SCHOOL, PARK, LIVING }
    enum Profile { STREET, AVENUE, BOULEVARD }

    /** A street along z (north-south) at x = c, or along x at z = c, over [from, to] of the other axis. */
    record Street(boolean alongZ, int c, int half, Profile profile, int from, int to) { }
    /** A house: its template, rotation, and the footprint it covers in city coordinates (inclusive). */
    public record House(Identifier template, BlockRotation rotation, int minX, int minZ, int maxX, int maxZ, int sizeX, int sizeZ) {
        /** Where the template's origin goes (city coordinates) for the footprint to land on [min, max] once rotated. */
        public int originX() {
            return switch (rotation) { case NONE, COUNTERCLOCKWISE_90 -> minX; case CLOCKWISE_90 -> minX + sizeZ - 1; case CLOCKWISE_180 -> minX + sizeX - 1; };
        }
        public int originZ() {
            return switch (rotation) { case NONE, CLOCKWISE_90 -> minZ; case COUNTERCLOCKWISE_90 -> minZ + sizeX - 1; case CLOCKWISE_180 -> minZ + sizeZ - 1; };
        }
    }
    /** A standing sign: city coordinates, its rotation (0..15, 0 = facing south), up to four lines. */
    public record Sign(int x, int z, int rotation, String[] lines) { }
    /** A block between the streets (inclusive bounds) and what it holds. */
    public record Quarter(int x0, int z0, int x1, int z1, Kind kind, String name) { }

    public static final int SQUARE = 30, SQUARE_ROAD = 37, SQUARE_EDGE = 40;
    public static final int STEP = 125;
    /** The 1-464 template: 20 blocks a section plus the closing wall and an apron of 2 round it; 20 deep with the apron. */
    static final int SECTION = 20, DEPTH = 20, SETBACK = 3;
    static final String[] COLOURS = { "grey", "beige", "white", "blue" };
    static final String[] CENTRE_NAMES = { "Администрация", "Дом культуры", "Гостиница", "Универмаг" };

    public final CityMap.City city;
    final int ring;            // the ring road's centre line, in from the built-up square's edge
    final List<Street> streets = new ArrayList<>();
    public final List<Quarter> quarters = new ArrayList<>();
    public final List<House> houses = new ArrayList<>();
    public final List<Sign> signs = new ArrayList<>();

    CityPlan(CityMap.City city) {
        this.city = city;
        int r = city.radius, b = city.biomeRadius;
        ring = r - 6;
        List<int[]> lines = new ArrayList<>();   // {c, half} for both axes alike (the boulevard's half differs on the z axis)
        streets.add(new Street(true, 0, 12, Profile.AVENUE, -ring, ring));
        streets.add(new Street(false, 0, 16, Profile.BOULEVARD, -ring, ring));
        for (int k = 1; k * STEP <= ring - 60; k++)
            for (int sign : new int[] { -1, 1 }) {
                streets.add(new Street(true, sign * k * STEP, 6, Profile.STREET, -ring, ring));
                streets.add(new Street(false, sign * k * STEP, 6, Profile.STREET, -ring, ring));
                lines.add(new int[] { sign * k * STEP, 6 });
            }
        for (int sign : new int[] { -1, 1 }) {
            streets.add(new Street(true, sign * ring, 6, Profile.STREET, -ring - 6, ring + 6));
            streets.add(new Street(false, sign * ring, 6, Profile.STREET, -ring - 6, ring + 6));
            // The exits: the avenue and the boulevard go on as plain streets to the biome's edge.
            streets.add(new Street(true, 0, 6, Profile.STREET, sign > 0 ? ring + 7 : -b, sign > 0 ? b : -ring - 7));
            streets.add(new Street(false, 0, 6, Profile.STREET, sign > 0 ? ring + 7 : -b, sign > 0 ? b : -ring - 7));
            lines.add(new int[] { sign * ring, 6 });
        }
        List<int[]> xs = new ArrayList<>(lines), zs = new ArrayList<>(lines);
        xs.add(new int[] { 0, 12 });
        zs.add(new int[] { 0, 16 });
        xs.sort((p, q) -> Integer.compare(p[0], q[0]));
        zs.sort((p, q) -> Integer.compare(p[0], q[0]));
        layout(xs, zs);
    }

    // ------------------------------------------------------------ the ground, column by column

    /** What the ground is at a column of the city (city coordinates). WILD is outside the built-up square: the levelled margin and
     * the biome round it, left to grass. */
    public Surface surface(int x, int z) {
        int cheb = Math.max(Math.abs(x), Math.abs(z));
        if (cheb <= SQUARE_EDGE) {
            if (cheb <= SQUARE) return Surface.PLAZA;
            if (cheb <= SQUARE_ROAD) return Surface.ROAD;
            return cheb == SQUARE_ROAD + 1 ? Surface.KERB : Surface.SIDEWALK;
        }
        Surface best = null;
        int roads = 0;
        for (Street s : streets) {
            int across = s.alongZ ? x : z, along = s.alongZ ? z : x;
            int o = across - s.c;
            if (Math.abs(o) > s.half || along < s.from || along > s.to) continue;
            Surface here = profile(s, o, along);
            if (here == Surface.ROAD || here == Surface.LINE_X || here == Surface.LINE_Z) roads++;
            if (best == null || rank(here) > rank(best)) best = here;
        }
        if (best != null) return roads > 1 ? Surface.ROAD : best;
        return cheb <= ring + 6 ? Surface.LAWN : Surface.WILD;
    }

    private static int rank(Surface s) {
        return switch (s) { case ROAD, LINE_X, LINE_Z -> 5; case SIDEWALK -> 4; case KERB -> 3; case ALLEY, PLAZA -> 2; default -> 1; };
    }

    private static Surface profile(Street s, int o, int along) {
        int a = Math.abs(o);
        Surface line = s.alongZ ? Surface.LINE_Z : Surface.LINE_X;
        boolean dash = Math.floorMod(along, 6) < 3;
        return switch (s.profile) {
            // 13: pavement 2, kerb, two lanes of 3 with a dashed line, kerb, pavement 2.
            case STREET -> a >= 5 ? Surface.SIDEWALK : a == 4 ? Surface.KERB : a == 0 && dash ? line : Surface.ROAD;
            // 25: pavement 3, kerb, 7 of road (two lanes), a median of 3 with a lawn for the lindens, the same back.
            case AVENUE -> a >= 10 ? Surface.SIDEWALK : a == 9 ? Surface.KERB : a >= 2 ? (a == 5 && dash ? line : Surface.ROAD)
                    : a == 1 ? Surface.KERB : Surface.LAWN;
            // 33: pavement 2, kerb, 7 of road, kerb, then the boulevard: lawn, an alley of 3, lawn of 3 in the middle, alley, lawn.
            case BOULEVARD -> a >= 15 ? Surface.SIDEWALK : a == 14 ? Surface.KERB : a >= 7 ? (a == 10 && dash ? line : Surface.ROAD)
                    : a == 6 ? Surface.KERB : a == 5 || a <= 1 ? Surface.LAWN : Surface.ALLEY;
        };
    }

    // ------------------------------------------------------------ the blocks

    private void layout(List<int[]> xs, List<int[]> zs) {
        Random rnd = new Random(city.seed);
        List<Quarter> raw = new ArrayList<>();
        for (int i = 0; i + 1 < xs.size(); i++)
            for (int j = 0; j + 1 < zs.size(); j++) {
                int x0 = xs.get(i)[0] + xs.get(i)[1] + 1, x1 = xs.get(i + 1)[0] - xs.get(i + 1)[1] - 1;
                int z0 = zs.get(j)[0] + zs.get(j)[1] + 1, z1 = zs.get(j + 1)[0] - zs.get(j + 1)[1] - 1;
                if (x1 - x0 < 40 || z1 - z0 < 40) continue;
                raw.add(new Quarter(x0, z0, x1, z1, Kind.LIVING, null));
            }
        // The four blocks at the square hold the centre; every quarter of the city gets a school and a park among the rest.
        List<List<Integer>> quarterOf = new ArrayList<>();
        for (int q = 0; q < 4; q++) quarterOf.add(new ArrayList<>());
        for (int k = 0; k < raw.size(); k++) {
            Quarter q = raw.get(k);
            boolean nearX = q.x0 == 13 || q.x1 == -13, nearZ = q.z0 == 17 || q.z1 == -17;
            if (nearX && nearZ) continue;
            quarterOf.get((q.x0 > 0 ? 1 : 0) + (q.z0 > 0 ? 2 : 0)).add(k);
        }
        Kind[] kinds = new Kind[raw.size()];
        String[] names = new String[raw.size()];
        java.util.Arrays.fill(kinds, Kind.LIVING);
        int school = 1;
        for (int k = 0; k < raw.size(); k++) {
            Quarter q = raw.get(k);
            if ((q.x0 == 13 || q.x1 == -13) && (q.z0 == 17 || q.z1 == -17)) {
                kinds[k] = Kind.CENTRE;
                names[k] = CENTRE_NAMES[(q.x0 > 0 ? 1 : 0) + (q.z0 > 0 ? 2 : 0)];
            }
        }
        for (List<Integer> list : quarterOf) {
            if (list.size() < 3) continue;
            int s = list.remove(rnd.nextInt(list.size())), p = list.remove(rnd.nextInt(list.size()));
            kinds[s] = Kind.SCHOOL;
            names[s] = "Школа № " + school++;
            kinds[p] = Kind.PARK;
            names[p] = "Парк";
        }
        for (int k = 0; k < raw.size(); k++) {
            Quarter q = raw.get(k);
            Quarter done = new Quarter(q.x0, q.z0, q.x1, q.z1, kinds[k], names[k]);
            quarters.add(done);
            if (kinds[k] == Kind.LIVING) living(done, new Random(city.seed ^ (31L * q.x0 + 17L * q.z0)));
            else placard(done);
        }
    }

    private static int length(int sections) { return SECTION * sections + 1 + 4; }

    private Identifier template(int sections, Random rnd) {
        return Identifier.of("mobtalk", "city2/house/1-464_s" + sections + "_f5_" + COLOURS[rnd.nextInt(COLOURS.length)]);
    }

    /** A living block: a long house along the north and the south street, their entrances to the yard between, and a shorter house
     * across the yard's west end (and often the east), its entrances to the yard too. The rows stand more than twice the five
     * storeys apart (spec §6.2: insolation). */
    private void living(Quarter q, Random rnd) {
        int w = q.x1 - q.x0 + 1, d = q.z1 - q.z0 + 1;
        int fit = Math.min(6, (w - 2 * SETBACK - 5) / SECTION);
        if (fit < 2) return;
        for (int row = 0; row < 2; row++) {
            int sections = Math.max(2, fit - rnd.nextInt(2));
            int len = length(sections), x0 = q.x0 + (w - len) / 2;
            int z0 = row == 0 ? q.z0 + SETBACK : q.z1 - SETBACK - DEPTH + 1;
            houses.add(new House(template(sections, rnd), row == 0 ? BlockRotation.CLOCKWISE_180 : BlockRotation.NONE,
                    x0, z0, x0 + len - 1, z0 + DEPTH - 1, len, DEPTH));
        }
        int gap = d - 2 * (SETBACK + DEPTH) - 8;
        int across = Math.min(4, (gap - 5) / SECTION);
        if (across < 1 || w - 2 * SETBACK - 2 * DEPTH < 30) return;
        for (int side = 0; side < 2; side++) {
            if (side == 1 && rnd.nextInt(3) == 0) continue;
            int sections = across >= 2 ? Math.max(2, across - rnd.nextInt(2)) : 1;
            int len = length(sections), z0 = q.z0 + (d - len) / 2;
            int x0 = side == 0 ? q.x0 + SETBACK : q.x1 - SETBACK - DEPTH + 1;
            houses.add(new House(template(sections, rnd), side == 0 ? BlockRotation.CLOCKWISE_90 : BlockRotation.COUNTERCLOCKWISE_90,
                    x0, z0, x0 + DEPTH - 1, z0 + len - 1, len, DEPTH));
        }
    }

    /** A block left for a later stage: a sign at its corner nearest the centre, turned to the centre. */
    private void placard(Quarter q) {
        int cx = Math.abs(q.x0) < Math.abs(q.x1) ? q.x0 + 2 : q.x1 - 2;
        int cz = Math.abs(q.z0) < Math.abs(q.z1) ? q.z0 + 2 : q.z1 - 2;
        if (Math.max(Math.abs(cx), Math.abs(cz)) <= SQUARE_EDGE + 2) cx = Integer.signum(cx) * (SQUARE_EDGE + 3);   // not on the square
        int dx = cx < 0 ? 1 : -1, dz = cz < 0 ? 1 : -1;   // towards the centre
        int rotation = dz > 0 ? (dx < 0 ? 2 : 14) : (dx < 0 ? 6 : 10);
        String size = q.kind == Kind.CENTRE ? "участок XL" : q.kind == Kind.SCHOOL ? "школа и стадион" : "деревья, клумбы";
        signs.add(new Sign(cx, cz, rotation, new String[] { "", q.name, "(" + size + ")", "строится" }));
    }
}
