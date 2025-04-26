package com.f4.reel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class RestTemplateConfig {

    private Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    @Bean
    public RestTemplate restTemplate() {    

        log.info("✅✅✅ Here is RestTemplateConfig ");
        return new RestTemplate();
    }

}