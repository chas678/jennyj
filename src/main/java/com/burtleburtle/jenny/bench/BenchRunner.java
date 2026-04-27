package com.burtleburtle.jenny.bench;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.burtleburtle.jenny.cli.JennyCli;
import picocli.CommandLine;

/**
 * Head-to-head runner: forks the C jenny binary, times this solver
 * in-process, and prints a two-row comparison table:
 *
 * <pre>
 *          tests    wall_ms
 * jenny-c     45         12
 * timefold    43       5007
 * </pre>
 *
 * The C binary path defaults to {@code $HOME/src/jenny/jenny}; override
 * via {@code JENNY_BIN} or a {@code --jenny-path} argument that the
 * caller strips before passing the rest to this class.
 */
public final class BenchRunner {

    public record Result(int tests, long wallMillis, String stdout) {
    }

    private final Path jennyBinary;
    private final ProcessForker forker;

    public BenchRunner(Path jennyBinary, ProcessForker forker) {
        this.jennyBinary = jennyBinary;
        this.forker = forker;
    }

    public BenchRunner(Path jennyBinary) {
        this(jennyBinary, BenchRunner::defaultForker);
    }

    /** Strategy for running an external process — mocked in tests. */
    @FunctionalInterface
    public interface ProcessForker {
        Result run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException;
    }

    public Result runJennyC(List<String> jennyArgs, long timeoutSeconds)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(jennyArgs.size() + 1);
        command.add(jennyBinary.toString());
        command.addAll(jennyArgs);
        return forker.run(command, timeoutSeconds);
    }

    public Result runTimefold(List<String> jennyArgs) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream captured = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        JennyCli cli = new JennyCli();
        cli.setOut(captured);
        long t0 = System.nanoTime();
        new CommandLine(cli).execute(jennyArgs.toArray(String[]::new));
        long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        String out = buffer.toString(StandardCharsets.UTF_8);
        return new Result(countTestLines(out), wallMs, out);
    }

    public void printComparison(PrintStream out, Result jennyC, Result timefold) {
        out.printf("         %8s  %9s%n", "tests", "wall_ms");
        out.printf("jenny-c  %8d  %9d%n", jennyC.tests(), jennyC.wallMillis());
        out.printf("timefold %8d  %9d%n", timefold.tests(), timefold.wallMillis());
    }

    static int countTestLines(String stdout) {
        int count = 0;
        for (String line : stdout.split("\\n", -1)) {
            if (!line.isEmpty() && line.charAt(0) == ' '
                    && !line.startsWith("Could not cover tuple")) {
                count++;
            }
        }
        return count;
    }

    private static Result defaultForker(List<String> command, long timeoutSeconds)
            throws IOException, InterruptedException {
        long t0 = System.nanoTime();
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("jenny-c timed out after " + timeoutSeconds + "s");
            }
            long wallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            String stdout = sb.toString();
            return new Result(countTestLines(stdout), wallMs, stdout);
        }
    }

    public static Path resolveJennyPath(String explicit) {
        if (explicit != null && !explicit.isEmpty()) {
            return Path.of(explicit);
        }
        String env = System.getenv("JENNY_BIN");
        if (env != null && !env.isEmpty()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home"), "src", "jenny", "jenny");
    }

    public static boolean jennyBinaryExists(Path p) {
        return p != null && Files.isExecutable(p);
    }
}
