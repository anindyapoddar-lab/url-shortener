package demo.shortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class ShortenerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShortenerApplication.class, args);
    }

    @EventListener
    public void listening(WebServerInitializedEvent event) {
        System.out.println("Listening on http://127.0.0.1:" + event.getWebServer().getPort());
    }
}
