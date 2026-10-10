package com.radar.intel.ask;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Ask page lives on github-radar-ui; this service's root used to serve it, so old links (the
 * profile README, shared URLs) land there instead.
 */
@RestController
class AskPageRedirect {

    static final String ASK_PAGE = "https://whyisthistrending.vercel.app/ask";

    @GetMapping("/")
    ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY).header(HttpHeaders.LOCATION, ASK_PAGE).build();
    }
}
