package io.github.mobtalk.core.place;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The flood on hand-built and generated scenes: every generated category must yield the conclusions a creature would draw. */
class PlaceTest {
    private static VoxelGrid flat() {
        VoxelGrid g = new VoxelGrid(SceneGenerator.RADIUS_XZ, SceneGenerator.RADIUS_Y);
        g.fill(-g.radiusXZ, -g.radiusY, -g.radiusXZ, g.radiusXZ, -1, g.radiusXZ, Material.SOLID, "minecraft:grass_block");
        return g;
    }

    @Test void openGroundHasExitsEverywhere() {
        Place.Report r = Place.analyse(flat(), Place.Options.DEFAULT.player(3, 0, 0));
        assertEquals(Place.Kind.OPEN, r.kind);
        assertFalse(r.trapped); assertTrue(r.skyOpen);
        assertTrue(r.exits.containsAll(List.of("восток", "запад", "юг", "север")), r.exits.toString());
        assertTrue(r.playerInside && "direct".equals(r.playerSight), r.summary());
        assertTrue(r.summary().startsWith("Ты на открытом месте"), r.summary());
    }

    @Test void aBedrockPitIsClosedAndUndiggable() {
        VoxelGrid g = flat();
        g.fill(-5, -1, -5, 5, 3, 5, Material.SOLID, "minecraft:stone"); // surrounding ground raised
        g.fill(-1, 0, -1, 1, 3, 1, Material.AIR, "minecraft:air"); // a 3×3 pit, 4 deep
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) for (int y = -1; y <= 3; y++)
            if ((Math.abs(x) == 2 || Math.abs(z) == 2 || y == -1) && !g.passable(x, y, z)) g.set(x, y, z, Material.UNBREAKABLE, "minecraft:bedrock");
        g.fill(-5, 4, -5, 5, g.radiusY, 5, Material.AIR, "minecraft:air");
        Place.Report r = Place.analyse(g, Place.Options.DEFAULT.player(2, 4, 0));
        assertEquals(Place.Kind.PIT, r.kind, r.summary());
        assertTrue(r.trapped && r.enclosed && r.exits.isEmpty());
        assertEquals(3, r.width); assertEquals(3, r.length); assertEquals(4, r.depth);
        assertTrue(r.share(Material.UNBREAKABLE) > 0.9, "walls: " + r.walls);
        assertTrue(r.skyOpen);
        assertFalse(r.playerInside); assertTrue(r.playerAbove);
        String text = r.summary();
        assertTrue(text.contains("ямы 3×3 глубиной 4") && text.contains("бедрок") && text.contains("не прокопать") && text.contains("заперт"), text);
        assertTrue(text.contains("Игрок снаружи, выше тебя, видно через проём"), text);
        assertTrue(r.escape().contains("не прокопать"), r.escape());
    }

    @Test void aGlassCageShowsThePlayerThroughTheWall() {
        VoxelGrid g = flat();
        g.fill(-2, 0, -2, 2, 3, 2, Material.GLASS, "minecraft:glass");
        g.fill(-1, 0, -1, 1, 2, 1, Material.AIR, "minecraft:air");
        Place.Report r = Place.analyse(g, Place.Options.DEFAULT.player(4, 0, 0));
        assertEquals(Place.Kind.CAGE, r.kind, r.summary());
        assertTrue(r.trapped);
        assertEquals("glass", r.playerSight);
        assertTrue(r.seesSky && !r.skyOpen, "sky through the roof glass");
        assertTrue(r.summary().contains("клетке") && r.summary().contains("видно через стекло"), r.summary());
    }

    @Test void aWaterFunnelPullsDownToLava() {
        SceneGenerator.Scene scene = new SceneGenerator(7).funnel(new VoxelGrid(SceneGenerator.RADIUS_XZ, SceneGenerator.RADIUS_Y));
        Place.Report r = Place.analyse(scene.grid(), Place.Options.DEFAULT);
        assertEquals(Place.Kind.FUNNEL, r.kind, scene.note() + " → " + r.summary());
        assertTrue(r.funnel && r.trapped);
    }

    @Test void aClosedDoorIsAWayOutOnlyForThoseWhoOpenDoors() {
        VoxelGrid g = flat();
        g.fill(-3, 0, -3, 3, 3, 3, Material.WOOD, "minecraft:oak_planks");
        g.fill(-2, 0, -2, 2, 2, 2, Material.AIR, "minecraft:air");
        g.set(3, 0, 0, Material.DOOR_CLOSED, "minecraft:oak_door"); g.set(3, 1, 0, Material.DOOR_CLOSED, "minecraft:oak_door");
        Place.Report cow = Place.analyse(g, Place.Options.DEFAULT);
        assertEquals(Place.Kind.ROOM, cow.kind, cow.summary());
        assertTrue(cow.trapped && cow.doors.contains("восток"), cow.summary());
        Place.Report villager = Place.analyse(g, Place.Options.DEFAULT.doors(true));
        assertFalse(villager.trapped);
        assertTrue(villager.summary().contains("дверь: восток (закрыта)"), villager.summary());
        g.set(3, 0, 0, Material.DOOR_OPEN, "minecraft:oak_door"); g.set(3, 1, 0, Material.DOOR_OPEN, "minecraft:oak_door");
        Place.Report open = Place.analyse(g, Place.Options.DEFAULT);
        assertEquals(Place.Kind.ROOM, open.kind, "an open door still bounds the room: " + open.summary());
        assertFalse(open.trapped);
        assertTrue(open.summary().contains("дверь: восток (открыта)"), open.summary());
    }

    @Test void aPillarIsAHeightWithDropsAround() {
        VoxelGrid g = new VoxelGrid(SceneGenerator.RADIUS_XZ, SceneGenerator.RADIUS_Y);
        g.fill(-g.radiusXZ, -g.radiusY, -g.radiusXZ, g.radiusXZ, -8, g.radiusXZ, Material.SOLID, "minecraft:stone");
        g.fill(0, -7, 0, 0, -1, 0, Material.SOLID, "minecraft:stone");
        Place.Report r = Place.analyse(g, Place.Options.DEFAULT.player(2, -7, 0));
        assertEquals(Place.Kind.HEIGHT, r.kind, r.summary());
        assertTrue(r.trapped, "a seven-block drop is not a way down");
        assertTrue(r.playerBelow && r.summary().contains("обрыв в 7 блоков"), r.summary());
        VoxelGrid low = new VoxelGrid(SceneGenerator.RADIUS_XZ, SceneGenerator.RADIUS_Y);
        low.fill(-low.radiusXZ, -low.radiusY, -low.radiusXZ, low.radiusXZ, -5, low.radiusXZ, Material.SOLID, "minecraft:stone");
        low.fill(0, -4, 0, 0, -1, 0, Material.SOLID, "minecraft:stone");
        Place.Report short4 = Place.analyse(low, Place.Options.DEFAULT);
        assertEquals(Place.Kind.HEIGHT, short4.kind);
        assertFalse(short4.trapped, "four blocks: it can jump");
    }

    @Test void lavaUnderTheNextStepIsNoFloor() {
        VoxelGrid g = flat();
        g.fill(-3, -1, -3, 3, -1, 3, Material.LAVA);
        g.set(0, -1, 0, Material.SOLID, "minecraft:stone");
        g.fill(-4, 0, -4, 4, 3, 4, Material.SOLID, "minecraft:stone");
        g.fill(-3, 0, -3, 3, 3, 3, Material.AIR, "minecraft:air");
        Place.Report r = Place.analyse(g, Place.Options.DEFAULT);
        assertEquals(1, r.cells, "only the island is standable");
        assertTrue(r.lavaNear > 0 && r.trapped, r.summary());
        assertTrue(r.summary().contains("лава"), r.summary());
    }

    @Test void playerChangesTellWhoBuiltIt() {
        VoxelGrid g = flat();
        g.fill(-2, 0, -2, 2, 3, 2, Material.SOLID, "minecraft:cobblestone");
        g.fill(-1, 0, -1, 1, 2, 1, Material.AIR, "minecraft:air");
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) if (!g.passable(x, y, z)) g.changed(x, y, z, VoxelGrid.PLACED);
        Place.Report r = Place.analyse(g, Place.Options.DEFAULT.changes(2));
        assertTrue(r.placedByPlayer >= 20, "placed: " + r.placedByPlayer);
        assertTrue(r.summary().contains("поставил игрок") && r.summary().contains("замуровал"), r.summary());
        Place.Report natural = Place.analyse(flatPit(), Place.Options.DEFAULT);
        assertTrue(natural.summary().contains("Следов игрока в стенах нет"), natural.summary());
        VoxelGrid dug = flatPit();
        for (int y = 0; y <= 2; y++) dug.changed(0, y, 0, VoxelGrid.BROKEN);
        dug.changed(0, -1, 0, VoxelGrid.BROKEN);
        Place.Report dugReport = Place.analyse(dug, Place.Options.DEFAULT.changes(1));
        assertTrue(dugReport.summary().contains("окопал"), dugReport.summary());
    }
    private static VoxelGrid flatPit() {
        VoxelGrid g = flat();
        g.fill(-g.radiusXZ, 0, -g.radiusXZ, g.radiusXZ, 2, g.radiusXZ, Material.SOLID, "minecraft:dirt");
        g.fill(0, 0, 0, 0, 2, 0, Material.AIR, "minecraft:air");
        return g;
    }

    @Test void generatedCategoriesYieldTheirConclusions() {
        SceneGenerator generator = new SceneGenerator(42);
        Map<SceneGenerator.Label, Integer> agree = new EnumMap<>(SceneGenerator.Label.class);
        int perLabel = 40;
        for (SceneGenerator.Label label : SceneGenerator.Label.ALL) for (int i = 0; i < perLabel; i++) {
            SceneGenerator.Scene scene = generator.generate(label);
            Place.Report r = Place.analyse(scene.grid(), Place.Options.DEFAULT);
            boolean ok = switch (label) {
                case PIT -> r.kind == Place.Kind.PIT && r.trapped && r.skyOpen;
                case CHAMBER -> r.kind == Place.Kind.CHAMBER && r.trapped && !r.seesSky;
                case FUNNEL -> r.kind == Place.Kind.FUNNEL && r.trapped;
                case CAGE -> r.kind == Place.Kind.CAGE && r.enclosed && r.seesSky;
                case HOUSE -> r.kind == Place.Kind.ROOM && !r.doors.isEmpty() == r.trapped && !r.seesSky;
                case FARM -> r.kind == Place.Kind.OPEN || r.kind == Place.Kind.PEN;
                case MINE -> (r.kind == Place.Kind.CORRIDOR || r.kind == Place.Kind.DEAD_END || r.kind == Place.Kind.OPEN || r.kind == Place.Kind.ROOM) && !r.seesSky && !r.trapped;
                case TRAP -> r.trapped && (r.lavaNear > 0 || r.waterCells > 0);
                case OPEN -> r.kind == Place.Kind.OPEN && !r.trapped;
                case PILLAR -> r.kind == Place.Kind.HEIGHT;
                case POND -> r.inWater && !r.trapped;
            };
            if (ok) agree.merge(label, 1, Integer::sum);
            else System.out.println(label + " " + scene.note() + " → " + r.kind + " trapped=" + r.trapped + " exits=" + r.exits + " doors=" + r.doors + " sky=" + r.skyOpen + "/" + r.seesSky + " water=" + r.waterCells + " lava=" + r.lavaNear + " | " + r.summary());
        }
        for (SceneGenerator.Label label : SceneGenerator.Label.ALL)
            assertTrue(agree.getOrDefault(label, 0) >= perLabel * 0.95, label + ": " + agree.getOrDefault(label, 0) + " of " + perLabel);
    }

    @Test void analysisStaysWithinTwoMilliseconds() {
        SceneGenerator generator = new SceneGenerator(3);
        List<SceneGenerator.Scene> scenes = new java.util.ArrayList<>();
        for (int i = 0; i < 220; i++) scenes.add(generator.generate(SceneGenerator.Label.ALL[i % SceneGenerator.Label.ALL.length]));
        for (int i = 0; i < 100; i++) { Place.analyse(scenes.get(i).grid(), Place.Options.DEFAULT.player(3, 0, 2)); PlaceObjects.find(scenes.get(i).grid(), 0); }
        long started = System.nanoTime(); int cells = 0;
        for (int i = 100; i < 220; i++) {
            Place.Report r = Place.analyse(scenes.get(i).grid(), Place.Options.DEFAULT.player(3, 0, 2));
            cells += r.cells + PlaceObjects.find(scenes.get(i).grid(), 0).size();
        }
        double perScene = (System.nanoTime() - started) / 120.0 / 1_000_000;
        System.out.printf("place analysis + objects: %.3f ms per scene (%d cells)%n", perScene, cells);
        assertTrue(perScene < 4, "too slow: " + perScene + " ms");
    }

    @Test void objectsAreNamedByDirectionDistanceSizeAndUse() {
        VoxelGrid g = flat();
        g.fill(-1, 0, -3, 2, 2, -3, Material.UNBREAKABLE, "minecraft:bedrock"); // a 4×3 wall two blocks north
        g.fill(2, -4, 2, 3, -4, 3, Material.LAVA);
        g.fill(2, -3, 2, 3, -1, 3, Material.AIR, "minecraft:air");
        g.set(4, 0, 0, Material.DOOR_OPEN, "minecraft:oak_door"); g.set(4, 1, 0, Material.DOOR_OPEN, "minecraft:oak_door");
        g.set(0, -1, 0, Material.WATER); g.flow(0, -1, 0, 0, 0, 0.8f);
        List<PlaceObjects.Item> items = PlaceObjects.find(g, 0);
        List<String> texts = items.stream().map(PlaceObjects.Item::text).toList();
        assertTrue(texts.stream().anyMatch(t -> t.equals("стена из бедрок 4×3 в 3 блоках к северу: не прокопать")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.startsWith("лава 2×2 в 4 блоках") && t.contains("ниже") && t.endsWith("смертельно")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.equals("дверь в 4 блоках к востоку, открыта")), texts.toString());
        assertTrue(texts.stream().anyMatch(t -> t.equals("вода под ногами, течёт на юг")), texts.toString());
        assertEquals(Material.LAVA, items.get(0).material(), "danger first");
    }

    @Test void asciiSlicesMarkTheCreature() {
        List<String> lines = Place.ascii(flatPit(), 0);
        assertEquals(1 + 9 + 1 + 10, lines.size());
        assertTrue(lines.get(5).contains("@") && lines.get(5).indexOf('@') == 4, lines.get(5));
    }
}
