package net.pyrocube.core.pump;

import java.io.IOException;

/** Base class of every failure raised by the PUMP implementation. */
public class PumpException extends IOException {
    public PumpException(String message) {
        super(message);
    }

    public PumpException(String message, Throwable cause) {
        super(message, cause);
    }
}
