import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public class Workflow {
    static final Map<String, List<String>> GRAPH = new LinkedHashMap<>();
    static {
        GRAPH.put("requirements", List.of());
        GRAPH.put("impact", List.of());
        GRAPH.put("design", List.of("requirements", "impact"));
        GRAPH.put("implementation", List.of("design"));
        GRAPH.put("testing", List.of("implementation"));
        GRAPH.put("documentation", List.of("implementation"));
        GRAPH.put("release", List.of("testing", "documentation"));
    }

    final Path directory;
    final Properties state = new Properties();
    final String scenario;
    int revision;

    Workflow(Path directory, String scenario) throws IOException {
        this.directory = directory;
        this.scenario = scenario;
        Path file = directory.resolve("state.properties");
        if (Files.exists(file)) {
            try (var input = Files.newInputStream(file)) {
                state.load(input);
            }
            if (!scenario.equals(state.getProperty("scenario"))) {
                throw new IllegalArgumentException("Choose a separate directory for each scenario");
            }
        } else {
            state.setProperty("scenario", scenario);
            state.setProperty("revision", "1");
            state.setProperty("started", Long.toString(System.currentTimeMillis()));
            save();
            event("operator", "run-started", "-");
        }
        revision = Integer.parseInt(state.getProperty("revision"));
    }

    void save() throws IOException {
        var output = new java.io.StringWriter();
        state.store(output, "Workflow state");
        atomicWrite(directory.resolve("state.properties"), output.toString());
    }

    static void atomicWrite(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "write-", ".tmp");
        try {
            Files.writeString(temporary, content);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized void event(String actor, String action, String detail) throws IOException {
        String line = String.join("\t", Instant.now().toString(),
                state.getProperty("revision"), actor, action, detail) + "\n";
        Files.writeString(directory.resolve("events.tsv"), line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.print(line);
    }

    Path output(String stage) {
        String owner = state.getProperty("output." + stage, Integer.toString(revision));
        return directory.resolve("revision-" + owner).resolve(stage + ".txt");
    }

    String status(String stage) {
        return state.getProperty("stage." + stage, "pending");
    }

    boolean ready(String stage) {
        return status(stage).equals("pending")
                && GRAPH.get(stage).stream().allMatch(dependency -> status(dependency).equals("done"));
    }

    void mark(String stage, String status) throws IOException {
        state.setProperty("stage." + stage, status);
        save();
    }

    void run() throws IOException {
        try (var executor = Executors.newFixedThreadPool(2)) {
            while (true) {
                var batch = GRAPH.keySet().stream().filter(this::ready).toList();
                if (state.containsKey("source") && status("implementation").equals("done")
                        && !sourceDigest().equals(state.getProperty("source"))) {
                    replan("implementation", "runner");
                    return; // Allow a fresh invocation to inspect the revised plan.
                }
                if (batch.isEmpty()) return;
                if (batch.contains("release") && !releaseApproved()) return;
                var jobs = new LinkedHashMap<String, Future<Boolean>>();
                for (String stage : batch) event("runner", "stage-enter", stage);
                for (String stage : batch) jobs.put(stage, executor.submit(() -> execute(stage)));
                boolean completed = true;
                for (var job : jobs.entrySet()) {
                    String stage = job.getKey();
                    try {
                        if (job.getValue().get()) {
                            if (stage.equals("implementation")) {
                                var manifest = Files.readAllLines(output(stage));
                                state.setProperty("source", manifest.get(0));
                                state.setProperty("artifact", manifest.get(1));
                            }
                            mark(stage, "done");
                            event("runner", "stage-exit", stage);
                            if (stage.equals("release")) {
                                Files.deleteIfExists(directory.resolve("candidate.backup"));
                                terminal(Files.readString(output(stage)).startsWith("Fallback:") ? "fallback" : "success");
                            }
                        } else {
                            completed = false;
                        }
                    } catch (ExecutionException failure) {
                        mark(stage, "failed");
                        var cause = failure.getCause();
                        String detail = String.valueOf(cause.getMessage()).replaceAll("[\\t\\r\\n]", " ");
                        event("runner", "safe-stop", stage + ":" + cause.getClass().getSimpleName() + ":" + detail);
                        terminal("failed");
                        completed = false;
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Workflow interrupted", interrupted);
                    }
                }
                if (!completed) return;
            }
        }
    }

    boolean execute(String stage) throws IOException {
        if (stage.equals("requirements") && scenario.equals("ambiguous") && !state.containsKey("ttl")) {
            event("requirements-agent", "clarification-required",
                    "Confirm demo TTL in seconds; existing links remain perpetual; expired links return HTTP 410");
            return false;
        }
        String result = switch (stage) {
            case "requirements" -> Files.readString(Path.of("docs/requirements.md"))
                    + "\nScenario: " + scenario + (scenario.equals("ambiguous")
                    ? "\nApproved demo TTL: " + state.getProperty("ttl") + " seconds" : "");
            case "impact" -> scenario.equals("greenfield") ? "New service and link store"
                    : "LinkController routes HTTP requests to LinkStore. LinkStore.decode reads legacy links.tsv rows; "
                    + "LinkStore.redirect updates counts and enforces expiry. Validate the existing two-field schema.";
            case "design" -> switch (scenario) {
                case "greenfield" -> "POST /links and GET /r/{code}; random codes; atomic local storage.";
                case "brownfield" -> "Extend stored records with counts; decode legacy rows as zero clicks.";
                default -> "Add optional TTL in seconds; preserve legacy rows; return 410 at expiry.";
            } + "\nInputs: "
                    + output("requirements") + ", " + output("impact");
            case "implementation" -> snapshot();
            case "testing" -> validateSnapshot();
            case "documentation" -> Files.readString(snapshotDirectory().resolve("docs/requirements.md"))
                    + "\nScenario: " + scenario + "\nImplementation digest: " + state.getProperty("source");
            case "release" -> release();
            default -> throw new IllegalArgumentException("Unknown stage");
        };
        atomicWrite(output(stage), result + "\n");
        return true;
    }

    void clarify(String[] args) throws IOException {
        if (!scenario.equals("ambiguous") || args.length != 5
                || !args[3].matches("[1-9][0-9]{0,6}") || Integer.parseInt(args[3]) > 2592000
                || !args[4].matches("[A-Za-z0-9_.@-]{1,80}")) {
            throw new IllegalArgumentException("Use clarify TTL_SECONDS OPERATOR for the ambiguous scenario");
        }
        if (args[3].equals(state.getProperty("ttl"))) throw new IllegalArgumentException("TTL is unchanged");
        state.setProperty("ttl", args[3]);
        state.setProperty("ttl.actor", args[4]);
        replan("requirements", args[4]);
        event(args[4], "clarified-ttl", args[3]);
    }

    void replan(String changed, String actor) throws IOException {
        var invalidated = new LinkedHashSet<String>();
        invalidated.add(changed);
        for (var entry : GRAPH.entrySet()) {
            if (entry.getValue().stream().anyMatch(invalidated::contains)) invalidated.add(entry.getKey());
        }
        for (String stage : GRAPH.keySet()) {
            if (invalidated.contains(stage)) {
                state.remove("stage." + stage);
                state.remove("output." + stage);
            } else if (status(stage).equals("done")) {
                state.putIfAbsent("output." + stage, Integer.toString(revision));
            }
        }
        revision++;
        state.setProperty("revision", Integer.toString(revision));
        state.setProperty("started", Long.toString(System.currentTimeMillis()));
        for (String key : List.of("decision", "approval.digest", "approval.artifact", "approval.actor", "fault")) state.remove(key);
        restoreCandidate();
        save();
        event(actor, "replan", String.join(",", invalidated));
    }

    boolean releaseApproved() throws IOException {
        String decision = state.getProperty("decision", "");
        if (decision.equals("deny")) {
            mark("release", "denied");
            event("runner", "safe-stop", "release-denied");
            terminal("denied");
            return false;
        }
        if (decision.isEmpty()) {
            event("runner", "approval-required", "release");
            return false;
        }
        if (!sourceDigest().equals(state.getProperty("approval.digest"))
                || !sourceDigest(snapshotDirectory()).equals(state.getProperty("approval.digest"))
                || !fileDigest(snapshotDirectory().resolve("shortener.jar")).equals(state.getProperty("approval.artifact"))) {
            mark("release", "failed");
            event("runner", "safe-stop", "source-changed");
            terminal("failed");
            return false;
        }
        return true;
    }

    String release() throws IOException {
        Path candidate = directory.resolve("candidate.txt");
        Path backup = directory.resolve("candidate.backup");
        String absent = "(no previous candidate)\n";
        if (!Files.exists(backup)) {
            atomicWrite(backup, Files.exists(candidate) ? Files.readString(candidate) : absent);
        }
        String fault = state.getProperty("fault", "");
        String receipt = "revision=" + revision + "\nsha256=" + sourceDigest()
                + "\nartifact_sha256=" + state.getProperty("artifact")
                + "\nartifact=" + snapshotDirectory().resolve("shortener.jar")
                + "\napprover=" + state.getProperty("approval.actor") + "\n";
        long started = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (fault.equals("fallback") || (fault.equals("retry") && attempt == 1)) {
                event("runner", "retryable-failure", "release:" + attempt);
                if (attempt == 1) {
                    event("runner", "retry", "release");
                    continue;
                }
                event("runner", "fallback", "release:review-only-receipt");
                return "Fallback: " + receipt;
            }
            try {
                atomicWrite(candidate, receipt);
                if (fault.equals("rollback")) throw new IOException("Simulated post-write failure");
                if (attempt > 1) event("runner", "recovered-ms", Long.toString(System.currentTimeMillis() - started));
                return receipt;
            } catch (IOException failure) {
                restoreCandidate();
                event("runner", "rollback", "release");
                event("runner", "recovered-ms", Long.toString(System.currentTimeMillis() - started));
                throw failure;
            }
        }
        throw new IllegalStateException("Retry limit exceeded");
    }

    void restoreCandidate() throws IOException {
        Path backup = directory.resolve("candidate.backup");
        if (!Files.exists(backup)) return;
        Path candidate = directory.resolve("candidate.txt");
        String previous = Files.readString(backup);
        if (previous.equals("(no previous candidate)\n")) Files.deleteIfExists(candidate);
        else atomicWrite(candidate, previous);
        Files.delete(backup);
    }

    Path snapshotDirectory() {
        return output("implementation").getParent().resolve("source");
    }

    static List<String> sourceFiles(Path root) throws IOException {
        var names = new ArrayList<>(List.of("pom.xml", "tools/ServiceTest.java",
                "tools/Workflow.java", "docs/requirements.md"));
        try (var files = Files.walk(root.resolve("src/main"))) {
            files.filter(Files::isRegularFile)
                    .map(root::relativize).map(Path::toString).sorted().forEach(names::add);
        }
        return names;
    }

    String snapshot() throws IOException {
        Path snapshot = snapshotDirectory();
        for (String name : sourceFiles(Path.of("."))) {
            atomicWrite(snapshot.resolve(name), Files.readString(Path.of(name)));
        }
        var command = new ArrayList<String>();
        String executable = System.getenv().getOrDefault("SHORTENER_MAVEN", "mvn");
        if (System.getProperty("os.name").startsWith("Windows")) {
            command.addAll(List.of("cmd.exe", "/d", "/c"));
            if (executable.equals("mvn")) executable = "mvn.cmd";
        }
        command.addAll(List.of(executable, "-B", "-Dmaven.test.skip=true", "clean", "package"));
        Path report = snapshot.resolve("build.log");
        var process = new ProcessBuilder(command).directory(snapshot.toFile())
                .redirectErrorStream(true).redirectOutput(report.toFile()).start();
        await(process, 300, report);
        Path artifact = snapshot.resolve("shortener.jar");
        Files.copy(snapshot.resolve("target/shortener.jar"), artifact, StandardCopyOption.REPLACE_EXISTING);
        Files.delete(snapshot.resolve("target/shortener.jar"));
        return sourceDigest(snapshot) + "\n" + fileDigest(artifact);
    }

    static void await(Process process, long seconds, Path report) throws IOException {
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                stop(process);
                throw new IOException("Timed out; see " + report);
            }
            if (process.exitValue() != 0) throw new IOException("See " + report);
        } catch (InterruptedException interrupted) {
            stop(process);
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    static String fileDigest(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String validateSnapshot() throws IOException {
        Path report = output("testing").resolveSibling("service-test.log");
        Path snapshot = snapshotDirectory();
        var process = new ProcessBuilder("java", snapshot.resolve("tools/ServiceTest.java").toString(),
                snapshot.resolve("shortener.jar").toString(), scenario, state.getProperty("ttl", "120"))
                .redirectErrorStream(true).redirectOutput(report.toFile()).start();
        await(process, 60, report);
        if (!sourceDigest(snapshot).equals(state.getProperty("source"))
                || !fileDigest(snapshot.resolve("shortener.jar")).equals(state.getProperty("artifact"))) {
            throw new IOException("Snapshot changed during validation");
        }
        return Files.readString(report);
    }

    void terminal(String outcome) throws IOException {
        long elapsed = System.currentTimeMillis() - Long.parseLong(state.getProperty("started"));
        event("runner", "terminal", outcome + ":" + elapsed);
    }

    void metrics() throws IOException {
        int success = 0, retries = 0, rollbacks = 0;
        var completed = new LinkedHashMap<String, String>();
        var recovery = new ArrayList<Long>();
        for (String line : Files.readAllLines(directory.resolve("events.tsv"))) {
            String[] fields = line.split("\t", 5);
            if (fields.length != 5) continue;
            switch (fields[3]) {
                case "terminal" -> completed.put(fields[1], fields[4]);
                case "retry" -> retries++;
                case "rollback" -> rollbacks++;
                case "recovered-ms" -> recovery.add(Long.parseLong(fields[4]));
            }
        }
        var latency = new ArrayList<Long>();
        for (String value : completed.values()) {
            String[] fields = value.split(":");
            if (fields[0].equals("success")) success++;
            latency.add(Long.parseLong(fields[1]));
        }
        System.out.printf("terminal_revisions=%d success=%d success_rate=%s retries=%d rollbacks=%d "
                        + "mean_recovery_ms=%s mean_end_to_end_ms=%s%n",
                completed.size(), success, completed.isEmpty() ? "n/a" : (double) success / completed.size(),
                retries, rollbacks, average(recovery), average(latency));
    }

    static String average(List<Long> samples) {
        var mean = samples.stream().mapToLong(Long::longValue).average();
        return mean.isPresent() ? String.format(Locale.ROOT, "%.2f", mean.getAsDouble()) : "n/a";
    }

    static String sourceDigest() throws IOException {
        return sourceDigest(Path.of("."));
    }

    static String sourceDigest(Path root) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String name : sourceFiles(root)) {
                digest.update(name.replace('\\', '/').getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
                byte[] bytes = Files.readAllBytes(root.resolve(name));
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    void command(String action, String[] args) throws IOException {
        switch (action) {
            case "run" -> run();
            case "metrics" -> metrics();
            case "clarify" -> clarify(args);
            case "refresh" -> {
                if (args.length != 3) throw new IllegalArgumentException("Expected refresh");
                if (!state.containsKey("source")) throw new IllegalStateException("No prior implementation");
                if (sourceDigest().equals(state.getProperty("source"))) {
                    throw new IllegalArgumentException("Source has not changed");
                }
                replan("implementation", "operator");
            }
            case "approve", "deny" -> {
                if (args.length != 4 || !args[3].matches("[A-Za-z0-9_.@-]{1,80}")) {
                    throw new IllegalArgumentException("Supply an operator identifier");
                }
                if (!ready("release") || state.containsKey("decision")) {
                    throw new IllegalArgumentException("Release is not awaiting a decision");
                }
                String digest = sourceDigest();
                if (!digest.equals(state.getProperty("source"))) {
                    throw new IllegalStateException("Source changed after the implementation stage");
                }
                state.setProperty("decision", action);
                String artifact = fileDigest(snapshotDirectory().resolve("shortener.jar"));
                if (!artifact.equals(state.getProperty("artifact"))
                        || !digest.equals(sourceDigest(snapshotDirectory()))) {
                    throw new IllegalStateException("Validated snapshot changed after testing");
                }
                state.setProperty("approval.artifact", artifact);
                state.setProperty("approval.digest", digest);
                state.setProperty("approval.actor", args[3]);
                save();
                event(args[3], action, digest);
            }
            case "inject" -> {
                if (args.length != 4 || !Set.of("retry", "fallback", "rollback").contains(args[3])
                        || !status("release").equals("pending")) {
                    throw new IllegalArgumentException("Inject retry, fallback, or rollback before release");
                }
                state.setProperty("fault", args[3]);
                save();
                event("operator", "fault-configured", args[3]);
            }
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3 || !Set.of("greenfield", "brownfield", "ambiguous").contains(args[0])) {
            throw new IllegalArgumentException("Usage: java tools/Workflow.java SCENARIO RUN_DIRECTORY COMMAND");
        }
        Path directory = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        try (var channel = FileChannel.open(directory.resolve("run.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Workflow already in use");
            new Workflow(directory, args[0]).command(args[2], args);
        }
    }
}
