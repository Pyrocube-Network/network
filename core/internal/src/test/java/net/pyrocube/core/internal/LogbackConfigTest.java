package net.pyrocube.core.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import net.pyrocube.core.api.log.Log;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogbackConfigTest {
    private static String render(Level level, String loggerName, String message) {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final ConsoleAppender<ILoggingEvent> console =
                (ConsoleAppender<ILoggingEvent>)
                        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("CONSOLE");
        final PatternLayoutEncoder encoder = (PatternLayoutEncoder) console.getEncoder();
        final LoggingEvent event =
                new LoggingEvent(
                        LogbackConfigTest.class.getName(),
                        context.getLogger(loggerName),
                        level,
                        message,
                        null,
                        null);
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    @Test
    void linesAreColoredAndNamedAfterTheComponent() {
        final String line = render(Level.WARN, Log.of("lobby").getName(), "careful");
        assertTrue(line.contains("\u001B["), "expected ANSI colors in: " + line);
        assertTrue(line.contains("lobby"), line);
        assertTrue(!line.contains("pyrocube.lobby"), "only the last name segment is shown: " + line);
        assertTrue(line.contains("careful"), line);
    }

    @Test
    void levelsGetDifferentColors() {
        final String error = render(Level.ERROR, "x", "m");
        final String info = render(Level.INFO, "x", "m");
        assertTrue(!error.substring(0, error.indexOf("ERROR")).equals(info.substring(0, info.indexOf("INFO"))));
    }

    @Test
    void logsGoToTheLogsFolderOfTheServer() {
        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final RollingFileAppender<ILoggingEvent> file =
                (RollingFileAppender<ILoggingEvent>)
                        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("FILE");
        assertEquals(Path.of("logs", "latest.log"), Path.of(file.getFile()));
    }

    @Test
    void componentLoggersLiveUnderTheGlobalOne() {
        assertEquals("pyrocube", Log.global().getName());
        assertEquals("pyrocube.proxy", Log.of("proxy").getName());
    }
}
