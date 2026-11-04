package com.documind.common.web;

import java.net.URI;

import com.documind.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

public final class Problems {
    private Problems() { }

    public static ProblemDetail create(ErrorCode error, HttpServletRequest request) {
        return create(error.status(), error.code(), error.detail(), request);
    }

    public static ProblemDetail create(HttpStatusCode status, String code, String detail,
                                       HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", request.getAttribute(RequestIdFilter.ATTRIBUTE));
        return problem;
    }
}
