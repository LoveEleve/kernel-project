package com.scalab.tracing;

import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TracingLabApplication {

    private static final Logger log = LoggerFactory.getLogger(TracingLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TracingLabApplication.class, args);
        log.info("[TracingLab] Started on port 28030");
        log.info("[TracingLab] Sampling probability: 1.0, Propagation: W3C+B3");
    }
}
