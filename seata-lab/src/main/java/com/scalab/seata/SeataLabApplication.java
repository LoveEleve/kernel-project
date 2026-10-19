package com.scalab.seata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SeataLabApplication {

    private static final Logger log = LoggerFactory.getLogger(SeataLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(SeataLabApplication.class, args);
        log.info("[SeataLab] Started on port 28041");
        log.info("[SeataLab] Seata TC: 21.130.247.89:27091");
    }
}
