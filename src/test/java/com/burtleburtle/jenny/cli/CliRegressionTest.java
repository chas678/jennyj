package com.burtleburtle.jenny.cli;

import com.burtleburtle.jenny.domain.Dimension;
import com.burtleburtle.jenny.domain.Without;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRegressionTest {

    private static final Pattern TEST_LINE =
            Pattern.compile(" (?:(\\d+)([a-zA-Z]) )+");
    private static final Pattern FEATURE = Pattern.compile("(\\d+)([a-zA-Z])");

    @Test
    void three_binary_dims_n2_covers_all_pairs() {
        CliResult result = runJenny("-n2", "3", "3", "3");

        assertEquals(0, result.exitCode);
        assertFalse(result.stdout.contains("Could not cover tuple"),
                "all tuples should have been coverable");

        List<Map<Integer, Character>> tests = parseTests(result.stdout);
        for (int d1 = 0; d1 < 3; d1++) {
            for (int d2 = d1 + 1; d2 < 3; d2++) {
                for (char f1 = 'a'; f1 < 'd'; f1++) {
                    for (char f2 = 'a'; f2 < 'd'; f2++) {
                        boolean covered = covers(tests, d1, f1, d2, f2);
                        assertTrue(covered,
                                "pair (" + (d1 + 1) + f1 + ", " + (d2 + 1) + f2
                                        + ") not covered");
                    }
                }
            }
        }
    }

    @Test
    void withouts_are_never_violated_in_output() {
        CliResult result = runJenny("-n2", "2", "2", "2", "-w1a2a");

        assertEquals(0, result.exitCode);
        List<Map<Integer, Character>> tests = parseTests(result.stdout);
        for (Map<Integer, Character> test : tests) {
            assertFalse(test.get(1) == 'a' && test.get(2) == 'a',
                    "test " + test + " violates -w1a2a");
        }
    }

    @Test
    void over_restricted_input_reports_uncoverable_tuple() {
        // 3 binary dims, n=2, -w3ab: forbids every value of dim 3. No legal
        // test exists, yet pair-tuples over (dim1, dim2) still pass the Without
        // filter (withouts don't mention those dims). They can never be
        // covered by any legal test → jenny reports "Could not cover tuple".
        CliResult result = runJenny("-n2", "2", "2", "2", "-w3ab");

        assertTrue(result.stdout.contains("Could not cover tuple"),
                "uncoverable input should emit a \"Could not cover tuple\" line; got:\n"
                        + result.stdout);
    }

    @Test
    void output_format_is_jenny_compatible() {
        CliResult result = runJenny("-n2", "2", "2");

        String[] lines = result.stdout.split("\\n");
        assertTrue(lines.length > 0);
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("Could not cover")) continue;
            assertTrue(line.startsWith(" "),
                    "test line must start with a space: [" + line + "]");
            assertTrue(line.endsWith(" "),
                    "test line must end with a space: [" + line + "]");
            assertTrue(TEST_LINE.matcher(line).matches(),
                    "test line must match the jenny format: [" + line + "]");
        }
    }

    private record CliResult(int exitCode, String stdout) {
    }

    private static CliResult runJenny(String... args) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);
        JennyCli cli = new JennyCli();
        cli.setOut(out);
        int exit = new CommandLine(cli)
                .execute(withTimeBudget(args));
        return new CliResult(exit, buf.toString(StandardCharsets.UTF_8));
    }

    private static String[] withTimeBudget(String[] original) {
        List<String> result = new ArrayList<>(original.length + 2);
        for (String a : original) {
            result.add(a);
        }
        result.add("--time-limit-seconds");
        result.add("3");
        return result.toArray(String[]::new);
    }

    private static List<Map<Integer, Character>> parseTests(String stdout) {
        List<Map<Integer, Character>> tests = new ArrayList<>();
        for (String line : stdout.split("\\n")) {
            if (line.isEmpty() || !line.startsWith(" ") || line.startsWith("Could")) {
                continue;
            }
            Map<Integer, Character> test = new LinkedHashMap<>();
            Matcher m = FEATURE.matcher(line);
            while (m.find()) {
                test.put(Integer.parseInt(m.group(1)), m.group(2).charAt(0));
            }
            tests.add(test);
        }
        return tests;
    }

    private static boolean covers(List<Map<Integer, Character>> tests,
                                  int d1Zero, char f1, int d2Zero, char f2) {
        for (Map<Integer, Character> t : tests) {
            if (Character.valueOf(f1).equals(t.get(d1Zero + 1))
                    && Character.valueOf(f2).equals(t.get(d2Zero + 1))) {
                return true;
            }
        }
        return false;
    }
}
