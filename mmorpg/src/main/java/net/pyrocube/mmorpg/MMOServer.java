package net.pyrocube.mmorpg;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.pyrocube.core.api.server.PyroServer;
import net.pyrocube.core.pump.minestom.PumpChunkLoader;

/** The mmorpg server. */
public final class MMOServer extends PyroServer {
    private InstanceContainer world;
    private PumpChunkLoader chunks;

    private MMOServer() {
        super("mmorpg");
    }

    @Override
    protected void onInitialize() {
        world =
                MinecraftServer.getInstanceManager()
                        .createInstanceContainer(DimensionType.OVERWORLD);
        chunks =
                new PumpChunkLoader(
                        Path.of(env("WORLD_DIR", "world")), DimensionType.OVERWORLD.key());
        world.setChunkLoader(chunks);
        // placeholder generator for chunks the world does not have yet
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        MinecraftServer.getGlobalEventHandler()
                .addListener(
                        AsyncPlayerConfigurationEvent.class,
                        event -> {
                            event.setSpawningInstance(world);
                            event.getPlayer().setRespawnPoint(new Pos(0, 41, 0));
                        });
    }

    @Override
    protected void onStop() {
        world.saveChunksToStorage().join();
        try {
            chunks.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void main(String[] args) {
        new MMOServer().start();
    }
}
