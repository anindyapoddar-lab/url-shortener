package demo.shortener;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@org.springframework.stereotype.Repository
class LinkStore implements AutoCloseable {
    record Link(String destination, long clicks) {}
    private final Path file;
    private final FileChannel lockChannel;
    private final Map<String, Link> links = new HashMap<>();
    private final SecureRandom random = new SecureRandom();

    LinkStore(@org.springframework.beans.factory.annotation.Value("${shortener.data-dir}") String dataDirectory) throws IOException {
        Path directory = Path.of(dataDirectory).toAbsolutePath();
        Files.createDirectories(directory);
        file = directory.resolve("links.tsv");
        lockChannel = FileChannel.open(directory.resolve("store.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (lockChannel.tryLock() == null) throw new IOException("Store is already in use");
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String[] fields = line.split("\t", -1);
                    links.put(fields[0], decode(fields));
                }
            }
        } catch (IOException | RuntimeException error) {
            lockChannel.close();
            throw error;
        }
    }

    synchronized String create(String destination) throws IOException {
        byte[] bytes = new byte[9];
        String code;
        do {
            random.nextBytes(bytes);
            code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (links.containsKey(code));
        save(code, new Link(destination, 0));
        return code;
    }

    synchronized Link get(String code) {
        return links.get(code);
    }

    synchronized Link redirect(String code) throws IOException {
        Link link = links.get(code);
        if (link == null) return null;
        save(code, new Link(link.destination(), Math.incrementExact(link.clicks())));
        return link;
    }

    private void save(String code, Link link) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "links-", ".tmp");
        try {
            StringBuilder rows = new StringBuilder();
            links.forEach((key, existing) -> {
                if (!key.equals(code)) rows.append(encode(key, existing)).append('\n');
            });
            rows.append(encode(code, link)).append('\n');
            Files.writeString(temporary, rows);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            links.put(code, link);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String encode(String code, Link link) {
        return code + "\t" + Base64.getEncoder().encodeToString(link.destination().getBytes(StandardCharsets.UTF_8)) + "\t" + link.clicks();
    }

    private static Link decode(String[] fields) {
        return new Link(new String(Base64.getDecoder().decode(fields[1]), StandardCharsets.UTF_8),
                fields.length >= 3 ? Long.parseLong(fields[2]) : 0);
    }

    public void close() throws IOException {
        lockChannel.close();
    }
}

