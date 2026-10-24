package net.pyrocube.core.pump.chunk;

/**
 * A scheduled block or fluid tick, the 16 byte {@code PumpScheduledTick} record (§5.4).
 *
 * @param x absolute world X
 * @param y absolute world Y
 * @param z absolute world Z
 * @param delay remaining delay in ticks, 0..255
 * @param priority priority level, -3..3
 * @param targetId numeric block or fluid id, 0..65535
 */
public record ScheduledTick(int x, int y, int z, int delay, int priority, int targetId) {
    public static final int BYTES = 16;

    public ScheduledTick {
        if (delay < 0 || delay > 0xFF) {
            throw new IllegalArgumentException("delay out of range 0..255: " + delay);
        }
        if (priority < -3 || priority > 3) {
            throw new IllegalArgumentException("priority out of range -3..3: " + priority);
        }
        if (targetId < 0 || targetId > 0xFFFF) {
            throw new IllegalArgumentException("targetId out of range 0..65535: " + targetId);
        }
    }
}
