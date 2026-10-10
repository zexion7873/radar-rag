package com.radar.intel.ask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the "Ask the radar" page needs from the server to render: the Turnstile site key. */
@RestController
public class PageConfigController {

    private final String siteKey;

    public PageConfigController(@Value("${radar.turnstile.site-key}") String siteKey) {
        this.siteKey = siteKey;
    }

    public record PageConfig(String turnstileSiteKey) {
    }

    @GetMapping("/config")
    public PageConfig config() {
        return new PageConfig(siteKey);
    }
}
