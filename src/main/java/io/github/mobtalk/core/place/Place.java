package io.github.mobtalk.core.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The shape of the place a creature stands in, worked out from a {@link VoxelGrid} by flooding the cells its body could reach
 * on foot (step up one, drop up to three, swim, climb): how big the reachable volume is, where it leaks out of the box (the exits),
 * what its walls are made of, whether the sky is overhead, where the water pulls, whether the walls narrow to a hole, and where the
 * player is relative to it. Pure Java, no Minecraft: the same code runs on generated scenes in tests. */
public final class Place {
    /** Cells the flood may visit before the place counts as open. */
    public static final int LIMIT = 2000;
    /** Horizontal reach of the flood; a cell this far from the creature counts as a way out. */
    public static final int RADIUS = 12;
    /** The longest drop a creature takes on purpose (vanilla path finding stops at three). */
    public static final int MAX_FALL = 3;
    /** Wall materials this common are what the place is "made of". */
    static final double DOMINANT_SHARE = 0.35;

    /** What kind of place the flood found. */
    public enum Kind {
        OPEN("открытое место"), PIT("яма"), CHAMBER("закрытая камера"), FUNNEL("воронка"), CAGE("клетка"), PEN("загон"),
        ROOM("помещение"), CORRIDOR("коридор"), DEAD_END("тупик"), HEIGHT("возвышение"), WATER("вода");
        public final String russian;
        Kind(String russian) { this.russian = russian; }
    }

    /** The creature's body and the player's whereabouts. {@code headroom} is the cells its body needs (1 for a chicken, 2 for a cow);
     * {@code opensDoors} whether a closed door is a way out for it; the player's feet are relative to the creature's, or
     * {@code playerKnown} false when out of reach; {@code changeMinutes} is how old the oldest remembered player change is. */
    public record Options(int headroom, boolean opensDoors, boolean playerKnown, int playerX, int playerY, int playerZ, int changeMinutes) {
        public static final Options DEFAULT = new Options(2, false, false, 0, 0, 0, 0);
        public Options player(int x, int y, int z) { return new Options(headroom, opensDoors, true, x, y, z, changeMinutes); }
        public Options doors(boolean opens) { return new Options(headroom, opensDoors || opens, playerKnown, playerX, playerY, playerZ, changeMinutes); }
        public Options headroom(int cells) { return new Options(cells, opensDoors, playerKnown, playerX, playerY, playerZ, changeMinutes); }
        public Options changes(int minutes) { return new Options(headroom, opensDoors, playerKnown, playerX, playerY, playerZ, minutes); }
    }

    /** Everything the flood learned, in numbers; {@link #summary()} says it in Russian for the prompt. */
    public static final class Report {
        public Kind kind = Kind.OPEN;
        public int cells, width, length, height, minY, maxY, ceiling, depth;
        /** The flood ran out of its cell budget: the place goes on beyond what was looked at. */
        public boolean limitHit;
        public final Set<String> exits = new LinkedHashSet<>();
        /** Closed doors, trapdoors and gates at the edge of the reachable volume, by direction. */
        public final Set<String> doors = new LinkedHashSet<>();
        /** Open ones: a way out for anybody. */
        public final Set<String> openDoors = new LinkedHashSet<>();
        public boolean enclosed, trapped, skyOpen, seesSky, inWater, funnel;
        public int skyCells, waterCells, ledges, minDrop = Integer.MAX_VALUE, lavaNear;
        public final Map<Material, Integer> walls = new EnumMap<>(Material.class);
        public final Map<String, Integer> wallBlocks = new LinkedHashMap<>();
        public int wallCount;
        public float flowX, flowY, flowZ;
        /** What the lowest reachable point leads to: floor, water, hole, lava, out. */
        public String bottom = "floor";
        public boolean playerKnown, playerInside, playerAbove, playerBelow;
        /** How the creature sees the player: direct, glass, none. */
        public String playerSight = "none";
        public int placedByPlayer, brokenByPlayer, changeMinutes;
        public int originY;
        /** The reachable cells, by grid index; for tests and the player check. */
        public BitSet region = new BitSet();

        public double share(Material material) { return wallCount == 0 ? 0 : walls.getOrDefault(material, 0) / (double) wallCount; }
        /** The block most of the boundary is made of. */
        public String dominantBlock() {
            String best = null; int count = 0;
            for (var entry : wallBlocks.entrySet()) if (entry.getValue() > count) { best = entry.getKey(); count = entry.getValue(); }
            return best == null ? "minecraft:stone" : best;
        }
        public Set<String> horizontalExits() {
            Set<String> out = new LinkedHashSet<>(exits); out.remove("вверх"); out.remove("вниз"); return out;
        }

