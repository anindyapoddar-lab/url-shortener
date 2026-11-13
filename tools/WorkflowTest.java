import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

public class WorkflowTest {
    static int checks;

    static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    static String call(Path work, String scenario, Path run, String... args) throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of(
                "java", "tools/Workflow.java", scenario, run.toString()));
        command.addAll(java.util.List.of(args));
        Path log = Files.createTempFile(run.getParent(), "command-", ".log");
        var process = new ProcessBuilder(command).directory(work.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(420, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("Workflow timed out");
        }
        String output = Files.readString(log);
        Files.delete(log);
        if (process.exitValue() != 0) throw new AssertionError(output);
        return output;
    }

    static boolean has(String trace, String event, String detail) {
        return trace.contains("\t" + event + "\t" + detail);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("workflow-test-");
        Path work = root.resolve("workspace");
        try {
            try (var files = Files.walk(Path.of("src/main"))) {
                for (Path file : files.toList()) {
                    if (Files.isDirectory(file)) Files.createDirectories(work.resolve(file));
                    else Files.copy(file, work.resolve(file));
                }
            }
            for (String file : java.util.List.of("pom.xml", "tools/Workflow.java",
                    "tools/ServiceTest.java", "docs/requirements.md")) {
                Files.createDirectories(work.resolve(file).getParent());
                Files.copy(Path.of(file), work.resolve(file));
            }
            Path green = root.resolve("green");
            String trace = call(work, "greenfield", green, "run");
            check(has(trace, "approval-required", "release"), "greenfield approval gate");
            check(!Files.exists(green.resolve("candidate.txt")), "no pre-approval candidate");
            check(trace.indexOf("stage-enter\tdocumentation") < trace.indexOf("stage-exit\ttesting"),
                    "parallel fork before join");
            check(Files.readString(green.resolve("revision-1/testing.txt")).contains("PASS 12"), "greenfield tests");
            call(work, "greenfield", green, "inject", "retry");
            call(work, "greenfield", green, "approve", "reviewer");
            trace = call(work, "greenfield", green, "run");
            check(has(trace, "retry", "release"), "bounded retry");
            check(has(trace, "terminal", "success:"), "successful release");
            check(!has(trace, "stage-enter", "testing"), "resume reuses tests");
            check(call(work, "greenfield", green, "metrics").contains("retries=1"), "retry metrics");

            Path brown = root.resolve("brown");
            call(work, "brownfield", brown, "run");
            check(Files.readString(brown.resolve("revision-1/testing.txt")).contains("PASS 15"), "brownfield tests");
            call(work, "brownfield", brown, "deny", "reviewer");
            trace = call(work, "brownfield", brown, "run");
            check(has(trace, "safe-stop", "release-denied"), "deny release");
            check(!Files.exists(brown.resolve("candidate.txt")), "denial preserves candidate absence");

            Path ambiguous = root.resolve("ambiguous");
            trace = call(work, "ambiguous", ambiguous, "run");
            check(has(trace, "clarification-required", "Confirm"), "ambiguous input pauses");
            call(work, "ambiguous", ambiguous, "clarify", "120", "reviewer");
            trace = call(work, "ambiguous", ambiguous, "run");
            check(!has(trace, "stage-enter", "impact"), "reuse unaffected stage");
            check(Files.readString(ambiguous.resolve("revision-2/testing.txt")).contains("PASS 17"), "expiry tests");
            call(work, "ambiguous", ambiguous, "approve", "reviewer");
            call(work, "ambiguous", ambiguous, "run");
            Path candidate = ambiguous.resolve("candidate.txt");
            String previous = Files.readString(candidate);
            call(work, "ambiguous", ambiguous, "clarify", "240", "reviewer");
            trace = call(work, "ambiguous", ambiguous, "run");
            check(has(trace, "approval-required", "release"), "replan clears approval");
            call(work, "ambiguous", ambiguous, "inject", "rollback");
            call(work, "ambiguous", ambiguous, "approve", "reviewer");
            trace = call(work, "ambiguous", ambiguous, "run");
            check(has(trace, "rollback", "release"), "rollback event");
            check(Files.readString(candidate).equals(previous), "rollback restores previous receipt");
            check(call(work, "ambiguous", ambiguous, "metrics").contains("rollbacks=1"), "rollback metrics");

            call(work, "ambiguous", ambiguous, "clarify", "360", "reviewer");
            call(work, "ambiguous", ambiguous, "run");
            call(work, "ambiguous", ambiguous, "inject", "fallback");
            call(work, "ambiguous", ambiguous, "approve", "reviewer");
            trace = call(work, "ambiguous", ambiguous, "run");
            check(has(trace, "terminal", "fallback:"), "fallback after retry limit");
            check(Files.readString(candidate).equals(previous), "fallback leaves candidate intact");

            Files.writeString(work.resolve("src/main/java/demo/shortener/ShortenerApplication.java"), "\n// Revised upstream input\n", StandardOpenOption.APPEND);
            trace = call(work, "ambiguous", ambiguous, "run");
            check(has(trace, "replan", "implementation,testing,documentation,release"), "source drift triggers replan");
            System.out.println("PASS " + checks + " workflow checks");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
