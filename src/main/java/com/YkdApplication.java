package com;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class YkdApplication {

    public static void main(String[] args) {
        SpringApplication.run(YkdApplication.class, args);
    }


}
