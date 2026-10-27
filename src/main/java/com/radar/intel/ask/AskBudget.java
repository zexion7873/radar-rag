package com.radar.intel.ask;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponseException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The day's /ask for every client together, so a pool of addresses cannot spend the month's model budget
 * in one day. Spent only after the bot check: a request without a token must not use up everyone's day.
 * Without {@code radar.ask.daily-limit} there is no limit, so the eval harness asks freely.
 */
@Component
class AskBudget {

    private final Bucket perDay;

    AskBudget(@Value("${radar.ask.daily-limit:0}") int dailyLimit) {
        this.perDay = dailyLimit > 0
                ? Bucket.builder()
                        .addLimit(l -> l.capacity(dailyLimit).refillGreedy(dailyLimit, Duration.ofDays(1)))
                        .build()
                : null;
    }

    void spend() {
        if (perDay == null) {
            return;
        }
        ConsumptionProbe probe = perDay.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            // Not ResponseStatusException, whose headers are read-only.
            ErrorResponseException refused = new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS);
            refused.getHeaders().set(HttpHeaders.RETRY_AFTER,
                    String.valueOf(TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1));
            throw refused;
        }
    }
}
