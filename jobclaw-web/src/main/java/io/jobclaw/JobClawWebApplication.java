package io.jobclaw;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "io.jobclaw")
@EnableScheduling
@EnableAsync
public class JobClawWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobClawWebApplication.class, args);
    }
}
