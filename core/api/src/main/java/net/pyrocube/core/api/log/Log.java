package net.pyrocube.core.api.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The network's logger. Every server and plugin gets its loggers here, so they all share the {@code
 * pyrocube} hierarchy and, on the Minestom servers, the one format configured in {@code
 * logback.xml} (levels and colors). It is plain SLF4J: the backend is whatever the host provides.
 *
 * <pre>{@code
 * private static final Logger LOG = Log.of("lobby");
 * LOG.info("{} players online", count);
 * }</pre>
 */
public final class Log {
    /** Name of the root of the network's logger hierarchy. */
    public static final String ROOT = "pyrocube";

    private static final Logger GLOBAL = LoggerFactory.getLogger(ROOT);

    private Log() {}

    /** The global logger, for code that has no component of its own. */
    public static Logger global() {
        return GLOBAL;
    }

    /** The logger of a component, named {@code pyrocube.<component>}. */
    public static Logger of(String component) {
        return LoggerFactory.getLogger(component);
    }
}
