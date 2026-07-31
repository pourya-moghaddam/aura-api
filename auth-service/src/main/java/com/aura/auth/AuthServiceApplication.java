package com.aura.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}