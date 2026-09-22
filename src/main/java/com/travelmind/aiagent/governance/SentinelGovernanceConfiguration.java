package com.travelmind.aiagent.governance;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class SentinelGovernanceConfiguration {
    private static final List<String> TOOL_RESOURCES = List.of(
            "tool.getCurrentWeather", "tool.getWeatherForecast", "tool.searchAttractions",
            "tool.searchHotels", "tool.searchRestaurants", "tool.searchNearby",
            "tool.planDrivingRoute", "tool.planTransitRoute", "tool.planWalkingRoute",
            "tool.searchWeb", "tool.scrapeWebPage", "tool.downloadResource", "tool.mcp");

    @Value("${travel.governance.tool-max-concurrency:16}") private double toolConcurrency;
    @Value("${travel.governance.model-max-concurrency:4}") private double modelConcurrency;
    @Value("${travel.governance.user-tool-qps:5}") private double userToolQps;

    @PostConstruct
    public void loadRules() {
        List<FlowRule> flowRules = new ArrayList<>();
        for (String resource : TOOL_RESOURCES) {
            FlowRule rule = new FlowRule(resource);
            rule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
            rule.setCount(toolConcurrency);
            flowRules.add(rule);
        }
        FlowRule modelRule = new FlowRule("model.itinerary");
        modelRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        modelRule.setCount(modelConcurrency);
        flowRules.add(modelRule);
        FlowRuleManager.loadRules(flowRules);

        List<DegradeRule> degradeRules = new ArrayList<>();
        for (String resource : TOOL_RESOURCES) {
            DegradeRule rule = new DegradeRule(resource);
            rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
            rule.setCount(0.5);
            rule.setMinRequestAmount(5);
            rule.setStatIntervalMs(10_000);
            rule.setTimeWindow(30);
            degradeRules.add(rule);
        }
        DegradeRuleManager.loadRules(degradeRules);

        ParamFlowRule userRule = new ParamFlowRule("tool.user")
                .setParamIdx(0).setCount(userToolQps).setDurationInSec(1);
        ParamFlowRuleManager.loadRules(List.of(userRule));
    }
}
