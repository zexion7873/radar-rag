package com.radar.intel.ask;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends this service's root to github-radar-ui's Ask page; old links to the root (the profile README,
 * shared URLs) break without it.
 */
@RestController
class AskPageRedirect {

    private final String askPage;

    AskPageRedirect(@Value("https://${radar.ui.host}/ask") String askPage) {
        this.askPage = askPage;
    }

    @GetMapping("/")
    ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, askPage).build();
    }
}
