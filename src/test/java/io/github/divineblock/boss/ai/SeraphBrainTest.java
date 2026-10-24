package io.github.divineblock.boss.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Java brain answers like the trained PyTorch net (ml/seraph_dodge/v2/export.py wrote the check file). */
class SeraphBrainTest {
    @Test void matchesPython() throws IOException {
        SeraphBrain brain = SeraphBrain.load("/assets/divineblock/ai/seraph_brain.bin");
        assertNotNull(brain, "weights file is in the jar and readable");
        assertEquals(SeraphMind.TOK, brain.tokDim);
        assertEquals(SeraphMind.ITEM, brain.itemDim);
        List<String> lines;
        try (InputStream in = SeraphBrainTest.class.getResourceAsStream("/seraph_brain_check.txt")) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        for (int r = 0; r + 2 < lines.size(); r += 3) {
            float[] t = parse(lines.get(r)), it = parse(lines.get(r + 1)), want = parse(lines.get(r + 2));
            float[][] tokens = new float[SeraphMind.T][SeraphMind.TOK], items = new float[SeraphMind.P][SeraphMind.ITEM];
            for (int x = 0; x < SeraphMind.T; x++) System.arraycopy(t, x * SeraphMind.TOK, tokens[x], 0, SeraphMind.TOK);
            for (int x = 0; x < SeraphMind.P; x++) System.arraycopy(it, x * SeraphMind.ITEM, items[x], 0, SeraphMind.ITEM);
            float[] got = brain.forward(tokens, items);
            for (int k = 0; k < want.length; k++) assertEquals(want[k], got[k], 1e-3, "row " + r / 3 + " output " + k);
        }
    }

    private static float[] parse(String line) {
        String[] parts = line.trim().split(" ");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }
}
