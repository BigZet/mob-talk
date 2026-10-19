package io.github.mobtalk.core.place;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The hand-written forward pass must agree with PyTorch: saved held-out cubes with the logits Python computed for them, for
 * every head of the bundled classifier. */
class PlaceClassifierTest {
    @Test void javaForwardMatchesPython() throws Exception {
        PlaceClassifier classifier = PlaceClassifier.bundled();
        assertNotNull(classifier, "classifier.bin is bundled in resources");
        try (InputStream raw = getClass().getResourceAsStream("/place/parity.bin")) {
            assertNotNull(raw, "parity.bin is in test resources");
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
            byte[] magic = new byte[4]; in.readFully(magic);
            String kind = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            int count = in.readInt(), cube = in.readInt(), fine = in.readInt(), functions = kind.equals("MTP2") ? in.readInt() : 0;
            assertEquals(VoxelGrid.CUBE, cube); assertEquals(classifier.labels().size(), fine);
            assertEquals(kind.equals("MTP2") ? 2 : 1, classifier.version(), "parity file and weights come from the same trainer");
            long started = System.nanoTime();
            for (int i = 0; i < count; i++) {
                byte[] cells = new byte[cube * cube * cube]; in.readFully(cells);
                byte[] families = null;
                if (kind.equals("MTP2")) { families = new byte[cells.length]; in.readFully(families); } else in.readByte();
                float[][] actual = classifier.logits(cells, families);
                float[][] expected = {new float[fine], new float[functions]};
                for (int c = 0; c < fine; c++) expected[0][c] = in.readFloat();
                for (int c = 0; c < functions; c++) expected[1][c] = in.readFloat();
                for (int h = 0; h < (functions > 0 ? 2 : 1); h++) {
                    assertEquals(argmax(expected[h]), argmax(actual[h]), "sample " + i + " head " + h);
                    for (int c = 0; c < expected[h].length; c++)
                        assertEquals(expected[h][c], actual[h][c], 0.02 + 0.01 * Math.abs(expected[h][c]), "sample " + i + " head " + h + " logit " + c);
                }
            }
            System.out.printf("classifier v%d: %d parity samples agree, %.1f ms per cube%n", classifier.version(), count, (System.nanoTime() - started) / (double) count / 1e6);
        }
    }

    private static int argmax(float[] v) { int b = 0; for (int i = 1; i < v.length; i++) if (v[i] > v[b]) b = i; return b; }

    @Test void generatedScenesAreMostlyRecognised() {
        PlaceClassifier classifier = PlaceClassifier.bundled();
        assertNotNull(classifier);
        SceneGenerator generator = new SceneGenerator(2026);
        int right = 0, total = 0;
        // Format 2 names classes in Russian; the generator's labels map to them through the same table the trainer uses.
        java.util.Map<String, String> russian = java.util.Map.ofEntries(java.util.Map.entry("PIT", "яма"), java.util.Map.entry("CHAMBER", "закрытая камера"),
                java.util.Map.entry("FUNNEL", "воронка"), java.util.Map.entry("CAGE", "клетка"), java.util.Map.entry("HOUSE", "дом"), java.util.Map.entry("FARM", "ферма"),
                java.util.Map.entry("MINE", "шахта"), java.util.Map.entry("TRAP", "ловушка"), java.util.Map.entry("OPEN", "открытое место"),
                java.util.Map.entry("PILLAR", "столб"), java.util.Map.entry("POND", "водоём"));
        for (SceneGenerator.Label label : SceneGenerator.Label.ALL) for (int i = 0; i < 6; i++) {
            VoxelGrid grid = generator.generate(label).grid();
            PlaceClassifier.Hierarchy guess = classifier.classify(grid.cube(), grid.familyCube());
            total++;
            if (guess.label().equals(label.name()) || guess.label().equals(russian.get(label.name()))) right++;
        }
        assertTrue(right >= total * 0.85, right + " of " + total);
    }
}
