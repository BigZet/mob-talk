package io.github.divineblock.boss.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Java net gives the same actions as the trained one in Python (onnxruntime) for observations from the trainer. */
class DodgeNetTest {
    @Test void matchesPython() throws IOException {
        DodgeNet net = DodgeNet.load("/assets/divineblock/ai/seraph_dodge.bin");
        assertNotNull(net, "weights file is in the jar and readable");
        assertEquals(SeraphDodge.OBS, net.inputSize());
        assertEquals(4, net.outputSize());
        List<String> lines;
        try (InputStream in = DodgeNetTest.class.getResourceAsStream("/seraph_dodge_check.txt")) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        for (int i = 0; i + 1 < lines.size(); i += 2) {
            float[] obs = parse(lines.get(i)), want = parse(lines.get(i + 1)), got = net.forward(obs);
            for (int k = 0; k < want.length; k++) assertEquals(want[k], got[k], 1e-4, "row " + i / 2 + " action " + k);
        }
    }

    private static float[] parse(String line) {
        String[] parts = line.trim().split(" ");
        float[] out = new float[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i]);
        return out;
    }
}
