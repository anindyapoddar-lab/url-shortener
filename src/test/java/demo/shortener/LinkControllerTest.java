package demo.shortener;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LinkControllerTest {
    @TempDir Path directory;

    @Test
    void createsRedirectsAndCountsOnlyGetRequests() throws Exception {
        try (var store = new LinkStore(directory.toString())) {
            var mvc = standaloneSetup(new LinkController(store)).build();
            var result = mvc.perform(post("/links").contentType("text/plain")
                            .content("https://example.org/item"))
                    .andExpect(status().isCreated()).andReturn();
            String code = result.getResponse().getContentAsString().strip();
            assertEquals("/r/" + code, result.getResponse().getHeader("Location"));
            mvc.perform(head("/r/" + code)).andExpect(status().isFound());
            mvc.perform(get("/r/" + code)).andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.org/item"));
            mvc.perform(get("/links/" + code + "/analytics")).andExpect(content().string("1\n"));
        }
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        try (var store = new LinkStore(directory.toString())) {
            var mvc = standaloneSetup(new LinkController(store)).build();
            mvc.perform(post("/links").content("file:///tmp")).andExpect(status().isBadRequest());
            mvc.perform(post("/links").content("x".repeat(2049))).andExpect(status().isPayloadTooLarge());
            mvc.perform(post("/links?ttl=0").content("https://example.org")).andExpect(status().isBadRequest());
        }
    }

    @Test
    void returnsGoneForExpiredLinksAndKeepsAnalyticsAvailable() throws Exception {
        String code = "ExpiredCode1";
        String encoded = Base64.getEncoder().encodeToString("https://example.org".getBytes(StandardCharsets.UTF_8));
        Files.writeString(directory.resolve("links.tsv"), code + "\t" + encoded + "\t3\t1\n");

        try (var store = new LinkStore(directory.toString())) {
            var mvc = standaloneSetup(new LinkController(store)).build();
            mvc.perform(get("/r/" + code)).andExpect(status().isGone())
                    .andExpect(header().doesNotExist("Location"));
            mvc.perform(get("/links/" + code + "/analytics"))
                    .andExpect(status().isOk()).andExpect(content().string("3\n"));
        }
    }

    @Test
    void returnsNotFoundForUnknownCodes() throws Exception {
        try (var store = new LinkStore(directory.toString())) {
            var mvc = standaloneSetup(new LinkController(store)).build();
            mvc.perform(get("/r/UnknownCode1")).andExpect(status().isNotFound());
            mvc.perform(get("/links/UnknownCode1/analytics")).andExpect(status().isNotFound());
        }
    }
}
