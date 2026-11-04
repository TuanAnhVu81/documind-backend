package com.documind.common.security;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.documind.common.error.ErrorCode;
import com.documind.common.web.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityProblemWriter {
    private final JsonMapper mapper;
    public SecurityProblemWriter(JsonMapper mapper) { this.mapper = mapper; }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode error) throws IOException {
        var problem = Problems.create(error, request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType()); body.put("title", problem.getTitle());
        body.put("status", problem.getStatus()); body.put("detail", problem.getDetail());
        body.put("instance", problem.getInstance()); body.putAll(problem.getProperties());
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), body);
    }
}
