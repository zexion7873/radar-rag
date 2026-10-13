package com.radar.intel.ask;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PageConfigController.class)
@Import(AskCorsConfig.class)
@ActiveProfiles("prod")
class AskCorsTest {

    private static final String UI = "https://whyisthistrending.vercel.app";

    @Autowired
    private MockMvc mvc;

    // MockMvc leaves the servlet path empty; the rate limiter keys on it, as DispatcherServlet on "/" sets it.
    private static MockHttpServletRequestBuilder ask(String origin) {
        return post("/ask").servletPath("/ask").header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"x\"}");
    }

    // Without the allow-origin header the browser hides the 429, and the page reports a network failure.
    @Test
    void aRateLimitedAskStillCarriesTheUiOrigin() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(ask(UI));
        }
        mvc.perform(ask(UI))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", UI));
    }
}
