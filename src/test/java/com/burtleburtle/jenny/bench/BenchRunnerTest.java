package com.burtleburtle.jenny.bench;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenchRunnerTest {

    @Test
    void countTestLines_ignores_uncovered_tuple_lines_and_empty_lines() {
        String stdout = "Could not cover tuple 1a 2b\n 1a 2b 3a \n 1b 2a 3b \n\n";

        assertEquals(2, BenchRunner.countTestLines(stdout));
    }

    @Test
    void countTestLines_counts_leading_space_lines() {
        String stdout = " 1a 2b \n 1b 2a \n 1a 2a \n 1b 2b \n";

        assertEquals(4, BenchRunner.countTestLines(stdout));
    }

    @Test
    void printComparison_lays_out_two_row_table() {
        BenchRunner.Result c = new BenchRunner.Result(45, 12L, "");
        BenchRunner.Result tf = new BenchRunner.Result(43, 5007L, "");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

        new BenchRunner(Path.of("/dev/null")).printComparison(out, c, tf);

        String rendered = buf.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("tests"));
        assertTrue(rendered.contains("wall_ms"));
        assertTrue(rendered.contains("jenny-c"));
        assertTrue(rendered.contains("timefold"));
        assertTrue(rendered.contains("45"));
        assertTrue(rendered.contains("43"));
    }

    @Test
    void runJennyC_delegates_to_injected_forker_with_full_command() throws Exception {
        BenchRunner.ProcessForker forker = mock(BenchRunner.ProcessForker.class);
        when(forker.run(any(), anyLong()))
                .thenReturn(new BenchRunner.Result(7, 42L, " 1a 2b 3c \n"));
        Path fakeBinary = Path.of("/opt/bin/jenny");
        BenchRunner runner = new BenchRunner(fakeBinary, forker);

        BenchRunner.Result result = runner.runJennyC(List.of("-n2", "2", "2", "2"), 30L);

        assertEquals(7, result.tests());
        assertEquals(42L, result.wallMillis());
        verify(forker).run(List.of("/opt/bin/jenny", "-n2", "2", "2", "2"), 30L);
    }
}
