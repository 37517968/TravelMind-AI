package com.travelmind.aiagent.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.HashMap;
import java.util.Map;

/**
 * 路线规划工具
 * 使用高德地图API进行路线规划
 */
public class RoutePlanningTool {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 4_000;

    private static final String DIRECTION_DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String DIRECTION_TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DIRECTION_WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String GEO_URL = "https://restapi.amap.com/v3/geocode/geo";
    
    private final String apiKey;

    public RoutePlanningTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 驾车路线规划
     */
    @Tool(description = "Plan driving route between two locations, returns distance, duration, and route details")
    public String planDrivingRoute(
            @ToolParam(description = "Starting location name, e.g. 上海虹桥火车站") String origin,
            @ToolParam(description = "Destination name, e.g. 外滩") String destination,
            @ToolParam(description = "City name for geocoding") String city) {
        try {
            String originCoord = getCoordinate(origin, city);
            String destCoord = getCoordinate(destination, city);
            
            if (originCoord == null || destCoord == null) {
                return "无法获取起点或终点坐标";
            }
            
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("origin", originCoord);
            paramMap.put("destination", destCoord);
            paramMap.put("output", "json");
            
            String response = get(DIRECTION_DRIVING_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"1".equals(jsonObject.getStr("status"))) {
                return "路线规划失败：" + jsonObject.getStr("info");
            }
            
            JSONObject route = jsonObject.getJSONObject("route");
            JSONArray paths = route.getJSONArray("paths");
            
            if (paths == null || paths.isEmpty()) {
                return "未找到可行路线";
            }
            
            JSONObject path = paths.getJSONObject(0);
            int distance = path.getInt("distance");
            int duration = path.getInt("duration");
            
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【驾车路线：%s → %s】\n", origin, destination));
            sb.append(String.format("总距离：%.1f公里\n", distance / 1000.0));
            sb.append(String.format("预计用时：%d分钟\n", duration / 60));
            sb.append(String.format("打车费用：约%s元\n", route.getStr("taxi_cost", "未知")));
            
            // 添加主要路段信息
            JSONArray steps = path.getJSONArray("steps");
            if (steps != null && !steps.isEmpty()) {
                sb.append("\n主要路段：");
                for (int i = 0; i < Math.min(steps.size(), 5); i++) {
                    JSONObject step = steps.getJSONObject(i);
                    sb.append(String.format("\n%d. %s（%.1fkm）",
                            i + 1,
                            step.getStr("instruction"),
                            step.getInt("distance") / 1000.0));
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            return "驾车路线规划出错：" + e.getMessage();
        }
    }

    /**
     * 公共交通路线规划
     */
    @Tool(description = "Plan public transit route between two locations, returns bus/subway options with duration and cost")
    public String planTransitRoute(
            @ToolParam(description = "Starting location name") String origin,
            @ToolParam(description = "Destination name") String destination,
            @ToolParam(description = "City name") String city) {
        try {
            String originCoord = getCoordinate(origin, city);
            String destCoord = getCoordinate(destination, city);
            
            if (originCoord == null || destCoord == null) {
                return "无法获取起点或终点坐标";
            }
            
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("origin", originCoord);
            paramMap.put("destination", destCoord);
            paramMap.put("city", city);
            paramMap.put("output", "json");
            
            String response = get(DIRECTION_TRANSIT_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"1".equals(jsonObject.getStr("status"))) {
                return "公交路线规划失败：" + jsonObject.getStr("info");
            }
            
            JSONObject route = jsonObject.getJSONObject("route");
            JSONArray transits = route.getJSONArray("transits");
            
            if (transits == null || transits.isEmpty()) {
                return "未找到公交路线";
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【公共交通：%s → %s】\n", origin, destination));
            sb.append(String.format("总距离：%s米\n", route.getStr("distance")));
            
            // 显示前3条路线方案
            for (int i = 0; i < Math.min(transits.size(), 3); i++) {
                JSONObject transit = transits.getJSONObject(i);
                sb.append(String.format("\n方案%d：用时%d分钟，费用%s元，步行%s米\n",
                        i + 1,
                        transit.getInt("duration") / 60,
                        transit.getStr("cost", "未知"),
                        transit.getStr("walking_distance")));
                
                // 显示换乘信息
                JSONArray segments = transit.getJSONArray("segments");
                if (segments != null) {
                    for (int j = 0; j < segments.size(); j++) {
                        JSONObject segment = segments.getJSONObject(j);
                        JSONObject bus = segment.getJSONObject("bus");
                        if (bus != null) {
                            JSONArray buslines = bus.getJSONArray("buslines");
                            if (buslines != null && !buslines.isEmpty()) {
                                JSONObject busline = buslines.getJSONObject(0);
                                sb.append(String.format("  → %s（%s站）\n",
                                        busline.getStr("name"),
                                        busline.getStr("via_num", "若干")));
                            }
                        }
                    }
                }
            }
            
            return sb.toString();
        } catch (Exception e) {
            return "公交路线规划出错：" + e.getMessage();
        }
    }

    /**
     * 步行路线规划
     */
    @Tool(description = "Plan walking route between two locations, suitable for short distances")
    public String planWalkingRoute(
            @ToolParam(description = "Starting location name") String origin,
            @ToolParam(description = "Destination name") String destination,
            @ToolParam(description = "City name") String city) {
        try {
            String originCoord = getCoordinate(origin, city);
            String destCoord = getCoordinate(destination, city);
            
            if (originCoord == null || destCoord == null) {
                return "无法获取起点或终点坐标";
            }
            
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("origin", originCoord);
            paramMap.put("destination", destCoord);
            paramMap.put("output", "json");
            
            String response = get(DIRECTION_WALKING_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"1".equals(jsonObject.getStr("status"))) {
                return "步行路线规划失败：" + jsonObject.getStr("info");
            }
            
            JSONObject route = jsonObject.getJSONObject("route");
            JSONArray paths = route.getJSONArray("paths");
            
            if (paths == null || paths.isEmpty()) {
                return "未找到步行路线";
            }
            
            JSONObject path = paths.getJSONObject(0);
            int distance = path.getInt("distance");
            int duration = path.getInt("duration");
            
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【步行路线：%s → %s】\n", origin, destination));
            sb.append(String.format("总距离：%d米\n", distance));
            sb.append(String.format("预计用时：%d分钟\n", duration / 60));
            
            return sb.toString();
        } catch (Exception e) {
            return "步行路线规划出错：" + e.getMessage();
        }
    }

    /**
     * 获取地点坐标
     */
    private String getCoordinate(String address, String city) {
        try {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("address", address);
            paramMap.put("city", city);
            paramMap.put("output", "json");
            
            String response = get(GEO_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"1".equals(jsonObject.getStr("status"))) {
                return null;
            }
            
            JSONArray geocodes = jsonObject.getJSONArray("geocodes");
            if (geocodes != null && !geocodes.isEmpty()) {
                return geocodes.getJSONObject(0).getStr("location");
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private String get(String url, Map<String, Object> parameters) {
        return HttpRequest.get(url).form(parameters).setConnectionTimeout(CONNECT_TIMEOUT_MS)
                .setReadTimeout(READ_TIMEOUT_MS).execute().body();
    }
}

