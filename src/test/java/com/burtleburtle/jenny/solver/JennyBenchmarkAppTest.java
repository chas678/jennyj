package com.burtleburtle.jenny.solver;

import ai.timefold.solver.benchmark.api.PlannerBenchmarkFactory;
import ai.timefold.solver.benchmark.config.PlannerBenchmarkConfig;
import ai.timefold.solver.benchmark.config.SolverBenchmarkConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Smoke test for {@link JennyBenchmarkApp}: verifies the
 * {@link PlannerBenchmarkFactory} construction path works without actually
 * running a benchmark (which takes minutes). Catches regressions in the
 * factory API or solver-config wiring before the user tries the real thing.
 */
class JennyBenchmarkAppTest {

    @Test
    void benchmarkFactoryBuildsSuccessfully() {
        PlannerBenchmarkConfig config = new PlannerBenchmarkConfig()
                .withBenchmarkDirectory(new File("target/benchmark-results-smoke"))
                .withWarmUpSecondsSpentLimit(0L)
                .withSolverBenchmarkConfigList(List.of(
                        new SolverBenchmarkConfig()
                                .withName("smoke")
                                .withSolverConfig(JennySolverFactory.createConfig(20)
                                        .withTerminationConfig(new TerminationConfig()
                                                .withSpentLimit(Duration.ofSeconds(1))))));
        assertNotNull(PlannerBenchmarkFactory.create(config));
    }
}
