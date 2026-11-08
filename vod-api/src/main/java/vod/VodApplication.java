package vod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VodApplication {
    public static void main(String[] args) {
        SpringApplication.run(VodApplication.class, args);
    }
}
