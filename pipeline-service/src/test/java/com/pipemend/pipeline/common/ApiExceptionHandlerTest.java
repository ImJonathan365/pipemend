package com.pipemend.pipeline.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {

    @Test
    void nfr_20_anUnexpectedErrorIsAnInternalErrorWithoutDetailsOfTheCause() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ingestions");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "5f1c2b3a-0d4e-4f6a-9b8c-7d6e5f4a3b2c");

        ResponseEntity<ProblemDetail> response =
                new ApiExceptionHandler().unexpected(new IllegalStateException("secret internals"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail problem = response.getBody();
        assertThat(problem.getProperties())
                .containsEntry("code", "INTERNAL_ERROR")
                .containsEntry("requestId", "5f1c2b3a-0d4e-4f6a-9b8c-7d6e5f4a3b2c");
        assertThat(problem.getType()).hasToString("https://pipemend.dev/problems/internal-error");
        assertThat(problem.getDetail()).doesNotContain("secret");
    }
}