        /** The facts in words, shortest first: the kind and size, then what bounds it, the way out, the sky, the water, the player,
         * and who dug or built it. */
        public String summary() {
            StringBuilder text = new StringBuilder();
            String made = BlockNames.russian(dominantBlock());
            boolean solidWalls = share(Material.UNBREAKABLE) >= DOMINANT_SHARE;
            String digText = solidWalls ? "не прокопать" : share(Material.GLASS) >= DOMINANT_SHARE ? "видно насквозь, не пройти"
                    : share(Material.FENCE) >= DOMINANT_SHARE ? "не перепрыгнуть" : "можно прокопать, если умеешь";
            switch (kind) {
                case PIT -> text.append("Ты на дне ямы ").append(width).append('×').append(length).append(" глубиной ").append(Math.max(2, depth))
                        .append("; стены из ").append(made).append(" (").append(digText).append("), наверху ").append(seesSky ? "небо" : "просвет").append('.');
                case CHAMBER -> text.append(corridorShape() ? "Ты в узкой закрытой камере " : "Ты в закрытой камере ").append(width).append('×').append(length).append('×').append(Math.max(1, ceiling))
                        .append(" из ").append(made).append(": ").append(digText).append(", неба не видно.");
                case FUNNEL -> text.append("Ты в воронке: стены сужаются книзу").append(waterCells > 0 ? ", вода тащит вниз" : "")
                        .append(", на дне ").append(switch (bottom) { case "lava" -> "лава"; case "hole" -> "дыра"; case "out" -> "дыра, ведёт ниже"; default -> "глухо"; })
                        .append("; стены из ").append(made).append(" (").append(digText).append(").");
                case CAGE -> text.append("Ты в клетке ").append(width).append('×').append(length).append('×').append(Math.max(1, ceiling)).append(" из ")
                        .append(made).append(": всё видно насквозь, но не выйти").append(doorText(", есть дверь: ")).append('.');
                case PEN -> text.append("Ты в загоне ").append(width).append('×').append(length).append(" за забором (").append(made).append(", не перепрыгнуть)")
                        .append(doors.isEmpty() && openDoors.isEmpty() ? ", калитки нет" : doorText(", калитка: ")).append('.');
                case ROOM -> text.append("Ты в помещении ").append(width).append('×').append(length).append(" из ").append(made)
                        .append(exits.isEmpty() ? "" : ", проход: " + String.join(", ", exits)).append(doorText(", дверь: ")).append('.');
                case CORRIDOR -> text.append("Ты в коридоре шириной ").append(Math.min(width, length)).append(" из ").append(made).append(", выход: ")
                        .append(String.join(", ", exits)).append(doorText(", дверь: ")).append('.');
                case DEAD_END -> text.append("Ты в тупике: узкий ход ").append(Math.min(width, length)).append('×').append(Math.max(width, length)).append(" из ").append(made)
                        .append(", единственный выход: ").append(String.join(", ", exits)).append(doorText(", дверь: ")).append(" (").append(digText).append(").");
                case HEIGHT -> text.append("Ты на возвышении ").append(width).append('×').append(length).append(": вокруг обрыв")
                        .append(minDrop == Integer.MAX_VALUE ? "" : " в " + minDrop + " " + plural(minDrop, "блок", "блока", "блоков"))
                        .append(", спускаться некуда").append(exits.isEmpty() ? "." : ", кроме как на " + String.join(", ", exits) + ".");
                case WATER -> text.append("Ты в воде").append(enclosed ? ", берега не выбраться: " + made + " (" + digText + ")" : ", выбраться можно: " + String.join(", ", exits)).append('.');
                case OPEN -> text.append("Ты на открытом месте, ").append(limitHit && exits.size() >= 3 ? "идти можно куда угодно" : "выходы: " + String.join(", ", exits)).append('.');
            }
            if (kind != Kind.PIT && kind != Kind.CHAMBER && kind != Kind.OPEN) {
                if (!seesSky) text.append(" Неба не видно.");
                else if (!skyOpen) text.append(" Небо видно только через стекло.");
            }
            if (kind == Kind.PIT && !skyOpen && seesSky) text.append(" Сверху стекло.");
            if (lavaNear > 0 && kind != Kind.FUNNEL) text.append(" Рядом лава: один неверный шаг — смерть.");
            if (waterCells > 0 && kind != Kind.FUNNEL && kind != Kind.WATER) text.append(inWater ? " Ты стоишь в воде." : " Здесь есть вода.");
            String current = flowText();
            if (current != null) text.append(' ').append(current);
            if (kind != Kind.FUNNEL && "hole".equals(bottom)) text.append(" Внизу дыра: ниже провал.");
            if (trapped) text.append(" Выхода нет: ты заперт.");
            else if (exits.isEmpty() && kind != Kind.ROOM && kind != Kind.CAGE && kind != Kind.PEN && (!doors.isEmpty() || !openDoors.isEmpty())) text.append(" Единственный выход — дверь (").append(doorText("")).append(").");
            text.append(player());
            text.append(builder());
            return text.toString();
        }

