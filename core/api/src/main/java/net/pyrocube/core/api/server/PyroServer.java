package net.pyrocube.core.api.server;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.pyrocube.core.api.log.Log;
import org.slf4j.Logger;

/**
 * Base of every Minestom server of the network: the initialization they all share, so a server only
 * says what is specific to it.
 *
 * <p>{@link #start()} reads the environment, initialises Minestom, runs {@link #onInitialize()},
 * then starts listening:
 *
 * <ul>
 *   <li>{@code SERVER_HOST} (default {@code 0.0.0.0}) and {@code SERVER_PORT} (default {@code
 *       25565}), where to listen.
 *   <li>{@code VELOCITY_SECRET}, the modern forwarding secret of the proxy. Without it players
 *       authenticate against Mojang directly and a warning is logged, which is only right for local
 *       development. Ignored when the {@code minestom.development} system property is true.
 * </ul>
 *
 * <p>Lines typed on stdin run as server commands, {@code stop} included. A shutdown hook calls
 * {@link #stop()}, so SIGTERM and Ctrl+C stop the server cleanly.
 */
public abstract class PyroServer {
    private final String name;
    private final Logger log;
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * @param name short server name, used for the logger ({@code pyrocube.<name>}) and the banner
     */
    protected PyroServer(String name) {
        this.name = name;
        this.log = Log.of(name);
    }

    /** Server specific setup: instances, events, commands. Runs after Minestom is initialized. */
    protected abstract void onInitialize();

    /** Runs after Minecraft server is initialized. */
    protected void onPostInitialize() {}

    /** Called once when the server stops, before Minestom shuts down. */
    protected void onStop() {}

    /** This server's logger. */
    protected final Logger log() {
        return log;
    }

    /** Initialises Minestom, runs {@link #onInitialize()} and starts listening. */
    public final void start() {
        final long began = System.nanoTime();
        final String host = env("SERVER_HOST", "0.0.0.0");
        final int port = port(env("SERVER_PORT", "25565"));
        final boolean development = Boolean.getBoolean("minestom.development");
        final String secret = development ? "" : env("VELOCITY_SECRET", "");

        log.info("Starting {}", name);
        final MinecraftServer server;
        if (development) {
            log.info("minestom.development is set: Velocity forwarding disabled");
            server = MinecraftServer.init();
        } else if (secret.isEmpty()) {
            log.warn("VELOCITY_SECRET is not set: players authenticate against Mojang directly");
            server = MinecraftServer.init();
        } else {
            log.info("Velocity modern forwarding enabled");
            server = MinecraftServer.init(new Auth.Velocity(secret));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, name + "-shutdown"));

        try {
            onInitialize();
            server.start(host, port);
        } catch (RuntimeException e) {
            log.error("{} failed to start", name, e);
            throw e;
        }
        log.info(
                "{} listening on {}:{} ({} ms)",
                name,
                host,
                port,
                (System.nanoTime() - began) / 1_000_000);

        onPostInitialize();
    }

    /** Stops the server. Safe to call more than once, only the first call does anything. */
    public final void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        log.info("Stopping {}", name);
        try {
            onStop();
        } catch (RuntimeException e) {
            log.error("{} failed while stopping", name, e);
        }
        MinecraftServer.stopCleanly();
    }

    /** The environment variable {@code key}, or {@code fallback} when unset or blank. */
    protected String env(String key, String fallback) {
        final String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int port(String text) {
        try {
            final int port = Integer.parseInt(text);
            if (port >= 0 && port <= 65535) return port;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new IllegalStateException("SERVER_PORT must be a port number, got '" + text + "'");
    }
}
