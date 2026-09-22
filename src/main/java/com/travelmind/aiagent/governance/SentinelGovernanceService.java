package com.travelmind.aiagent.governance;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

@Service
public class SentinelGovernanceService {
    public <T> T executeTool(String resource, String userId, Callable<T> action) throws Exception {
        Entry userEntry = null;
        Entry resourceEntry = null;
        try {
            userEntry = SphU.entry("tool.user", EntryType.OUT, 1, safeUser(userId));
            resourceEntry = SphU.entry(resource, EntryType.OUT);
            return action.call();
        } catch (BlockException blocked) {
            throw new GovernanceBlockedException(blocked.getClass().getSimpleName(), blocked);
        } catch (Exception failure) {
            if (resourceEntry != null) Tracer.traceEntry(failure, resourceEntry);
            throw failure;
        } finally {
            if (resourceEntry != null) resourceEntry.exit();
            if (userEntry != null) userEntry.exit(1, safeUser(userId));
        }
    }

    public <T> T executeModel(Callable<T> action) throws Exception {
        Entry entry = null;
        try {
            entry = SphU.entry("model.itinerary", EntryType.OUT);
            return action.call();
        } catch (BlockException blocked) {
            throw new GovernanceBlockedException(blocked.getClass().getSimpleName(), blocked);
        } catch (Exception failure) {
            if (entry != null) Tracer.traceEntry(failure, entry);
            throw failure;
        } finally {
            if (entry != null) entry.exit();
        }
    }

    public <T> Flux<T> executeModelStream(Supplier<Flux<T>> action) {
        return Flux.defer(() -> {
            final Entry entry;
            try {
                entry = SphU.entry("model.itinerary", EntryType.OUT);
            } catch (BlockException blocked) {
                return Flux.error(new GovernanceBlockedException(blocked.getClass().getSimpleName(), blocked));
            }
            try {
                return action.get()
                        .doOnError(error -> Tracer.traceEntry(error, entry))
                        .doFinally(signal -> entry.exit());
            } catch (RuntimeException failure) {
                Tracer.traceEntry(failure, entry);
                entry.exit();
                return Flux.error(failure);
            }
        });
    }

    private String safeUser(String userId) { return userId == null || userId.isBlank() ? "anonymous" : userId; }

    public static class GovernanceBlockedException extends RuntimeException {
        public GovernanceBlockedException(String reason, Throwable cause) {
            super("请求已被 Sentinel 治理规则拒绝: " + reason, cause);
        }
    }
}
