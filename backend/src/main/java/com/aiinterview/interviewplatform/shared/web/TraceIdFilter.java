package com.aiinterview.interviewplatform.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes a trace id for every request.
 *
 * <p>The id goes into the SLF4J MDC (so it appears on every log line), into
 * the {@code X-Trace-Id} response header, and into every RFC 9457 problem
 * response. A user can quote the id from an error screen and we can find the
 * exact request in the logs.
 *
 * <p>An inbound W3C {@code traceparent} is honoured so the id survives
 * whatever sits in front of the application. Inbound values are validated as
 * 32 lowercase hex characters before use — an unvalidated header would let a
 * caller forge log lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String RESPONSE_HEADER = "X-Trace-Id";

    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(RESPONSE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String fromTraceparent = extractFromTraceparent(request.getHeader(TRACEPARENT_HEADER));
        if (fromTraceparent != null) {
            return fromTraceparent;
        }
        String direct = normalise(request.getHeader(TRACE_ID_HEADER));
        return direct != null ? direct : newTraceId();
    }

    /** {@code version-traceid-spanid-flags}, e.g. {@code 00-<32 hex>-<16 hex>-01}. */
    private String extractFromTraceparent(String header) {
        if (header == null) {
            return null;
        }
        String[] parts = header.split("-");
        return parts.length >= 2 ? normalise(parts[1]) : null;
    }

    /** Accepts only a 32-character lowercase hex id that is not all zeroes. */
    private String normalise(String candidate) {
        if (candidate == null || candidate.length() != 32) {
            return null;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        boolean allZero = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return null;
            }
            if (c != '0') {
                allZero = false;
            }
        }
        return allZero ? null : lower;
    }

    private String newTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "%016x%016x".formatted(random.nextLong(), random.nextLong());
    }
}
