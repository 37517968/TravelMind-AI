package com.travelmind.aiagent.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只持久化标准 W3C traceparent，不保存 Baggage 或业务正文。 */
@Component
public class TraceContextCodec {
    private static final Pattern TRACE_PARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$");
    private final Tracer tracer;

    public TraceContextCodec(Tracer tracer) {
        this.tracer = tracer;
    }

    public String capture() {
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop()) return null;
        TraceContext context = span.context();
        String flags = Boolean.TRUE.equals(context.sampled()) ? "01" : "00";
        return "00-" + context.traceId() + "-" + context.spanId() + "-" + flags;
    }

    public Span startProducerSpan(String traceParent, String name, String eventId) {
        Span.Builder builder = tracer.spanBuilder().name(name).kind(Span.Kind.PRODUCER)
                .tag("messaging.system", "rabbitmq")
                .tag("messaging.message.id", eventId == null ? "unknown" : eventId);
        TraceContext parent = decode(traceParent);
        if (parent == null) builder.setNoParent(); else builder.setParent(parent);
        return builder.start();
    }

    private TraceContext decode(String value) {
        if (value == null) return null;
        Matcher matcher = TRACE_PARENT.matcher(value.trim().toLowerCase());
        if (!matcher.matches()) return null;
        return tracer.traceContextBuilder()
                .traceId(matcher.group(1))
                .spanId(matcher.group(2))
                .sampled((Integer.parseInt(matcher.group(3), 16) & 1) == 1)
                .build();
    }
}
