package com.radar.intel.ask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets github-radar-ui's /ask page call POST /ask from the browser. Only /ask, only the listed
 * origins; every other path keeps the browser's same-origin default.
 */
@Configuration
class AskCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    AskCorsConfig(@Value("${radar.ask.allowed-origins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/ask")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("POST")
                .allowedHeaders("Content-Type", "X-Turnstile-Token");
    }
}
