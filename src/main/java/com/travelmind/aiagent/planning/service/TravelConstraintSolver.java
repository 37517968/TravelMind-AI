package com.travelmind.aiagent.planning.service;

import com.travelmind.aiagent.planning.model.TravelCandidateSet;
import com.travelmind.aiagent.planning.model.TravelConstraintSpec;
import com.travelmind.aiagent.planning.model.TravelSolverResult;

public interface TravelConstraintSolver {
    TravelSolverResult solve(TravelConstraintSpec constraints, TravelCandidateSet candidates);
}
