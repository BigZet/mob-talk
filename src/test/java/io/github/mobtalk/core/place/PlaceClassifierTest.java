package io.github.mobtalk.core.place;

import java.io.DataInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The hand-written forward pass must agree with PyTorch: saved validation cubes with the logits Python computed for them. */
class PlaceClassifierTest {
    @Test void javaForwardMatchesPython() throws Exception {
        PlaceClassifier classifier = PlaceClassifier.bundled();
        assertNotNull(classifier, "classifier.bin is bundled in resources");
        try (InputStream raw = getClass().getResourceAsStream("/place/parity.bin")) {
            assertNotNull(raw, "parity.bin is in test resources");
            DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
            byte[] magic = new byte[4]; in.readFully(magic);
            assertEquals("MTPR", new String(magic, java.nio.charset.StandardCharsets.US_ASCII));
            int count = in.readInt(), cube = in.readInt(), classes = in.readInt();
            assertEquals(VoxelGrid.CUBE, cube); assertEquals(classifier.labels().size(), classes);
            int agree = 0;
            long started = System.nanoTime();
            for (int i = 0; i < count; i++) {
                byte[] cells = new byte[cube * cube * cube]; in.readFully(cells);
                int label = in.readByte();
                float[] expected = new float[classes]; for (int c = 0; c < classes; c++) expected[c] = in.readFloat();
                float[] actual = classifier.logits(cells);
                int bestExpected = 0, bestActual = 0;
                for (int c = 1; c < classes; c++) { if (expected[c] > expected[bestExpected]) bestExpected = c; if (actual[c] > actual[bestActual]) bestActual = c; }
                assertEquals(bestExpected, bestActual, "sample " + i + " (label " + classifier.labels().get(label) + ")");
                for (int c = 0; c < classes; c++) assertEquals(expected[c], actual[c], 0.02 + 0.01 * Math.abs(expected[c]), "sample " + i + " logit " + c);
                if (bestActual == label) agree++;
            }
            double perSample = (System.nanoTime() - started) / (double) count / 1_000_000;
            System.out.printf("classifier: %d of %d parity samples labelled right, %.1f ms per cube%n", agree, count, perSample);
            assertTrue(agree >= count * 0.8, "the saved samples should mostly be classified right: " + agree + "/" + count);
        }
    }

    @Test void generatedScenesAreMostlyRecognised() {
        PlaceClassifier classifier = PlaceClassifier.bundled();
        assertNotNull(classifier);
        SceneGenerator generator = new SceneGenerator(2026);
        int right = 0, total = 0;
        for (SceneGenerator.Label label : SceneGenerator.Label.ALL) for (int i = 0; i < 6; i++) {
            PlaceClassifier.Guess guess = classifier.guess(generator.generate(label).grid().cube());
            total++; if (guess.label().equals(label.name())) right++;
        }
        assertTrue(right >= total * 0.85, right + " of " + total);
    }
}