        boolean corridorShape() { return Math.min(width, length) <= 2 && Math.max(width, length) >= 5; }
        /** "дверь: восток (открыта), юг (закрыта)" after the given lead-in, or nothing when there is no door. */
        String doorText(String lead) {
            if (doors.isEmpty() && openDoors.isEmpty()) return "";
            List<String> parts = new ArrayList<>();
            for (String d : openDoors) parts.add(d + " (открыта)");
            for (String d : doors) parts.add(d + " (закрыта)");
            return lead + String.join(", ", parts);
        }
        String flowText() {
            double horizontal = Math.hypot(flowX, flowZ);
            if (waterCells == 0 || horizontal < 0.3 && flowY > -0.5) return null;
            if (flowY <= -0.5 && horizontal < 0.3) return "Течение тянет вниз.";
            String dir = Math.abs(flowX) > Math.abs(flowZ) ? flowX > 0 ? "восток" : "запад" : flowZ > 0 ? "юг" : "север";
            return "Течение несёт на " + dir + (flowY <= -0.5 ? " и вниз." : ".");
        }
        String player() {
            if (!playerKnown) return " Игрока рядом нет.";
            StringBuilder text = new StringBuilder(" Игрок ");
            text.append(playerInside ? (enclosed ? "здесь же, внутри, заперт вместе с тобой" : "тут же, рядом") : enclosed || kind == Kind.ROOM || kind == Kind.PIT ? "снаружи" : "в стороне");
            if (playerAbove) text.append(", выше тебя"); else if (playerBelow) text.append(", ниже тебя");
            text.append(switch (playerSight) {
                case "glass" -> ", видно через стекло";
                case "direct" -> playerInside ? "" : ", видно через проём";
                default -> ", не видно";
            });
            return text.append('.').toString();
        }
        String builder() {
            if (placedByPlayer == 0 && brokenByPlayer == 0) return enclosed ? " Следов игрока в стенах нет: похоже, ты сам сюда провалился или забрёл." : "";
            StringBuilder text = new StringBuilder(" Из блоков вокруг тебя ");
            if (placedByPlayer > 0) text.append(placedByPlayer).append(' ').append(plural(placedByPlayer, "поставил", "поставил", "поставил")).append(" игрок");
            if (placedByPlayer > 0 && brokenByPlayer > 0) text.append(" и ");
            if (brokenByPlayer > 0) text.append(brokenByPlayer).append(" выкопал").append(placedByPlayer > 0 ? "" : " игрок");
            text.append(" за последние ").append(Math.max(1, changeMinutes)).append(' ').append(plural(Math.max(1, changeMinutes), "минуту", "минуты", "минут")).append('.');
            if (enclosed && placedByPlayer >= brokenByPlayer && placedByPlayer >= 2) text.append(" Это он тебя замуровал.");
            else if (enclosed && brokenByPlayer >= 2) text.append(" Это он тебя окопал: яму вырыл он.");
            return text.toString();
        }

        /** One short line for the fight estimate: what shuts the creature in. */
        public String escape() {
            String made = BlockNames.russian(dominantBlock());
            return switch (kind) {
                case PIT -> "яма " + width + "×" + length + " глубиной " + Math.max(2, depth) + " из " + made + (share(Material.UNBREAKABLE) >= DOMINANT_SHARE ? ", не прокопать" : "");
                case CHAMBER -> "закрытая камера из " + made + (share(Material.UNBREAKABLE) >= DOMINANT_SHARE ? ", не прокопать" : "");
                case FUNNEL -> "воронка, на дне " + ("lava".equals(bottom) ? "лава" : "дыра");
                case CAGE -> "клетка из " + made + ", видно насквозь";
                case PEN -> "загон за забором";
                case DEAD_END -> "тупик";
                case HEIGHT -> "обрыв со всех сторон";
                case WATER -> "вода, берега не выбраться";
                default -> "выхода нет";
            };
        }
    }

    private Place() { }

