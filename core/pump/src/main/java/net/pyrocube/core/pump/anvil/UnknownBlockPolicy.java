package net.pyrocube.core.pump.anvil;

/** What to do with an Anvil block state this server does not know. */
public enum UnknownBlockPolicy {
    /** Fail the chunk. Nothing is silently lost, the chunk is reported and skipped. */
    FAIL,

    /** Replace the state with air and record a warning. */
    AIR
}
