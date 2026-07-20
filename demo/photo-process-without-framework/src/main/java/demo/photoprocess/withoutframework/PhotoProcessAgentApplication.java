package demo.photoprocess.withoutframework;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entrypoint for the without-framework photo-process hosted agent. The app logic lives
 * in {@link PhotoProcessWorkflow}; the hosted-agent wire contract is hand-written in
 * {@link PhotoProcessController} (+ {@link ModelClient}, {@link ResponsesJson}).
 */
@SpringBootApplication
public class PhotoProcessAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PhotoProcessAgentApplication.class, args);
    }
}
