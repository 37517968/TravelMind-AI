from typing import Any

from fastapi import FastAPI
from pydantic import BaseModel
from z3 import Bool, If, Optimize, Solver, Sum, is_true, sat

app = FastAPI(title="Travel Constraint Solver", version="1.0.0")


class SolveRequest(BaseModel):
    constraints: dict[str, Any]
    candidates: dict[str, Any]
    timeoutMillis: int = 3000


def all_candidates(payload: dict[str, Any]) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for key in ("transports", "hotels", "attractions", "restaurants"):
        result.extend(payload.get(key) or [])
    return result


def multiplier(candidate: dict[str, Any], spec: dict[str, Any]) -> int:
    travelers = max(1, int(spec.get("travelers") or 1))
    if candidate.get("type") == "HOTEL":
        nights = max(1, int(spec.get("days") or 1) - 1)
        rooms = max(1, (travelers + 1) // 2)
        return nights * rooms
    return travelers


def build_constraints(solver: Any, tracked: bool, request: SolveRequest):
    spec = request.constraints
    candidates = all_candidates(request.candidates)
    choices = [Bool(f"candidate_{index}") for index in range(len(candidates))]
    groups: dict[str, list[int]] = {}
    for index, candidate in enumerate(candidates):
        groups.setdefault(candidate.get("type", "UNKNOWN"), []).append(index)

    def add(expr: Any, name: str):
        if tracked:
            solver.assert_and_track(expr, name)
        else:
            solver.add(expr)

    for index, candidate in enumerate(candidates):
        if not candidate.get("available", False):
            add(choices[index] == False, f"availability_{index}")
        capacity = int(candidate.get("capacity") or 0)
        if capacity and capacity < int(spec.get("travelers") or 1):
            add(choices[index] == False, f"capacity_{index}")

    hotel_indexes = groups.get("HOTEL", [])
    if int(spec.get("days") or 1) > 1:
        add(Sum([If(choices[i], 1, 0) for i in hotel_indexes]) == 1, "hotel_availability")
    transport_indexes = groups.get("TRANSPORT", [])
    if transport_indexes:
        add(Sum([If(choices[i], 1, 0) for i in transport_indexes]) <= 1, "transport_cardinality")

    attraction_indexes = groups.get("ATTRACTION", [])
    if attraction_indexes:
        add(Sum([If(choices[i], 1, 0) for i in attraction_indexes]) >= 1, "attraction_required")
    for tag in spec.get("requiredAttractionTags") or []:
        tagged = [i for i in attraction_indexes if tag in (candidates[i].get("tags") or [])]
        add(Sum([If(choices[i], 1, 0) for i in tagged]) >= 1, f"required_attraction_{tag}")
    restaurant_indexes = groups.get("RESTAURANT", [])
    for tag in spec.get("requiredCuisineTags") or []:
        tagged = [i for i in restaurant_indexes if tag in (candidates[i].get("tags") or [])]
        add(Sum([If(choices[i], 1, 0) for i in tagged]) >= 1, f"required_cuisine_{tag}")

    allowed_modes = set(spec.get("allowedTransportModes") or [])
    if allowed_modes:
        for index in transport_indexes:
            mode = (candidates[index].get("attributes") or {}).get("mode")
            if mode not in allowed_modes:
                add(choices[index] == False, f"transport_mode_{index}")

    costs = [int(candidate.get("unitCostCents") or 0) * multiplier(candidate, spec)
             for candidate in candidates]
    total = Sum([If(choices[i], costs[i], 0) for i in range(len(candidates))])
    max_budget = spec.get("maxBudgetCents")
    if max_budget is not None:
        add(total <= int(max_budget), "max_budget")
    return candidates, choices, costs, total


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.post("/solve")
def solve(request: SolveRequest) -> dict[str, Any]:
    tracked_solver = Solver()
    tracked_solver.set(timeout=request.timeoutMillis)
    build_constraints(tracked_solver, True, request)
    if tracked_solver.check() != sat:
        core = [str(item) for item in tracked_solver.unsat_core()]
        suggestions = []
        if "max_budget" in core:
            suggestions.append({"constraintId": "max_budget", "explanation": "提高预算上限",
                                "proposedChanges": {"increaseBudget": True}, "estimatedImpactCents": 0})
        return {"status": "UNSAT", "selected": [], "totalCostCents": 0, "unsatCore": core,
                "relaxationSuggestions": suggestions, "objectiveScore": 0,
                "diagnostics": {"solver": "Z3_SMT"}}

    optimizer = Optimize()
    optimizer.set(timeout=request.timeoutMillis)
    candidates, choices, costs, total = build_constraints(optimizer, False, request)
    optimizer.minimize(total)
    if optimizer.check() != sat:
        return {"status": "UNKNOWN", "selected": [], "totalCostCents": 0, "unsatCore": [],
                "relaxationSuggestions": [], "objectiveScore": 0,
                "diagnostics": {"solver": "Z3_SMT", "reason": "timeout_or_unknown"}}
    model = optimizer.model()
    selected = [candidate for i, candidate in enumerate(candidates) if is_true(model.eval(choices[i]))]
    total_cost = sum(costs[i] for i in range(len(candidates)) if is_true(model.eval(choices[i])))
    return {"status": "SAT", "selected": selected, "totalCostCents": total_cost, "unsatCore": [],
            "relaxationSuggestions": [], "objectiveScore": 100.0,
            "diagnostics": {"solver": "Z3_SMT", "candidateCount": len(candidates)}}