    public static String plural(int count, String one, String few, String many) {
        int ten = count % 10, hundred = count % 100;
        if (ten == 1 && hundred != 11) return one;
        if (ten >= 2 && ten <= 4 && (hundred < 12 || hundred > 14)) return few;
        return many;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final String[] DIR_NAMES = {"восток", "запад", "юг", "север"};

    public static Report analyse(VoxelGrid grid, Options options) {
        int R = Math.min(RADIUS, grid.radiusXZ), RY = grid.radiusY, head = Math.max(1, options.headroom());
        Report report = new Report();
        report.playerKnown = options.playerKnown(); report.changeMinutes = options.changeMinutes();
        int startY = 0;
        if (!valid(grid, 0, 0, 0, head)) startY = valid(grid, 0, 1, 0, head) ? 1 : valid(grid, 0, -1, 0, head) ? -1 : Integer.MIN_VALUE;
        if (startY == Integer.MIN_VALUE) {
            // Buried: no room even for its own body. Say so and stop.
            report.kind = Kind.CHAMBER; report.enclosed = report.trapped = true; report.width = report.length = report.ceiling = 1;
            tallyWall(report, grid, 1, 0, 0); tallyWall(report, grid, -1, 0, 0); tallyWall(report, grid, 0, 0, 1); tallyWall(report, grid, 0, 0, -1);
            report.playerInside = false; sight(grid, report, options, 0);
            return report;
        }
        report.originY = startY;
        report.inWater = grid.material(0, startY, 0) == Material.WATER;
        BitSet visited = new BitSet();
        int[] queue = new int[LIMIT + 8 * LIMIT];
        int[] xs = new int[queue.length], ys = new int[queue.length], zs = new int[queue.length];
        int qHead = 0, qTail = 0;
        int start = grid.index(0, startY, 0);
        visited.set(start); xs[qTail] = 0; ys[qTail] = startY; zs[qTail] = 0; queue[qTail++] = start;
        int minX = 0, maxX = 0, minZ = 0, maxZ = 0, minY = startY, maxY = startY;
        int[] areaByY = new int[grid.sizeY];
        int ceiling = Integer.MAX_VALUE, depth = Integer.MAX_VALUE;
        int bottomLava = 0, bottomHole = 0, bottomOut = 0;
        Set<String> ledgeDirs = new LinkedHashSet<>();
        BitSet boundary = new BitSet(), broken = new BitSet();
        while (qHead < qTail && report.cells < LIMIT) {
            int x = xs[qHead], y = ys[qHead], z = zs[qHead]; qHead++;
            report.cells++;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            areaByY[y + RY]++;
            Material here = grid.material(x, y, z);
            boolean swimming = here == Material.WATER, climbing = here == Material.CLIMBABLE;
            if (swimming) {
                report.waterCells++;
                float[] flow = grid.flow(x, y, z);
                if (flow != null) { report.flowX += flow[0]; report.flowY += flow[1]; report.flowZ += flow[2]; }
            }
            for (int dy = -1; dy <= 1; dy++) if (grid.changed(x, y + dy, z) == VoxelGrid.BROKEN && !broken.get(grid.index(x, y + dy, z))) { broken.set(grid.index(x, y + dy, z)); report.brokenByPlayer++; }
            // Exits: the flood reached the edge of what it may look at.
            if (Math.abs(x) >= R || Math.abs(z) >= R || Math.abs(y) >= RY) {
                report.exits.add(Math.abs(x) >= R ? (x > 0 ? "восток" : "запад") : Math.abs(z) >= R ? (z > 0 ? "юг" : "север") : y > 0 ? "вверх" : "вниз");
                if (y <= -RY) bottomOut++;
                continue;
            }
            // Sky: a clear column up to the top of the box and open above it.
            int skyAt = skyColumn(grid, x, y, z);
            if (skyAt == 2) { report.skyCells++; report.skyOpen = true; report.seesSky = true; }
            else if (skyAt == 1) report.seesSky = true;
            // Ceiling and floor.
            int up = 1;
            while (y + up <= RY && grid.passable(x, y + up, z)) up++;
            if (y + up <= RY) { ceiling = Math.min(ceiling, up); tallyBoundary(report, boundary, grid, x, y + up, z); }
            if (!grid.passable(x, y - 1, z)) {
                tallyBoundary(report, boundary, grid, x, y - 1, z);
                if (grid.material(x, y - 1, z) == Material.LAVA) report.lavaNear++;
            }
            if (!swimming && !climbing && !supported(grid, x, y, z) && y - 1 >= -RY) {
                // Hanging in the air, as after swimming down into a shaft: it falls from here.
                int drop = 1; boolean landed = false;
                while (y - drop >= -RY) {
                    Material cell = grid.material(x, y - drop, z);
                    if (!cell.passable) { if (cell == Material.LAVA) { report.lavaNear++; bottomLava++; } break; }
                    if (cell == Material.WATER || valid(grid, x, y - drop, z, head) && supported(grid, x, y - drop, z)) {
                        landed = true;
                        int i = grid.index(x, y - drop, z);
                        if (!visited.get(i)) { visited.set(i); xs[qTail] = x; ys[qTail] = y - drop; zs[qTail] = z; queue[qTail++] = i; }
                        break;
                    }
                    drop++;
                }
                if (!landed && y - drop < -RY) bottomOut++;
                continue;
            }
            // Vertical moves while swimming or climbing.
            if (swimming || climbing) {
                if (valid(grid, x, y + 1, z, head) && (!swimming || withCurrent(grid, x, y, z, 0, 1, 0)) && !visited.get(grid.index(x, y + 1, z))) { int i = grid.index(x, y + 1, z); visited.set(i); xs[qTail] = x; ys[qTail] = y + 1; zs[qTail] = z; queue[qTail++] = i; }
                if (y - 1 >= -RY && valid(grid, x, y - 1, z, head) && !visited.get(grid.index(x, y - 1, z))) { int i = grid.index(x, y - 1, z); visited.set(i); xs[qTail] = x; ys[qTail] = y - 1; zs[qTail] = z; queue[qTail++] = i; }
            }
            for (int d = 0; d < 4; d++) {
                int nx = x + DIRS[d][0], nz = z + DIRS[d][1];
                Material side = grid.material(nx, y, nz);
                if (side == Material.DOOR_OPEN || side == Material.DOOR_CLOSED) {
                    // A doorway bounds the room: the room is what the flood measures, the door is its exit.
                    if (side == Material.DOOR_OPEN) report.openDoors.add(DIR_NAMES[d]); else report.doors.add(DIR_NAMES[d]);
                    tallyBoundary(report, boundary, grid, nx, y, nz);
                    continue;
                }
                if (swimming && !withCurrent(grid, x, y, z, DIRS[d][0], 0, DIRS[d][1])) continue;
                if (!side.passable) {
                    tallyBoundary(report, boundary, grid, nx, y, nz);
                    if (side == Material.LAVA) report.lavaNear++;
                    if (side == Material.DOOR_CLOSED) report.doors.add(DIR_NAMES[d]);
                    else {
                        // How tall this wall is from here: the pit's depth is its lowest wall.
                        int h = 0;
                        while (y + h <= RY && !grid.passable(nx, y + h, nz)) h++;
                        if (h >= 2) depth = Math.min(depth, h);
                    }
                } else {
                    boolean entered = false;
                    if (valid(grid, nx, y, nz, head)) {
                        if (supported(grid, nx, y, nz)) { entered = true; int i = grid.index(nx, y, nz); if (!visited.get(i)) { visited.set(i); xs[qTail] = nx; ys[qTail] = y; zs[qTail] = nz; queue[qTail++] = i; } }
                    } else if (head >= 2) tallyBoundary(report, boundary, grid, nx, y + 1, nz);
                    if (!entered) {
                        // A drop: fall until something holds, water catches, or it is too deep to choose.
                        int drop = 1; boolean landed = false;
                        while (y - drop >= -RY) {
                            Material cell = grid.material(nx, y - drop, nz);
                            if (!cell.passable) { if (cell == Material.LAVA) { report.lavaNear++; if (y - drop <= minY) bottomLava++; } break; }
                            if (cell == Material.WATER || valid(grid, nx, y - drop, nz, head) && supported(grid, nx, y - drop, nz)) {
                                landed = true;
                                if (drop <= MAX_FALL || cell == Material.WATER) { int i = grid.index(nx, y - drop, nz); if (!visited.get(i)) { visited.set(i); xs[qTail] = nx; ys[qTail] = y - drop; zs[qTail] = nz; queue[qTail++] = i; } }
                                else { report.ledges++; ledgeDirs.add(DIR_NAMES[d]); report.minDrop = Math.min(report.minDrop, drop); if (y <= minY + 1) bottomHole++; }
                                break;
                            }
                            drop++;
                        }
                        if (!landed && y - drop < -RY) { report.ledges++; ledgeDirs.add(DIR_NAMES[d]); report.minDrop = Math.min(report.minDrop, drop); bottomOut++; }
                    }
                }
                // Step up: one block, with room to jump.
                if (valid(grid, nx, y + 1, nz, head) && supported(grid, nx, y + 1, nz) && grid.passable(x, y + head, z)
                        && (!swimming || withCurrent(grid, x, y, z, DIRS[d][0], 1, DIRS[d][1]))) {
                    int i = grid.index(nx, y + 1, nz);
                    if (!visited.get(i)) { visited.set(i); xs[qTail] = nx; ys[qTail] = y + 1; zs[qTail] = nz; queue[qTail++] = i; }
                }
            }
        }
        report.limitHit = qHead < qTail;
        report.region = visited;
        report.width = maxX - minX + 1; report.length = maxZ - minZ + 1; report.height = maxY - minY + 1;
        report.minY = minY; report.maxY = maxY;
        report.ceiling = ceiling == Integer.MAX_VALUE ? RY : ceiling;
        report.depth = depth == Integer.MAX_VALUE ? 0 : depth;
        for (int i = boundary.nextSetBit(0); i >= 0; i = boundary.nextSetBit(i + 1)) {
            int[] at = unindex(grid, i);
            if (grid.changed(at[0], at[1], at[2]) == VoxelGrid.PLACED) report.placedByPlayer++;
        }
        // Where the lowest point leads.
        report.bottom = bottomLava > 0 ? "lava" : bottomOut > 0 ? "out" : bottomHole > 0 ? "hole" : grid.material(0, minY, 0) == Material.WATER && areaByY[minY + RY] > 0 ? "water" : "floor";
        // Narrowing to the bottom: a funnel, read off the bowl of open space around the creature, not only off what it can reach.
        String bowlBottom = bowl(grid, startY, report);
        if (report.funnel && "floor".equals(report.bottom)) report.bottom = bowlBottom;
        // The kind.
        Set<String> horizontal = report.horizontalExits();
        boolean corridor = Math.min(report.width, report.length) <= 2 && Math.max(report.width, report.length) >= 5;
        // Falling out of the bottom of the box is no way out: a hole the water sweeps it into is still a trap.
        boolean closed = horizontal.isEmpty() && !report.exits.contains("вверх") && !report.limitHit && report.openDoors.isEmpty();
        report.enclosed = closed && (report.doors.isEmpty() || !options.opensDoors());
        boolean fenced = report.share(Material.FENCE) >= DOMINANT_SHARE, glazed = report.share(Material.GLASS) >= DOMINANT_SHARE;
        if (report.limitHit || horizontal.size() >= 3) report.kind = report.inWater ? Kind.WATER : Kind.OPEN;
        else if (closed) {
            if (fenced) report.kind = Kind.PEN;
            else if (glazed) report.kind = Kind.CAGE;
            else if (report.funnel) report.kind = Kind.FUNNEL;
            else if (!report.doors.isEmpty() && !report.skyOpen) report.kind = Kind.ROOM;
            else if (ledgeDirs.size() >= 2 && report.wallCount <= report.cells * 2 && report.skyOpen) report.kind = Kind.HEIGHT;
            else if (report.inWater && report.waterCells * 2 >= report.cells) report.kind = Kind.WATER;
            else if (report.skyOpen) report.kind = Kind.PIT;
            else report.kind = Kind.CHAMBER;
        } else {
            if (fenced) report.kind = Kind.PEN;
            else if (glazed) report.kind = Kind.CAGE;
            else if (report.inWater && report.waterCells * 2 >= report.cells) report.kind = Kind.WATER;
            else if (corridor) report.kind = horizontal.size() + report.openDoors.size() == 1 ? Kind.DEAD_END : Kind.CORRIDOR;
            else if (report.skyCells * 2 >= report.cells && report.openDoors.isEmpty()) report.kind = Kind.OPEN;
            else report.kind = Kind.ROOM;
        }
        report.trapped = report.enclosed && (report.kind != Kind.HEIGHT || report.minDrop > 5);
        if (report.kind == Kind.PIT && report.depth == 0) report.depth = Math.max(2, report.ceiling);
        // The player.
        if (options.playerKnown()) {
            int px = options.playerX(), py = options.playerY(), pz = options.playerZ();
            int at = grid.index(px, py, pz), below = grid.index(px, py - 1, pz), above = grid.index(px, py + 1, pz);
            report.playerInside = at >= 0 && visited.get(at) || below >= 0 && visited.get(below) || above >= 0 && visited.get(above);
            report.playerAbove = py > startY + 1; report.playerBelow = py < startY - 1;
            sight(grid, report, options, startY);
        }
        return report;
    }

    /** Cells of open space the flood may cover at one level before the level counts as open ground. */
    static final int LEVEL_LIMIT = 400;

    /** The bowl of open space through the creature's level: each level's horizontally connected open area, grown upward and
     * downward from the level the creature stands on. A funnel is a bowl that narrows steadily to a few cells and ends in a
     * hole, lava or water. Returns what its lowest level stands on: floor, lava, out, water. */
    static String bowl(VoxelGrid grid, int startY, Report report) {
        int RY = grid.radiusY, R = Math.min(RADIUS, grid.radiusXZ);
        int levels = 2 * RY + 1;
        int[] area = new int[levels];
        BitSet[] cells = new BitSet[levels];
        int size = grid.sizeXZ * grid.sizeXZ;
        cells[startY + RY] = level(grid, startY, R, java.util.List.of(grid.column(0, 0)), null);
        area[startY + RY] = cells[startY + RY].cardinality();
        int top = startY, bottom = startY;
        for (int y = startY + 1; y <= RY && area[y - 1 + RY] > 0 && area[y - 1 + RY] < LEVEL_LIMIT; y++) {
            cells[y + RY] = level(grid, y, R, null, cells[y - 1 + RY]);
            area[y + RY] = cells[y + RY].cardinality();
            // A level that runs out to open ground is the rim, not part of the bowl.
            if (area[y + RY] == 0 || area[y + RY] >= LEVEL_LIMIT) break;
            top = y;
        }
        for (int y = startY - 1; y >= -RY && area[y + 1 + RY] > 0; y--) {
            cells[y + RY] = level(grid, y, R, null, cells[y + 1 + RY]);
            area[y + RY] = cells[y + RY].cardinality();
            if (area[y + RY] == 0) break;
            bottom = y;
        }
        // What the lowest level stands on.
        String stands = "floor";
        BitSet lowest = cells[bottom + RY];
        boolean water = false;
        for (int c = lowest.nextSetBit(0); c >= 0; c = lowest.nextSetBit(c + 1)) {
            int x = c / grid.sizeXZ - grid.radiusXZ, z = c % grid.sizeXZ - grid.radiusXZ;
            if (grid.material(x, bottom, z) == Material.WATER) water = true;
            Material below = grid.material(x, bottom - 1, z);
            if (below == Material.LAVA) stands = "lava";
            else if (bottom - 1 < -RY && !"lava".equals(stands)) stands = "out";
        }
        if ("floor".equals(stands) && water) stands = "water";
        int steps = 0, narrowing = 0, widest = 0;
        for (int y = top; y >= bottom; y--) { widest = Math.max(widest, Math.min(area[y + RY], LEVEL_LIMIT)); }
        for (int y = top; y > bottom; y--) { steps++; if (area[y + RY] >= area[y - 1 + RY]) narrowing++; }
        int low = area[bottom + RY];
        boolean wet = false;
        for (int y = top; y >= bottom; y--) { BitSet set = cells[y + RY]; for (int c = set.nextSetBit(0); c >= 0; c = set.nextSetBit(c + 1)) {
            if (grid.material(c / grid.sizeXZ - grid.radiusXZ, y, c % grid.sizeXZ - grid.radiusXZ) == Material.WATER) { wet = true; break; } } if (wet) break; }
        report.funnel = top - bottom >= 3 && low <= 4 && widest >= 3 * Math.max(1, low) && widest < LEVEL_LIMIT && narrowing * 10 >= steps * 7
                && (wet || "lava".equals(stands) || "out".equals(stands));
        if (wet && report.waterCells == 0) report.waterCells = 1;
        return stands;
    }
    /** One level of the bowl: the passable cells at height {@code y} horizontally connected to the seeds (given columns, or
     * the cells of the level next to it), capped at {@link #LEVEL_LIMIT}. */
    private static BitSet level(VoxelGrid grid, int y, int R, java.util.List<Integer> seeds, BitSet neighbourLevel) {
        BitSet set = new BitSet();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        if (seeds != null) for (int c : seeds) { int x = c / grid.sizeXZ - grid.radiusXZ, z = c % grid.sizeXZ - grid.radiusXZ; if (grid.passable(x, y, z)) { set.set(c); queue.add(c); } }
        if (neighbourLevel != null) for (int c = neighbourLevel.nextSetBit(0); c >= 0; c = neighbourLevel.nextSetBit(c + 1)) {
            int x = c / grid.sizeXZ - grid.radiusXZ, z = c % grid.sizeXZ - grid.radiusXZ;
            if (grid.passable(x, y, z) && !set.get(c)) { set.set(c); queue.add(c); }
        }
        while (!queue.isEmpty() && set.cardinality() < LEVEL_LIMIT) {
            int c = queue.poll();
            int x = c / grid.sizeXZ - grid.radiusXZ, z = c % grid.sizeXZ - grid.radiusXZ;
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (Math.abs(nx) > R || Math.abs(nz) > R || !grid.passable(nx, y, nz)) continue;
                int n = grid.column(nx, nz);
                if (!set.get(n)) { set.set(n); queue.add(n); }
            }
        }
        return set;
    }

