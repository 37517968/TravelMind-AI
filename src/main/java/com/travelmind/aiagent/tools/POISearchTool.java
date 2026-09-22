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
 * POI搜索工具
 * 使用高德地图API搜索景点、酒店、餐厅等POI信息
 */
public class POISearchTool {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 4_000;

    private static final String POI_SEARCH_URL = "https://restapi.amap.com/v3/place/text";
    private static final String POI_AROUND_URL = "https://restapi.amap.com/v3/place/around";
    
    private final String apiKey;

    public POISearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 搜索景点
     */
    @Tool(description = "Search tourist attractions/scenic spots in a city, returns name, address, rating, etc.")
    public String searchAttractions(
            @ToolParam(description = "City name in Chinese, e.g. 上海, 北京") String city,
            @ToolParam(description = "Optional keyword to filter attractions, e.g. 博物馆, 公园, 古镇") String keyword) {
        return searchPOI(city, keyword != null ? keyword : "景点", "110000");
    }

    /**
     * 搜索酒店
     */
    @Tool(description = "Search hotels in a city, returns hotel name, address, rating, price range")
    public String searchHotels(
            @ToolParam(description = "City name in Chinese") String city,
            @ToolParam(description = "Optional keyword like 五星级, 经济型, 民宿") String keyword) {
        return searchPOI(city, keyword != null ? keyword + "酒店" : "酒店", "100000");
    }

    /**
     * 搜索餐厅
     */
    @Tool(description = "Search restaurants in a city, returns restaurant name, cuisine type, address, rating")
    public String searchRestaurants(
            @ToolParam(description = "City name in Chinese") String city,
            @ToolParam(description = "Optional cuisine type like 本帮菜, 火锅, 海鲜") String cuisineType) {
        return searchPOI(city, cuisineType != null ? cuisineType : "美食", "050000");
    }

    /**
     * 搜索周边POI
     */
    @Tool(description = "Search POIs around a specific location, useful for finding nearby attractions, restaurants, etc.")
    public String searchNearby(
            @ToolParam(description = "Center location name, e.g. 外滩, 故宫") String location,
            @ToolParam(description = "City name") String city,
            @ToolParam(description = "POI type: 景点/酒店/餐厅/购物") String poiType,
            @ToolParam(description = "Search radius in meters, default 1000") Integer radius) {
        try {
            // 先获取中心点坐标
            String centerLocation = getLocationCoordinate(location, city);
            if (centerLocation == null) {
                return "未找到位置：" + location;
            }
            
            String typeCode = switch (poiType) {
                case "景点" -> "110000";
                case "酒店" -> "100000";
                case "餐厅", "美食" -> "050000";
                case "购物" -> "060000";
                default -> "";
            };
            
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("location", centerLocation);
            paramMap.put("radius", radius != null ? radius : 1000);
            if (!typeCode.isEmpty()) {
                paramMap.put("types", typeCode);
            }
            paramMap.put("output", "json");
            
            String response = get(POI_AROUND_URL, paramMap);
            return parsePOIResponse(response, location + "周边" + poiType);
        } catch (Exception e) {
            return "周边搜索出错：" + e.getMessage();
        }
    }

    /**
     * 通用POI搜索
     */
    private String searchPOI(String city, String keyword, String typeCode) {
        try {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("keywords", keyword);
            paramMap.put("city", city);
            paramMap.put("types", typeCode);
            paramMap.put("citylimit", "true");
            paramMap.put("output", "json");
            paramMap.put("offset", "10"); // 返回10条结果
            
            String response = get(POI_SEARCH_URL, paramMap);
            return parsePOIResponse(response, city + keyword);
        } catch (Exception e) {
            return "POI搜索出错：" + e.getMessage();
        }
    }

    /**
     * 解析POI响应
     */
    private String parsePOIResponse(String response, String title) {
        JSONObject jsonObject = JSONUtil.parseObj(response);
        
        if (!"1".equals(jsonObject.getStr("status"))) {
            return "搜索失败：" + jsonObject.getStr("info");
        }
        
        JSONArray pois = jsonObject.getJSONArray("pois");
        if (pois == null || pois.isEmpty()) {
            return "未找到相关结果";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(title).append("搜索结果】\n");
        
        for (int i = 0; i < Math.min(pois.size(), 10); i++) {
            JSONObject poi = pois.getJSONObject(i);
            sb.append(String.format(
                    "\n%d. %s\n   地址：%s\n   电话：%s\n   类型：%s",
                    i + 1,
                    poi.getStr("name"),
                    poi.getStr("address", "暂无"),
                    poi.getStr("tel", "暂无"),
                    poi.getStr("type", "暂无")
            ));
            // 如果有评分
            if (poi.containsKey("biz_ext")) {
                JSONObject bizExt = poi.getJSONObject("biz_ext");
                if (bizExt != null && bizExt.containsKey("rating")) {
                    sb.append("\n   评分：").append(bizExt.getStr("rating"));
                }
            }
        }
        
        return sb.toString();
    }

    /**
     * 获取位置坐标
     */
    private String getLocationCoordinate(String location, String city) {
        try {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("key", apiKey);
            paramMap.put("keywords", location);
            paramMap.put("city", city);
            paramMap.put("output", "json");
            
            String response = get(POI_SEARCH_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"1".equals(jsonObject.getStr("status"))) {
                return null;
            }
            
            JSONArray pois = jsonObject.getJSONArray("pois");
            if (pois != null && !pois.isEmpty()) {
                return pois.getJSONObject(0).getStr("location");
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

