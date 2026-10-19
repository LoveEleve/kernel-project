package com.scalab.rocketmq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RocketmqLabApplication {

    private static final Logger log = LoggerFactory.getLogger(RocketmqLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RocketmqLabApplication.class, args);
        log.info("[RocketmqLab] Started on port 28040");
        log.info("[RocketmqLab] NameServer: 21.130.247.89:9876");
    }
}
