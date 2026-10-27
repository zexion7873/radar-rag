package com.radar.intel.ask;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class AskBudgetTest {

    @Test
    void pastTheDailyLimitIs429WithRetryAfter() {
        AskBudget budget = new AskBudget(2);
        budget.spend();
        budget.spend();

        ErrorResponseException refused = catchThrowableOfType(ErrorResponseException.class, budget::spend);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Two a day refill one every 12 hours.
        assertThat(Long.parseLong(refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))).isBetween(43_000L, 43_201L);
    }

    @Test
    void withoutALimitNothingIsRefused() {
        AskBudget budget = new AskBudget(0);
        for (int i = 0; i < 1000; i++) {
            budget.spend();
        }
    }
}
