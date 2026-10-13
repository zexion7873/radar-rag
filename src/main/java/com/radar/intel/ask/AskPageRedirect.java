package com.radar.intel.ask;

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

    static final String ASK_PAGE = "https://whyisthistrending.vercel.app/ask";

    @GetMapping("/")
    ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, ASK_PAGE).build();
    }
}
