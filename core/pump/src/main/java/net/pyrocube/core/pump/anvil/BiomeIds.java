package net.pyrocube.core.pump.anvil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.RegistryKey;
import net.minestom.server.world.biome.Biome;

/**
 * Mapping between biome names (Anvil) and the numeric ids stored in PUMP sections (§5.3.3).
 *
 * <p>Biome ids are a property of the server's registry, not of the format, so the transcoder asks
 * for them instead of assuming a table.
 */
public interface BiomeIds {
    /** The numeric id of {@code name} such as {@code minecraft:plains}, or -1 if unknown. */
    int idOf(String name);

    /** The name of {@code id}, or {@code null} if unknown. */
    String nameOf(int id);

    /** The id used for sections without biome data and for unknown biome names. */
    int fallbackId();

    /**
     * Backs the mapping by a Minestom biome registry, such as {@code
     * instance.registries().biome()}.
     */
    static BiomeIds of(DynamicRegistry<Biome> registry) {
        final int fallback = registry.getId(Biome.PLAINS);
        return new BiomeIds() {
            @Override
            public int idOf(String name) {
                return registry.getId(RegistryKey.of(name));
            }

            @Override
            public String nameOf(int id) {
                final RegistryKey<Biome> key = registry.getKey(id);
                return key == null ? null : key.key().asString();
            }

            @Override
            public int fallbackId() {
                return fallback;
            }
        };
    }

    /** A fixed table where the id of a name is its position in {@code names}. */
    static BiomeIds ofList(List<String> names, String fallback) {
        final Map<String, Integer> ids = new HashMap<>();
        for (int i = 0; i < names.size(); i++) ids.put(names.get(i), i);
        final int fallbackId = ids.getOrDefault(fallback, 0);
        return new BiomeIds() {
            @Override
            public int idOf(String name) {
                return ids.getOrDefault(name, -1);
            }

            @Override
            public String nameOf(int id) {
                return id >= 0 && id < names.size() ? names.get(id) : null;
            }

            @Override
            public int fallbackId() {
                return fallbackId;
            }
        };
    }
}
