package net.pyrocube.core.pump.chunk;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;

/** Validation of block entity compounds (§5.5). */
public final class BlockEntities {
    private BlockEntities() {}

    /**
     * Checks that {@code tag} holds the integer tags {@code x}, {@code y}, {@code z} and the string
     * tag {@code id}.
     *
     * @return a description of the first violation, or {@code null} if the compound is valid
     */
    public static String problem(CompoundBinaryTag tag) {
        for (String key : new String[] {"x", "y", "z"}) {
            if (!(tag.get(key) instanceof IntBinaryTag)) {
                return "Block entity is missing the TAG_Int '" + key + "'";
            }
        }
        if (!(tag.get("id") instanceof StringBinaryTag)) {
            return "Block entity is missing the TAG_String 'id'";
        }
        return null;
    }
}
