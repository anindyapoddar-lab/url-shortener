package demo.shortener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkStoreTest {
    private static final String DESTINATION = "https://example.org/docs";

    @TempDir
    Path directory;

    @Test
    void createsDistinctCodesAndPersistsClickCountsAcrossReopen() throws Exception {
        String code;
        String other;
        try (var store = openStore()) {
            code = store.create(DESTINATION, 0);
            other = store.create(DESTINATION, 0);

            assertTrue(code.matches("[A-Za-z0-9_-]{12}"));
            assertNotEquals(code, other);
            assertEquals(new LinkStore.Link(DESTINATION, 0, 0), store.get(code));
            assertNull(store.redirect("UnknownCode1", 100));
            store.redirect(code, 100);
            store.redirect(code, 101);
        }

        try (var reopened = openStore()) {
            assertEquals(new LinkStore.Link(DESTINATION, 2, 0), reopened.get(code));
            assertEquals(new LinkStore.Link(DESTINATION, 0, 0), reopened.get(other));
        }
    }

    @Test
    void readsVersionTwoRecordsAndPersistsTheirFirstClick() throws Exception {
        String code = "LegacyCode01";
        String encoded = Base64.getEncoder().encodeToString(DESTINATION.getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("links.tsv"), code + "\t" + encoded + "\n");

        try (var store = openStore()) {
            assertEquals(new LinkStore.Link(DESTINATION, 0, 0), store.get(code));
            assertEquals(DESTINATION, store.redirect(code, 100).destination());
        }

        try (var reopened = openStore()) {
            assertEquals(new LinkStore.Link(DESTINATION, 1, 0), reopened.get(code));
        }
    }

    @Test
    void stopsCountingAtTheExpirationBoundary() throws Exception {
        try (var store = openStore()) {
            String code = store.create(DESTINATION, 120);
            long expiration = store.get(code).expiresAt();

            store.redirect(code, expiration - 1);
            assertEquals(1, store.get(code).clicks());

            assertTrue(store.redirect(code, expiration).expired(expiration));
            assertEquals(1, store.get(code).clicks());

            store.redirect(code, expiration + 1);
            assertEquals(1, store.get(code).clicks());
        }
    }

    private LinkStore openStore() throws Exception {
        return new LinkStore(directory.toString());
    }
}
