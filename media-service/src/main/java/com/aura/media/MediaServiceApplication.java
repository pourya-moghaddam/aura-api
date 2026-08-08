package com.aura.media;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Async and scheduling are both on from the start: post-upload work (validation, scanning,
 * thumbnails) must never run on the request thread, and abandoned uploads need reaping.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class MediaServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(MediaServiceApplication.class, args);
    }
}
