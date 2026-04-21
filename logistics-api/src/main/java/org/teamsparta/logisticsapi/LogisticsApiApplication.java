package org.teamsparta.logisticsapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LogisticsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LogisticsApiApplication.class, args);
    }
}
