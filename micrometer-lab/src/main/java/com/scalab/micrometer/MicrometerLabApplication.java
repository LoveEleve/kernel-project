package com.scalab.micrometer;

import io.micrometer.core.aop.CountedAspect;
import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;

import com.scalab.micrometer.b.B01UptimeBinder;
import com.scalab.micrometer.b.B03LoggingObservationHandler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MicrometerLabApplication {

    private static final Logger log = LoggerFactory.getLogger(MicrometerLabApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(MicrometerLabApplication.class, args);
        log.info("[MicrometerLab] Started on port 28020");
    }

    /** 手动注册 TimedAspect——A07 @Timed 注解需要此切面（对照 D4 §二） */
    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    /** 手动注册 CountedAspect——A08 @Counted 注解需要此切面（对照 D4 §四） */
    @Bean
    public CountedAspect countedAspect(MeterRegistry registry) {
        return new CountedAspect(registry);
    }

    /** 手动注册 ObservedAspect——A09 @Observed 注解需要此切面（对照 D3 §十四） */
    @Bean
    public ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /** B01 自定义 MeterBinder（对照 D2 §一） */
    @Bean
    public MeterBinder uptimeBinder() {
        return new B01UptimeBinder();
    }

    /** B03 自定义 ObservationHandler（对照 D3 §三） */
    @Bean
    public B03LoggingObservationHandler loggingObservationHandler() {
        return new B03LoggingObservationHandler();
    }

    /** 应用就绪后注册 ObservationHandler——避免循环依赖 */
    @EventListener(ApplicationReadyEvent.class)
    public void registerObservationHandler(ApplicationReadyEvent event) {
        ObservationRegistry registry = event.getApplicationContext().getBean(ObservationRegistry.class);
        B03LoggingObservationHandler handler = event.getApplicationContext().getBean(B03LoggingObservationHandler.class);
        registry.observationConfig().observationHandler(handler);
        log.info("[MicrometerLab] B03 ObservationHandler registered");
    }
}
