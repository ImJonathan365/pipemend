package com.pipemend.pipeline.policy;

import com.pipemend.pipeline.triage.contract.Operation;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds and allowlist of the policy (AC-09.4). The defaults live in application.yml and change only with an ADR.
 * The application does not start with a threshold outside [0.5, 1] or a field the catalog does not allow; an unknown
 * operation already fails to bind.
 */
@ConfigurationProperties("pipemend.policy")
public record PolicyProperties(double minConfidence, double enumMinConfidence, Map<Operation, Set<String>> allowlist) {

    static final double LOWEST_THRESHOLD = 0.5;
    static final double HIGHEST_THRESHOLD = 1;

    public PolicyProperties {
        checkThreshold("min-confidence", minConfidence);
        checkThreshold("enum-min-confidence", enumMinConfidence);
        Objects.requireNonNull(allowlist, "pipemend.policy.allowlist is required");
        allowlist.forEach((operation, fields) -> {
            Set<String> allowed = CorrectionCatalog.FIELDS.get(operation);
            for (String field : fields) {
                if (!allowed.contains(field)) {
                    throw new IllegalArgumentException("pipemend.policy.allowlist: docs/02 section 4 does not allow "
                            + operation + " on " + field + " (AC-09.4)");
                }
            }
        });
        allowlist = Map.copyOf(allowlist);
    }

    private static void checkThreshold(String name, double value) {
        // Written this way so that NaN fails too.
        if (!(value >= LOWEST_THRESHOLD && value <= HIGHEST_THRESHOLD)) {
            throw new IllegalArgumentException(
                    "pipemend.policy." + name + " must be within [0.5, 1], got " + value + " (AC-09.4)");
        }
    }
}
