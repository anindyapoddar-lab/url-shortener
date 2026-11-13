import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class ServiceTest {
    static final String LEGACY = "LegacyCode01";
    static final String EXPIRED = "ExpiredCode1";
    static final String TARGET = "https://example.com/path?x=1";
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();
    static int checks;

    static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    static HttpResponse<String> request(String base, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3));
        if (body != null) builder.header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static void withServer(Path source, Path data, ServerChecks test) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add("java");
        command.add("-jar");
        command.addAll(java.util.List.of(source.toString(), "--server.port=0", "--shortener.data-dir=" + data, "--logging.level.root=WARN"));
        Process server = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var reader = new BufferedReader(new InputStreamReader(server.getInputStream()))) {
            String base = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("Listening on ")) return line.substring(13);
                        System.err.println(line);
                    }
                    throw new IllegalStateException("Server exited before binding");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }).get(15, TimeUnit.SECONDS);
            test.run(base);
        } finally {
            server.destroy();
            if (!server.waitFor(3, TimeUnit.SECONDS)) server.destroyForcibly().waitFor();
        }
    }

    interface ServerChecks {
        void run(String base) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args.length > 0 ? args[0] : "target/shortener.jar").toAbsolutePath();
        String scenario = args.length > 1 ? args[1] : "all";
        Path data = Files.createTempDirectory("shortener-test-");
        try {
            String encoded = java.util.Base64.getEncoder().encodeToString(TARGET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.writeString(data.resolve("links.tsv"), LEGACY + "\t" + encoded + "\n"
                    + EXPIRED + "\t" + encoded + "\t0\t" + Instant.now().getEpochSecond() + "\n");
            String[] created = new String[1];
            withServer(source, data, base -> {
                check(request(base, "/health", null).statusCode() == 200, "health");
                check(request(base, "/links", "file:///tmp/data").statusCode() == 400, "unsupported scheme");
                check(request(base, "/links", "https://u:p@example.com").statusCode() == 400, "userinfo");
                check(request(base, "/links", "x".repeat(2049)).statusCode() == 413, "body limit");
                check(request(base, "/r/000000000000", null).statusCode() == 404, "unknown code");
                check(request(base, "/links", null).statusCode() == 405, "method restriction");
                var response = request(base, "/links", TARGET);
                check(response.statusCode() == 201, "create");
                created[0] = response.body().strip();
                check(created[0].matches("[A-Za-z0-9_-]{12}"), "code format");
                check(response.headers().firstValue("Location").orElseThrow().equals("/r/" + created[0]), "create location");
                var redirect = request(base, "/r/" + created[0], null);
                check(redirect.statusCode() == 302, "redirect");
                check(redirect.headers().firstValue("Location").orElseThrow().equals(TARGET), "redirect location");
                if (scenario.equals("all") || scenario.equals("brownfield")) {
                    check(request(base, "/links/" + LEGACY + "/analytics", null).body().strip().equals("0"), "legacy initial count");
                    check(request(base, "/r/" + LEGACY, null).statusCode() == 302, "legacy redirect");
                    check(request(base, "/links/" + LEGACY + "/analytics", null).body().strip().equals("1"), "legacy increment");
                }
                if (scenario.equals("all") || scenario.equals("ambiguous")) {
                    String ttl = args.length > 2 ? args[2] : "120";
                    check(request(base, "/links?ttl=" + ttl, TARGET).statusCode() == 201, "approved TTL");
                    check(request(base, "/links?ttl=0", TARGET).statusCode() == 400, "TTL bounds");
                    check(request(base, "/r/" + EXPIRED, null).statusCode() == 410, "expired link");
                    check(request(base, "/links/" + EXPIRED + "/analytics", null).body().strip().equals("0"), "expired click excluded");
                    check(request(base, "/r/" + LEGACY, null).statusCode() == 302, "legacy remains perpetual");
                }
            });
            withServer(source, data, base ->
                    check(request(base, "/r/" + created[0], null).statusCode() == 302, "persisted across restart"));
            System.out.println("PASS " + checks + " service checks (" + scenario + ")");
        } finally {
            try (var files = Files.walk(data)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
