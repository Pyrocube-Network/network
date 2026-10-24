package net.pyrocube.core.pump.anvil;

import java.util.List;

/**
 * A conversion result together with the non-fatal problems met on the way, such as a dropped
 * malformed block entity or a tick delay that had to be clamped.
 */
public record Transcoded<T>(T value, List<String> warnings) {}
