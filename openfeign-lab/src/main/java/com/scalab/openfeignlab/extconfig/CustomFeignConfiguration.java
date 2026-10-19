package com.scalab.openfeignlab.extconfig;

import com.scalab.openfeign.custom.CustomCapability;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomFeignConfiguration {

    @Bean
    public CustomCapability customCapability() {
        return new CustomCapability();
    }
}
