package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidate;
import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelMapPlan;
import com.travelmind.aiagent.planning.model.TravelRouteOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 决定是否需要用户先选择景点路线，并把选定路线收敛为后续求解器的候选域。 */
@Component
public class TravelRouteSelectionService {
    private static final List<String> TITLES = List.of("经典必游路线", "轻松漫游路线", "城市深度路线");
    private static final List<String> EMOJIS = List.of("✨", "🌿", "🧭");

    public SelectionDecision decide(TravelConstraintSpec spec, TravelCandidateSet candidates,
                                    Map<String, Object> request) {
        Set<String> selectedIds = strings(request.get("selectedAttractionIds"));
        Set<String> selectedNames = strings(request.get("selectedAttractionNames"));
        if (!selectedIds.isEmpty() || !selectedNames.isEmpty()) {
            TravelCandidateSet narrowed = narrow(candidates, selectedIds, selectedNames);
            return new SelectionDecision(false, narrowed, List.of(),
                    Objects.toString(request.get("selectedRouteId"), "custom"));
        }

        Set<String> explicitNames = new LinkedHashSet<>(spec.specificAttractions());
        String userText = Objects.toString(request.get("prompt"), "") + " "
                + Objects.toString(request.get("userClarification"), "");
        candidates.attractions().stream().map(TravelCandidate::name)
                .filter(name -> name != null && name.length() >= 2 && userText.contains(name))
                .forEach(explicitNames::add);
        Set<String> matchedExplicitNames = matchedNames(candidates.attractions(), explicitNames);
        if (!matchedExplicitNames.isEmpty()) {
            TravelCandidateSet narrowed = narrow(candidates, Set.of(), matchedExplicitNames);
            return new SelectionDecision(false, narrowed, List.of(), "user-specified");
        }

        List<TravelRouteOption> options = options(spec, candidates.attractions());
        // 上游完全没有可展示的 POI 时不制造空选择题，交给后续求解/降级逻辑处理。
        return new SelectionDecision(!options.isEmpty(), candidates, options, null);
    }

    private List<TravelRouteOption> options(TravelConstraintSpec spec, List<TravelCandidate> attractions) {
        List<TravelCandidate> usable = attractions.stream()
                .filter(item -> item.name() != null && !item.name().isBlank())
                .limit(12).toList();
        if (usable.isEmpty()) return List.of();
        // 每条路线只取候选池的一部分，才能形成真正不同的主题组合，而不是仅改变同一批景点的顺序。
        int size = Math.min(usable.size(), Math.max(2, Math.min(4, spec.days() + 1)));
        List<List<TravelCandidate>> proposals = List.of(
                usable.stream().limit(size).toList(),
                stride(usable, 1, size),
                reversed(usable, size));
        Map<String, TravelRouteOption> unique = new LinkedHashMap<>();
        for (int i = 0; i < proposals.size(); i++) {
            List<TravelCandidate> route = proposals.get(i);
            String signature = route.stream().map(TravelCandidate::id).sorted().toList().toString();
            if (route.isEmpty() || unique.containsKey(signature)) continue;
            List<TravelRouteOption.RouteAttraction> stops = route.stream().map(this::attraction).toList();
            String summary = "约 " + Math.max(1, (int) Math.ceil(stops.size() / 2D))
                    + " 天，串联 " + stops.size() + " 个景点；选择后再细排交通、餐饮与住宿。";
            unique.put(signature, new TravelRouteOption("route-" + (i + 1), spec.destination(), TITLES.get(i), EMOJIS.get(i),
                    summary, stops));
        }
        return List.copyOf(unique.values());
    }

    private List<TravelCandidate> stride(List<TravelCandidate> source, int start, int limit) {
        List<TravelCandidate> values = new ArrayList<>();
        for (int i = start; values.size() < limit && i < source.size(); i += 2) values.add(source.get(i));
        for (TravelCandidate item : source) if (values.size() < limit && !values.contains(item)) values.add(item);
        return List.copyOf(values);
    }

    private List<TravelCandidate> reversed(List<TravelCandidate> source, int limit) {
        List<TravelCandidate> values = new ArrayList<>();
        for (int i = source.size() - 1; i >= 0 && values.size() < limit; i--) values.add(source.get(i));
        return List.copyOf(values);
    }

    private TravelRouteOption.RouteAttraction attraction(TravelCandidate item) {
        return new TravelRouteOption.RouteAttraction(item.id(), item.name(),
                Objects.toString(item.attributes().get("address"), ""), photoUrl(item.attributes().get("photos")));
    }

    private String photoUrl(Object raw) {
        if (!(raw instanceof Collection<?> photos)) return "";
        for (Object photo : photos) {
            if (photo instanceof TravelMapPlan.Photo value && value.url() != null) return value.url();
            if (photo instanceof Map<?, ?> map) {
                String url = Objects.toString(map.get("url"), "");
                if (url.startsWith("http://") || url.startsWith("https://")) return url;
            }
        }
        return "";
    }

    private TravelCandidateSet narrow(TravelCandidateSet source, Set<String> ids, Set<String> names) {
        List<TravelCandidate> matched = source.attractions().stream().filter(item -> ids.contains(item.id())
                || matchesAnyName(item.name(), names)).toList();
        if (matched.isEmpty()) return source;
        return new TravelCandidateSet(source.transports(), source.hotels(), matched, source.restaurants(),
                source.collectedAt());
    }

    private Set<String> matchedNames(List<TravelCandidate> attractions, Set<String> names) {
        Set<String> matched = new LinkedHashSet<>();
        for (String name : names) {
            if (attractions.stream().anyMatch(item -> matchesName(item.name(), name))) matched.add(name);
        }
        return matched;
    }

    private boolean matchesAnyName(String candidateName, Set<String> names) {
        return names.stream().anyMatch(name -> matchesName(candidateName, name));
    }

    private boolean matchesName(String candidateName, String requestedName) {
        if (candidateName == null || candidateName.isBlank() || requestedName == null || requestedName.isBlank()) {
            return false;
        }
        return candidateName.contains(requestedName) || requestedName.contains(candidateName);
    }

    private Set<String> strings(Object value) {
        if (value instanceof Collection<?> values) {
            Set<String> result = new LinkedHashSet<>();
            values.stream().filter(Objects::nonNull).map(Object::toString).filter(item -> !item.isBlank())
                    .forEach(result::add);
            return result;
        }
        if (value == null || value.toString().isBlank()) return Set.of();
        return Set.of(value.toString());
    }

    public record SelectionDecision(boolean waitingForSelection, TravelCandidateSet candidates,
                                    List<TravelRouteOption> options, String selectedRouteId) {}
}
