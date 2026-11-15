package org.apache.zookeeper.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Research-only delay injector for multi-stage latency attribution. Logs with
 * prefix "ZK_OPCODE_LOG [STAGE]" for easy grepping. Supports: HEAVY
 * (busy-loop), SLOW (Thread.sleep), NETWORK (sleep + thread rename).
 *
 * Activation via per-opcode configuration:
 * -Dzookeeper.test.delayPerOpcode=opcode:category:seconds,opcode:category:seconds
 *
 * Delay categories are numeric: 1 = SLOW 2 = HEAVY 3 = NETWORK
 *
 * Optional stage filter: -Dzookeeper.test.delayStages=STAGE1,STAGE2,... If set,
 * only the listed stages are processed. Default = all stages. Stage names are
 * compared case-insensitively and stored in a Set for uniqueness.
 *
 * By default, if delayPerOpcode is not set or empty, no delays are injected.
 */
public final class DelayInjector {

    private static final Logger LOG = LoggerFactory.getLogger(DelayInjector.class);

    private static final class DelaySpec {

        final int category;
        final float durationSeconds;

        DelaySpec(int category, float durationSeconds) {
            this.category = category;
            this.durationSeconds = durationSeconds;
        }
    }

    private static final Map<Integer, DelaySpec> OPCODE_DELAYS;
    private static final Set<String> ALLOWED_STAGES;

    static {
        // Parse stage filter - default = all stages (empty set)
        Set<String> stages = new HashSet<>();
        String stagesProp = System.getProperty("zookeeper.test.delayStages", "").trim();
        if (!stagesProp.isEmpty()) {
            for (String part : stagesProp.split(",")) {
                String stage = part.trim().toUpperCase();
                if (!stage.isEmpty()) {
                    stages.add(stage); // Set guarantees uniqueness
                }
            }
        }
        ALLOWED_STAGES = Collections.unmodifiableSet(stages);

        Map<Integer, DelaySpec> map = new HashMap<>();
        String prop = System.getProperty("zookeeper.test.delayPerOpcode", "").trim();
        if (!prop.isEmpty()) {
            String[] entries = prop.split(",");
            for (String entry : entries) {
                String[] parts = entry.trim().split(":");
                if (parts.length != 3) {
                    LOG.warn("ZK_OPCODE_LOG Invalid delayPerOpcode entry '{}', expected opcode:category:seconds", entry);
                    continue;
                }
                try {
                    int opcode = Integer.parseInt(parts[0].trim());
                    int category = Integer.parseInt(parts[1].trim());
                    float duration = Float.parseFloat(parts[2].trim());
                    if (duration < 0.0f) {
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

    private static boolean isStageAllowed(String stage) {
        if (ALLOWED_STAGES.isEmpty()) {
            return true; // default = all stages
        }
        return ALLOWED_STAGES.contains(stage.toUpperCase());
    }

    public static void inject(String stage, String spec) {
        inject(stage, spec, (String) null);
    }

    public static void inject(String stage, String spec, Request request) {
        if (request == null) {
            // Stage-only injection without request context – stage alone is meaningless, skip
            return;
        }
        // Stage filter check - opcode takes priority, stage alone has no meaning
        if (!isStageAllowed(stage)) {
            return;
        }
        String opcode = Request.op2String(request.type);
        int opcodeNum = request.type;
        // Always log stage passage for research comparison
        long cpuCycles = Request.getInstructionCount();
        DelaySpec ds = OPCODE_DELAYS.get(opcodeNum);
        if (ds == null) {
            // Normal processing without delay injection - stage filter passes but no opcode config
            LOG.info("ZK_OPCODE_LOG [{}][opcode={}][opcodeNum={}] NO_DELAY cpuCycles={}", stage, opcode, opcodeNum, cpuCycles);
            return;
        }
        // Delay configured – proceed with injection
        injectInternal(stage, opcode, opcodeNum, ds.category, ds.durationSeconds);
    }

    public static void inject(String stage, String spec, String opcode) {
        // Deprecated path without request context – skip
        return;
    }

    private static void injectInternal(String stage, String opcode, int opcodeNum, int category, float durationSeconds) {
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

        long cpuCyclesBefore = Request.getInstructionCount();

        String logMsg = String.format("ZK_OPCODE_LOG [%s][opcode=%s][opcodeNum=%d] %s delay (%.1f) seconds cpuCycles=%d", stage, opcode, opcodeNum, typeName, durationSeconds, cpuCyclesBefore);
        LOG.info(logMsg + " → starting");
        try {
            switch (category) {
                case 2: // HEAVY
                    if (durationSeconds == 0.0f) {
                        LOG.info(logMsg + " → no burn (0 seconds)");
                        break;
                    }
                    long startTime = System.currentTimeMillis();
                    long diffInSeconds = 0;
                    long counter = 0;
                    while (diffInSeconds < durationSeconds) {   // use < instead of <=
                        counter++;
                        diffInSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - startTime);
                    }
                    LOG.info(logMsg + " → CPU burn complete (counter: {})", counter);
                    break;
                case 1: // SLOW
                    try {
                        TimeUnit.SECONDS.sleep((long) durationSeconds);
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
                        TimeUnit.SECONDS.sleep((long) durationSeconds);
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
