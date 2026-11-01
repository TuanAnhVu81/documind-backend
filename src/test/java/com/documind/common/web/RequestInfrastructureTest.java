package com.documind.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
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
            .setControllerAdvice(new ApiExceptionHandler())
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
        mvc.perform(get("/test/number").param("value", "not-an-integer")
                        .header(RequestIdFilter.HEADER, "bad-parameter"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("bad-parameter"));
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
