package net.pyrocube.skyblock;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import net.minestom.server.world.DimensionType;
import net.pyrocube.core.api.server.PyroServer;

/** The skyblock server. */
public final class SkyblockServer extends PyroServer {
    private SkyblockServer() {
        super("skyblock");
    }

    @Override
    protected void onInitialize() {
        // placeholder world so players can join, replace with the real one
        final InstanceContainer world =
                MinecraftServer.getInstanceManager()
                        .createInstanceContainer(DimensionType.OVERWORLD);
        world.setGenerator(unit -> unit.modifier().fillHeight(0, 40, Block.GRASS_BLOCK));
        MinecraftServer.getGlobalEventHandler()
                .addListener(
                        AsyncPlayerConfigurationEvent.class,
                        event -> {
                            event.setSpawningInstance(world);
                            event.getPlayer().setRespawnPoint(new Pos(0, 41, 0));
                        });
    }

    public static void main(String[] args) {
        new SkyblockServer().start();
    }
}
