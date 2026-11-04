package com.documind.common.web;

import org.junit.jupiter.api.Test;
import com.documind.common.error.AppException;
import com.documind.common.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestInfrastructureTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .addFilters(new RequestIdFilter()).build();

    @Test
    void correlatesSanitizedErrorsWithoutLeakingExceptionText() throws Exception {
        mvc.perform(get("/test/failure").header(RequestIdFilter.HEADER, "phase1-check"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(RequestIdFilter.HEADER, "phase1-check"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("phase1-check"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-exception-payload"))));
        assertThat(MDC.get(RequestIdFilter.ATTRIBUTE)).isNull();
    }

    @Test
    void malformedParametersUseTheSameErrorContract() throws Exception {
        mvc.perform(get("/test/number").param("value", "sensitive-not-an-integer")
                        .header(RequestIdFilter.HEADER, "bad-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("bad-parameter"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-not-an-integer"))));
    }

    @Test
    void knownApplicationFailureUsesCentralCodeAndPreservesRetryHeaders() throws Exception {
        mvc.perform(get("/test/refresh-race").header(RequestIdFilter.HEADER, "known-error"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("REFRESH_RACE"))
                .andExpect(jsonPath("$.requestId").value("known-error"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void internalConstraintExceptionIsASanitizedServerError() throws Exception {
        mvc.perform(get("/test/validation").header(RequestIdFilter.HEADER, "bad-value"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").value("bad-value"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sensitive-validation-value"))));
    }

    @Test
    void replacesUntrustedRequestIdsAndRestoresCallerMdc() throws Exception {
        MDC.put(RequestIdFilter.ATTRIBUTE, "outer-context");
        try {
            var result = mvc.perform(get("/test/failure").header(RequestIdFilter.HEADER, "unsafe value"))
                    .andExpect(status().isInternalServerError()).andReturn();
            assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER))
                    .matches("[0-9a-f-]{36}");
            assertThat(MDC.get(RequestIdFilter.ATTRIBUTE)).isEqualTo("outer-context");
        } finally {
            MDC.remove(RequestIdFilter.ATTRIBUTE);
        }
    }

    @RestController
    static class FailureController {
        @GetMapping("/test/refresh-race")
        String refreshRace() { throw new AppException(ErrorCode.REFRESH_RACE); }

        @GetMapping("/test/validation")
        String validation() { throw new ConstraintViolationException("sensitive-validation-value", java.util.Set.of()); }

        @GetMapping("/test/number")
        int number(@RequestParam int value) {
            return value;
        }

        @GetMapping("/test/failure")
        String failure() {
            throw new IllegalStateException("sensitive-exception-payload");
        }
    }
}
