package org.apache.zookeeper.server;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Research-only delay injector for multi-stage latency attribution. Logs with
 * prefix "ZK_OPCODE_LOG [STAGE]" for easy grepping. Supports: HEAVY
 * (busy-loop), SLOW (Thread.sleep), NETWORK (sleep + thread rename).
 */
public final class DelayInjector {

    private static final Logger LOG = LoggerFactory.getLogger(DelayInjector.class);

    private DelayInjector() {
    }

    public static void inject(String stage, String spec) {
        inject(stage, spec, (String) null);
    }

    public static void inject(String stage, String spec, Request request) {
        String opcode = (request != null) ? Request.op2String(request.type) : "UNKNOWN";
        inject(stage, spec, opcode);
    }

    public static void inject(String stage, String spec, String opcode) {
        if (spec == null || spec.trim().isEmpty()) {
            return;
        }

        String[] parts = spec.trim().split(":", 2);
        if (parts.length != 2) {
            LOG.warn("ZK_OPCODE_LOG [{}] Invalid delay spec '{}' - expected TYPE:DURATION", stage, spec);
            return;
        }

        String type = parts[0].trim().toUpperCase();
        long durationMinutes;
        try {
            durationMinutes = Long.parseLong(parts[1].trim());
            if (durationMinutes < 0) {
                LOG.warn("ZK_OPCODE_LOG [{}] Negative duration in spec '{}'", stage, spec);
                return;
            }
        } catch (NumberFormatException e) {
            LOG.warn("ZK_OPCODE_LOG [{}] Invalid duration in delay spec '{}'", stage, spec, e);
            return;
        }

        String logMsg = String.format("ZK_OPCODE_LOG [%s][opcode=%s] %s delay (%d) minutes", stage, opcode, type, durationMinutes);
        LOG.info(logMsg + " → starting");
        try {
            switch (type) {
                case "HEAVY":
                    long startTime = System.currentTimeMillis();
                    long diffInMinutes = 0;
                    long counter = 0;
                    while (diffInMinutes <= durationMinutes) {
                        counter++;
                        diffInMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - startTime);
                    }
                    LOG.info(logMsg + " → CPU burn complete (counter: {})", counter);
                    break;
                case "SLOW":
                    try {
                        TimeUnit.MINUTES.sleep(durationMinutes);
                        LOG.info(logMsg + " → sleep complete");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn(logMsg + " → sleep interrupted", e);
                    }
                    break;
                case "NETWORK":
                    String originalName = Thread.currentThread().getName();
                    Thread.currentThread().setName(originalName + "-NETWORK-WAIT");
                    try {
                        TimeUnit.MINUTES.sleep(durationMinutes);
                        LOG.info(logMsg + " → network RTT simulated");
                    } finally {
                        Thread.currentThread().setName(originalName);
                    }
                    break;
                default:
                    LOG.warn("ZK_OPCODE_LOG [{}][opcode={}] Unknown delay type: {}", stage, opcode, type);
            }
        } catch (InterruptedException e) {
            LOG.warn("ZK_OPCODE_LOG [{}][opcode={}] Delay interrupted", stage, opcode, e);
            Thread.currentThread().interrupt();
        }
    }
}
