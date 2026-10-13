package com.radar.intel.ask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Lets github-radar-ui's /ask page call POST /ask from the browser. Only /ask, only the listed
 * origins; every other path keeps the browser's same-origin default.
 */
@Configuration
class AskCorsConfig {

    // A filter, not MVC's CORS mapping, and ahead of RateLimitFilter (unordered, so last): MVC's CORS runs
    // after every filter, so a 429 lost the allow-origin header and the page saw a network failure.
    @Bean
    FilterRegistrationBean<CorsFilter> askCorsFilter(@Value("${radar.ask.allowed-origins}") String[] allowedOrigins) {
        CorsConfiguration cors = new CorsConfiguration().applyPermitDefaultValues();
        cors.setAllowedOrigins(List.of(allowedOrigins));
        cors.setAllowedMethods(List.of("POST"));
        cors.setAllowedHeaders(List.of("Content-Type", "X-Turnstile-Token"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/ask", cors);
        FilterRegistrationBean<CorsFilter> filter = new FilterRegistrationBean<>(new CorsFilter(source));
        filter.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return filter;
    }
}
