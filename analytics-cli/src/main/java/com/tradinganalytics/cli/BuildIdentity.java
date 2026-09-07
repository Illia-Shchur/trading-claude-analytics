package com.tradinganalytics.cli;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tradinganalytics.infrastructure.build.BuildIdentityService;

/**
 * Reports the identity of the executable that is actually running.
 *
 * <p>The build marker is generated before packaging and is deliberately
 * separate from the final JAR digest.  That avoids the impossible fixed point
 * of putting a JAR's own digest inside the JAR while still binding the
 * executable to the source/build inputs used to compile it.</p>
 */
final class BuildIdentity {
    private BuildIdentity() {}

    static ObjectNode describe(Class<?> anchor) {
        return BuildIdentityService.describe(anchor);
    }
}