    private static boolean valid(VoxelGrid grid, int x, int y, int z, int head) {
        if (!grid.inside(x, y, z) || !grid.passable(x, y, z)) return false;
        for (int h = 1; h < head; h++) if (!grid.passable(x, y + h, z) && grid.inside(x, y + h, z)) return false;
        return true;
    }
    /** In moving water a body goes only where the water lets it: never straight against the current. */
    private static boolean withCurrent(VoxelGrid grid, int x, int y, int z, int dx, int dy, int dz) {
        float[] flow = grid.flow(x, y, z);
        if (flow == null) return true;
        // Water pulling down lets nothing rise; sideways, nothing goes straight against it.
        if (dy > 0 && flow[1] <= -0.3) return false;
        double sideways = Math.hypot(flow[0], flow[2]);
        if (sideways < 0.2 || dx == 0 && dz == 0) return true;
        return (flow[0] * dx + flow[2] * dz) / sideways > -0.3;
    }
    /** Something holds the body there: ground below, water around or below, or a ladder. */
    private static boolean supported(VoxelGrid grid, int x, int y, int z) {
        Material here = grid.material(x, y, z);
        if (here == Material.WATER || here == Material.CLIMBABLE) return true;
        if (!grid.inside(x, y - 1, z)) return true;
        Material below = grid.material(x, y - 1, z);
        // Lava is no floor: a body that steps over it sinks in.
        return !below.passable && below != Material.LAVA || below == Material.WATER || below == Material.CLIMBABLE;
    }
    /** 2: a clear column to the sky; 1: sky through glass; 0: roofed. */
    private static int skyColumn(VoxelGrid grid, int x, int y, int z) {
        boolean glass = false;
        for (int h = y + 1; h <= grid.radiusY; h++) {
            Material m = grid.material(x, h, z);
            if (m == Material.GLASS) glass = true;
            else if (!m.passable) return 0;
        }
        if (!grid.openAbove(x, z)) return 0;
        return glass ? 1 : 2;
    }
    private static void tallyBoundary(Report report, BitSet boundary, VoxelGrid grid, int x, int y, int z) {
        int i = grid.index(x, y, z);
        if (i < 0 || boundary.get(i)) return;
        boundary.set(i);
        tallyWall(report, grid, x, y, z);
    }
    private static void tallyWall(Report report, VoxelGrid grid, int x, int y, int z) {
        Material m = grid.material(x, y, z);
        if (m.passable) return;
        report.wallCount++;
        report.walls.merge(m, 1, Integer::sum);
        report.wallBlocks.merge(grid.block(x, y, z), 1, Integer::sum);
    }
    private static int[] unindex(VoxelGrid grid, int index) {
        int z = index % grid.sizeXZ - grid.radiusXZ;
        int rest = index / grid.sizeXZ;
        int y = rest % grid.sizeY - grid.radiusY;
        int x = rest / grid.sizeY - grid.radiusXZ;
        return new int[]{x, y, z};
    }
    /** A ray from the creature's eye to the player's: clear, through glass, or blocked. */
    private static void sight(VoxelGrid grid, Report report, Options options, int startY) {
        if (!options.playerKnown()) { report.playerSight = "none"; return; }
        double x0 = 0.5, y0 = startY + 1.5, z0 = 0.5, x1 = options.playerX() + 0.5, y1 = options.playerY() + 1.5, z1 = options.playerZ() + 0.5;
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0, dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist * 3));
        boolean glass = false;
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            int cx = (int) Math.floor(x0 + dx * t), cy = (int) Math.floor(y0 + dy * t), cz = (int) Math.floor(z0 + dz * t);
            if (!grid.inside(cx, cy, cz)) break;
            Material m = grid.material(cx, cy, cz);
            if (m == Material.GLASS) glass = true;
            else if (!m.passable && m != Material.WATER) { report.playerSight = "none"; return; }
        }
        report.playerSight = glass ? "glass" : "direct";
    }

    /** A horizontal 9×9 slice at the creature's feet and a vertical north–south section, for a prompt experiment or a log:
     * '.' air, '#' solid, 'B' unbreakable, 'G' glass, '~' water, 'L' lava, 'D' door, 'F' fence, '@' the creature. */
    public static List<String> ascii(VoxelGrid grid, int originY) {
        List<String> lines = new ArrayList<>();
        lines.add("горизонтальный срез на уровне ног (север вверху, восток справа):");
        for (int z = -4; z <= 4; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = -4; x <= 4; x++) row.append(x == 0 && z == 0 ? '@' : symbol(grid.material(x, originY, z)));
            lines.add(row.toString());
        }
        lines.add("вертикальный разрез с севера на юг (верх вверху):");
        for (int y = originY + 5; y >= originY - 4; y--) {
            StringBuilder row = new StringBuilder();
            for (int z = -4; z <= 4; z++) row.append(z == 0 && y == originY ? '@' : symbol(grid.material(0, y, z)));
            lines.add(row.toString());
        }
        return lines;
    }
    static char symbol(Material m) {
        return switch (m) {
            case AIR, PLANT -> '.'; case SOLID, WOOD, LEAVES, FARMLAND, MACHINE, HAZARD -> '#'; case UNBREAKABLE -> 'B'; case GLASS -> 'G';
            case WATER -> '~'; case LAVA -> 'L'; case DOOR_OPEN, DOOR_CLOSED -> 'D'; case FENCE -> 'F'; case CLIMBABLE -> 'H';
        };
    }
}
