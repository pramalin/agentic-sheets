package com.alai.agenticsheets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Step 2 was a health endpoint and nothing else. Step 4 adds the first
 * real component: {@code CanonicalModelRegistry}, which needs
 * {@code @EnableScheduling} for its periodic config reload. See the repo
 * README's roadmap for the full sequence.
 */
@SpringBootApplication
@EnableScheduling
public class AgenticSheetsApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgenticSheetsApplication.class, args);
    }
}
