package demo.photoprocess.withframework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entrypoint for the with-framework photo-process hosted agent. All the hosted-agent
 * behavior lives in {@link PhotoProcessConfiguration} beans; there is no hand-written controller.
 */
@SpringBootApplication
public class PhotoProcessAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhotoProcessAgentApplication.class, args);
    }
}
