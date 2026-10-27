package org.apache.zookeeper.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Research-only delay injector for multi-stage latency attribution. Logs with
 * prefix "ZK_OPCODE_LOG [STAGE]" for easy grepping. Supports: HEAVY
 * (busy-loop), SLOW (Thread.sleep), NETWORK (sleep + thread rename).
 *
 * Activation via per-opcode configuration:
 * -Dzookeeper.test.delayPerOpcode=opcode:category:minutes,opcode:category:minutes
 *
 * Delay categories are numeric: 1 = SLOW 2 = HEAVY 3 = NETWORK
 *
 * By default, if delayPerOpcode is not set or empty, no delays are injected.
 */
public final class DelayInjector {

    private static final Logger LOG = LoggerFactory.getLogger(DelayInjector.class);

    private static final class DelaySpec {

        final int category;
        final long durationMinutes;

        DelaySpec(int category, long durationMinutes) {
            this.category = category;
            this.durationMinutes = durationMinutes;
        }
    }

    private static final Map<Integer, DelaySpec> OPCODE_DELAYS;

    static {
        Map<Integer, DelaySpec> map = new HashMap<>();
        String prop = System.getProperty("zookeeper.test.delayPerOpcode", "").trim();
        if (!prop.isEmpty()) {
            String[] entries = prop.split(",");
            for (String entry : entries) {
                String[] parts = entry.trim().split(":");
                if (parts.length != 3) {
                    LOG.warn("ZK_OPCODE_LOG Invalid delayPerOpcode entry '{}', expected opcode:category:minutes", entry);
                    continue;
                }
                try {
                    int opcode = Integer.parseInt(parts[0].trim());
                    int category = Integer.parseInt(parts[1].trim());
                    long duration = Long.parseLong(parts[2].trim());
                    if (duration < 0) {
                        LOG.warn("ZK_OPCODE_LOG Negative duration for opcode {} in delayPerOpcode", opcode);
                        continue;
                    }
                    if (category < 1 || category > 3) {
                        LOG.warn("ZK_OPCODE_LOG Unknown category {} for opcode {} in delayPerOpcode", category, opcode);
                        continue;
                    }
                    map.put(opcode, new DelaySpec(category, duration));
                } catch (NumberFormatException e) {
                    LOG.warn("ZK_OPCODE_LOG Invalid delayPerOpcode entry '{}'", entry, e);
                }
            }
        }
        OPCODE_DELAYS = Collections.unmodifiableMap(map);
    }

    private DelayInjector() {
    }

    public static void inject(String stage, String spec) {
        inject(stage, spec, (String) null);
    }

    public static void inject(String stage, String spec, Request request) {
        if (OPCODE_DELAYS.isEmpty()) {
            return;
        }
        if (request == null) {
            // Stage-only injection without request context – skip
            return;
        }
        DelaySpec ds = OPCODE_DELAYS.get(request.type);
        if (ds == null) {
            return;
        }
        String opcode = Request.op2String(request.type);
        injectInternal(stage, opcode, request.type, ds.category, ds.durationMinutes);
    }

    public static void inject(String stage, String spec, String opcode) {
        // Deprecated path without request context – skip
        return;
    }

    private static void injectInternal(String stage, String opcode, int opcodeNum, int category, long durationMinutes) {
        String typeName;
        switch (category) {
            case 1:
                typeName = "SLOW";
                break;
            case 2:
                typeName = "HEAVY";
                break;
            case 3:
                typeName = "NETWORK";
                break;
            default:
                LOG.warn("ZK_OPCODE_LOG [{}][opcode={}] Unknown delay category: {}", stage, opcode, category);
                return;
        }

        String logMsg = String.format("ZK_OPCODE_LOG [%s][opcode=%s][opcodeNum=%d] %s delay (%d) minutes", stage, opcode, opcodeNum, typeName, durationMinutes);
        LOG.info(logMsg + " → starting");
        try {
            switch (category) {
                case 2: // HEAVY
                    long startTime = System.currentTimeMillis();
                    long diffInMinutes = 0;
                    long counter = 0;
                    while (diffInMinutes <= durationMinutes) {
                        counter++;
                        diffInMinutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - startTime);
                    }
                    LOG.info(logMsg + " → CPU burn complete (counter: {})", counter);
                    break;
                case 1: // SLOW
                    try {
                        TimeUnit.MINUTES.sleep(durationMinutes);
                        LOG.info(logMsg + " → sleep complete");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOG.warn(logMsg + " → sleep interrupted", e);
                    }
                    break;
                case 3: // NETWORK
                    String originalName = Thread.currentThread().getName();
                    Thread.currentThread().setName(originalName + "-NETWORK-WAIT");
                    try {
                        TimeUnit.MINUTES.sleep(durationMinutes);
                        LOG.info(logMsg + " → network RTT simulated");
                    } finally {
                        Thread.currentThread().setName(originalName);
                    }
                    break;
            }
        } catch (InterruptedException e) {
            LOG.warn("ZK_OPCODE_LOG [{}][opcode={}] Delay interrupted", stage, opcode, e);
            Thread.currentThread().interrupt();
        }
    }
}
