package com.radar.intel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan  // binds radar.notion.* -> NotionProperties
@EnableScheduling             // ready for a @Scheduled sync later; P0 syncs via POST /sync
public class RadarIntelligenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RadarIntelligenceApplication.class, args);
    }
}
