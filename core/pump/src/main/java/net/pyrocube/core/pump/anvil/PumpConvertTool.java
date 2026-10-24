package net.pyrocube.core.pump.anvil;

import java.io.PrintStream;
import java.nio.file.Path;
import net.minestom.server.MinecraftServer;
import net.pyrocube.core.pump.region.PumpOptions;

/**
 * Command line world converter between Anvil and PUMP.
 *
 * <pre>
 * anvil-to-pump &lt;anvil region dir&gt; &lt;pump region dir&gt; [options]
 * pump-to-anvil &lt;pump region dir&gt; &lt;anvil region dir&gt; [options]
 *
 * options:
 *   --unknown-blocks=fail|air   what to do with block states this Minestom does not know (default fail)
 *   --level=N                   Zstandard level for PUMP output (default 3)
 *   --threads=N                 worker parallelism (default: all cores)
 * </pre>
 *
 * <p>Biome and block ids come from the Minestom version on the classpath, so the converted world is
 * valid for exactly that version. From Gradle: {@code ./gradlew :core:pump:convertWorld
 * -Pmode=anvil-to-pump -Psource=world/region -Ptarget=out/region}.
 */
public final class PumpConvertTool {
    private PumpConvertTool() {}

    public static void main(String[] args) throws Exception {
        MinecraftServer.init();
        final int code = run(args, System.out, BiomeIds.of(MinecraftServer.process().biome()));
        if (code != 0) System.exit(code);
    }

    /**
     * Runs the tool.
     *
     * @return the process exit code: 0 on success, 1 if any chunk failed, 2 on a usage error
     */
    public static int run(String[] args, PrintStream out, BiomeIds biomes) throws Exception {
        if (args.length < 3) return usage(out);
        final String mode = args[0];
        final Path source = Path.of(args[1]);
        final Path target = Path.of(args[2]);

        UnknownBlockPolicy policy = UnknownBlockPolicy.FAIL;
        PumpOptions options = PumpOptions.defaults();
        for (int i = 3; i < args.length; i++) {
            final String arg = args[i];
            try {
                if (arg.startsWith("--unknown-blocks=")) {
                    policy = UnknownBlockPolicy.valueOf(value(arg).toUpperCase());
                } else if (arg.startsWith("--level=")) {
                    options = options.withCompressionLevel(Integer.parseInt(value(arg)));
                } else if (arg.startsWith("--threads=")) {
                    options = options.withParallelism(Integer.parseInt(value(arg)));
                } else {
                    out.println("Unknown option " + arg);
                    return usage(out);
                }
            } catch (IllegalArgumentException e) {
                out.println("Invalid option " + arg);
                return usage(out);
            }
        }

        final WorldConverter converter =
                new WorldConverter(new AnvilTranscoder(biomes, policy), options);
        final ConversionReport report;
        switch (mode) {
            case "anvil-to-pump" -> report = converter.anvilToPump(source, target);
            case "pump-to-anvil" -> report = converter.pumpToAnvil(source, target);
            default -> {
                out.println("Unknown mode " + mode);
                return usage(out);
            }
        }

        out.println(report);
        for (String failure : report.failures()) out.println("  failed: " + failure);
        if (report.failed() > report.failures().size()) {
            out.println("  ... and " + (report.failed() - report.failures().size()) + " more");
        }
        return report.failed() == 0 ? 0 : 1;
    }

    private static String value(String arg) {
        return arg.substring(arg.indexOf('=') + 1);
    }

    private static int usage(PrintStream out) {
        out.println(
                "usage: anvil-to-pump|pump-to-anvil <source dir> <target dir> [--unknown-blocks=fail|air] [--level=N] [--threads=N]");
        return 2;
    }
}
