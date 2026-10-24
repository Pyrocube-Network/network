package net.pyrocube.core.pump.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minestom.server.MinecraftServer;
import net.pyrocube.core.pump.chunk.PumpChunk;
import net.pyrocube.core.pump.region.PumpStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PumpConvertToolTest {
    @TempDir Path dir;

    @BeforeAll
    static void bootstrap() {
        MinecraftServer.init();
    }

    private static String run(int expectedCode, String... args) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final int code =
                PumpConvertTool.run(
                        args,
                        new PrintStream(bytes),
                        BiomeIds.of(MinecraftServer.process().biome()));
        assertEquals(expectedCode, code, bytes.toString());
        return bytes.toString();
    }

    @Test
    void roundTripWithTheRealBiomeRegistry() throws Exception {
        final List<PumpChunk> chunks = new ArrayList<>();
        for (int x = 0; x < 6; x++) chunks.add(AnvilFixtures.validChunk(x, x * 2, x));
        try (PumpStorage s = PumpStorage.open(dir.resolve("a"))) {
            s.writeAll(chunks);
        }
        final String toAnvil = run(0, "pump-to-anvil", dir.resolve("a").toString(), dir.resolve("m").toString());
        assertTrue(toAnvil.contains("converted=6"), toAnvil);
        assertTrue(Files.exists(dir.resolve("m/r.0.0.mca")));

        final String toPump =
                run(0, "anvil-to-pump", dir.resolve("m").toString(), dir.resolve("b").toString(), "--level=6", "--threads=2", "--unknown-blocks=air");
        assertTrue(toPump.contains("converted=6"), toPump);
        try (PumpStorage s = PumpStorage.open(dir.resolve("b"))) {
            for (PumpChunk expected : chunks) assertEquals(expected, s.read(expected.x(), expected.z()));
        }
    }

    @Test
    void usageErrors() throws Exception {
        assertTrue(run(2).contains("usage"));
        assertTrue(run(2, "sideways", "a", "b").contains("Unknown mode"));
        assertTrue(run(2, "anvil-to-pump", "a", "b", "--bogus").contains("Unknown option"));
        assertTrue(run(2, "anvil-to-pump", "a", "b", "--level=abc").contains("Invalid option"));
    }
}
