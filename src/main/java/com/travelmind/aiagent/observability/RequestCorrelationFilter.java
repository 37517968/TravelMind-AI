package com.travelmind.aiagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/** 将请求关联标识写入响应、MDC 和 Trace；不把 userId 放入指标标签。 */
@Component
public class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private final ObservationRegistry observations;

    public RequestCorrelationFilter(ObservationRegistry observations) {
        this.observations = observations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = safeOrGenerated(request.getHeader("X-Request-Id"));
        response.setHeader("X-Request-Id", requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
            Observation current = observations.getCurrentObservation();
            if (current != null) {
                current.highCardinalityKeyValue("request.id", requestId);
                addHeader(current, request, "X-Conversation-Id", "conversation.id");
                addHeader(current, request, "X-Task-Id", "agent.task.id");
            }
            chain.doFilter(request, response);
        }
    }

    private void addHeader(Observation observation, HttpServletRequest request, String header, String traceKey) {
        String value = request.getHeader(header);
        if (value != null && SAFE_ID.matcher(value).matches()) observation.highCardinalityKeyValue(traceKey, value);
    }

    private String safeOrGenerated(String value) {
        return value != null && SAFE_ID.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }
}
