# Spring Boot URL Shortener

Run with Java 21 and Maven.

```sh
mvn package
java -jar target/shortener.jar
```

The service listens on 127.0.0.1:8080. Check it with
`curl http://127.0.0.1:8080/health` (use `curl.exe` on Windows).
