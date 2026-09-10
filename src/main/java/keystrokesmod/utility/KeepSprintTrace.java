package keystrokesmod.utility;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayDeque;
import java.util.Deque;

/** Opt-in bounded history; contains movement/combat diagnostics, never chat or login packets. */
public final class KeepSprintTrace {
    private static final Logger LOG = LogManager.getLogger("KeepSprintTrace");
    private final Deque<String> history = new ArrayDeque<>();
    private long started = System.nanoTime();
    private String configuration = "";

    public synchronized void configuration(String value) { configuration = value; }

    public synchronized void record(String message) {
        if (history.size() == 256) history.removeFirst();
        history.addLast((System.nanoTime() - started) / 1000000L + "ms " + message);
    }

    public synchronized void correction(String message) {
        LOG.info("[KeepSprintTrace] BEGIN " + message);
        LOG.info("[KeepSprintTrace] " + configuration);
        for (String line : history) LOG.info("[KeepSprintTrace] " + line);
        LOG.info("[KeepSprintTrace] END");
        history.clear();
    }

    public synchronized void clear() {
        history.clear();
        configuration = "";
        started = System.nanoTime();
    }
}
