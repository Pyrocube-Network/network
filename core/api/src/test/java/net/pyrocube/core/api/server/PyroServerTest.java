package net.pyrocube.core.api.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.Test;

class PyroServerTest {
    private static final class Recording extends PyroServer {
        final List<String> events = new ArrayList<>();

        Recording() {
            super("test");
        }

        @Override
        protected String env(String key, String fallback) {
            return key.equals("SERVER_PORT") ? "0" : fallback;
        }

        @Override
        protected void onInitialize() {
            events.add("configure alive=" + MinecraftServer.process().isAlive());
        }

        @Override
        protected void onStop() {
            events.add("stop");
        }
    }

    @Test
    void configureRunsBeforeListeningAndStopIsIdempotent() {
        final Recording server = new Recording();
        server.start();
        assertTrue(MinecraftServer.process().isAlive(), "start() must leave the server listening");
        server.stop();
        server.stop();
        assertEquals(List.of("configure alive=false", "stop"), server.events);
    }
}
