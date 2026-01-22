package com.pobox.chas66;

import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;

/**
 * Test configuration utilities for faster test execution.
 */
public class TestConfig {

    /**
     * Fast solver configuration for unit tests.
     * Uses 5-second timeout instead of production 30-45 seconds.
     */
    public static SolverConfig createFastTestConfig() {
        SolverConfig config = PairwiseSolverFactory.createConfig();
        config.withTerminationConfig(new TerminationConfig()
                .withSecondsSpentLimit(5L)
                .withUnimprovedSecondsSpentLimit(3L));
        return config;
    }

    /**
     * Very fast solver configuration for smoke tests.
     * Uses 2-second timeout for quick validation.
     */
    public static SolverConfig createSmokeFastTestConfig() {
        SolverConfig config = PairwiseSolverFactory.createConfig();
        config.withTerminationConfig(new TerminationConfig()
                .withSecondsSpentLimit(2L)
                .withUnimprovedSecondsSpentLimit(1L));
        return config;
    }
}
