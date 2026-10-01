package org.apache.zookeeper.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Research-only delay injector for multi-stage latency attribution.
 * Logs with prefix "ZK_OPCODE_LOG [STAGE]" for easy grepping.
 * Supports: HEAVY (busy-loop), SLOW (Thread.sleep), NETWORK (sleep + thread rename).
 */
public final class DelayInjector {
    private static final Logger LOG = LoggerFactory.getLogger(DelayInjector.class);

    private DelayInjector() {}

    public static void inject(String stage, String spec) {
        if (spec == null || spec.trim().isEmpty()) return;

        String[] parts = spec.trim().split(":", 2);
        if (parts.length != 2) return;

        String type = parts[0].trim().toUpperCase();
        try {
            long duration = Long.parseLong(parts[1].trim());

            String logMsg = String.format("ZK_OPCODE_LOG [%s] %s delay (%d)", stage, type, duration);
            switch (type) {
                case "HEAVY":
                     long counter = 0; // Removed invalid 'volatile' modifier
                     duration = 100000;
                    for (long i = 0; i < duration; i++) {
                        counter += i;
                    }
                    // Including 'counter' in the log prevents the JIT compiler from optimizing the loop away
                    LOG.debug(logMsg + " → CPU burn complete (counter: {})", counter);
                    break;
                case "SLOW":
                    duration = 200;
                    Thread.sleep(duration);
                    LOG.debug(logMsg + " → sleep complete");
                    break;
                case "NETWORK":
                    // Simulate network hop: sleep + rename thread to show "network wait"
                    String originalName = Thread.currentThread().getName();
                    duration = 150;
                    Thread.currentThread().setName(originalName + "-NETWORK-WAIT");
                    try {
                        Thread.sleep(duration);
                        LOG.debug(logMsg + " → network RTT simulated");
                    } finally {
                        Thread.currentThread().setName(originalName);
                    }
                    break;
                default:
                    LOG.warn("ZK_OPCODE_LOG [{}] Unknown delay type: {}", stage, type);
            }
        } catch (NumberFormatException | InterruptedException e) {
            LOG.warn("ZK_OPCODE_LOG [{}] Invalid delay spec '{}'", stage, spec, e);
            if (e instanceof InterruptedException) {
                // Restore interrupted status
                Thread.currentThread().interrupt();
            }
        }
    }
}