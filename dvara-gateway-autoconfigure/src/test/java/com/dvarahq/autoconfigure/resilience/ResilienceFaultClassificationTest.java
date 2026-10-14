package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.exception.GatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/** Which failures count against a provider's circuit, and which are worth sending again. */
class ResilienceFaultClassificationTest {

    @Test
    void aFailureInsideTheGatewayNeitherOpensTheCircuitNorIsRetried() {
        var unreadable = new RestClientException("Error while extracting response",
                new HttpMessageNotReadableException("bad JSON", new IOException("Unexpected token"), null));
        var bug = new IllegalStateException("a bug");
        var npe = new NullPointerException();

        for (Throwable gatewayFault : new Throwable[]{unreadable, bug, npe}) {
            assertThat(ResilienceAutoConfiguration.isProviderFailure(gatewayFault)).as(gatewayFault.toString()).isFalse();
            assertThat(ResilienceAutoConfiguration.isRetryable(gatewayFault)).as(gatewayFault.toString()).isFalse();
        }
    }

    @Test
    void networkErrorsTimeoutsAndProvider5xxStillCount() {
        var unreachable = new ResourceAccessException("I/O error", new java.net.ConnectException("refused"));
        var slow = new SocketTimeoutException("read timed out");
        var timeLimit = new TimeoutException();
        var serverError = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad gateway",
                HttpHeaders.EMPTY, new byte[0], null);
        var providerError = GatewayException.upstream(503, "upstream 503");

        for (Throwable providerFault : new Throwable[]{unreachable, slow, timeLimit, serverError, providerError}) {
            assertThat(ResilienceAutoConfiguration.isProviderFailure(providerFault)).as(providerFault.toString()).isTrue();
            assertThat(ResilienceAutoConfiguration.isRetryable(providerFault)).as(providerFault.toString()).isTrue();
        }
    }

    @Test
    void aRateLimitIsRetriedButDoesNotOpenTheSharedCircuit() {
        var tooMany = HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "slow down",
                HttpHeaders.EMPTY, new byte[0], null);

        assertThat(ResilienceAutoConfiguration.isRetryable(tooMany)).isTrue();
        assertThat(ResilienceAutoConfiguration.isProviderFailure(tooMany)).isFalse();
    }
}
