package demo.shortener;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
class LinkController {
    private final LinkStore store;

    LinkController(LinkStore store) {
        this.store = store;
    }

    @PostMapping(value = "/links", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(2049);
        if (body.length > 2048) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String destination = validate(new String(body, StandardCharsets.UTF_8).strip());
        String code = store.create(destination);
        return ResponseEntity.created(URI.create("/r/" + code)).body(code + "\n");
    }

    @GetMapping("/r/{code:[A-Za-z0-9_-]{12}}")
    ResponseEntity<Void> redirect(@PathVariable String code) throws IOException {
        var link = store.get(code);
        if (link == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, link.destination())
                .cacheControl(CacheControl.noStore()).build();
    }

    static String validate(String input) {
        try {
            URI uri = URI.create(input);
            if (("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() <= 65535) {
                return uri.toASCIIString();
            }
        } catch (IllegalArgumentException invalidUri) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid destination");
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected an HTTP(S) destination");
    }
}
