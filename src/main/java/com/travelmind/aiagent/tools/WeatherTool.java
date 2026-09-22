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
 * 天气查询工具
 * 使用和风天气API查询天气信息
 */
public class WeatherTool {

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 4_000;

    // 和风天气API地址
    private static final String WEATHER_API_URL = "https://devapi.qweather.com/v7/weather/";
    private static final String GEO_API_URL = "https://geoapi.qweather.com/v2/city/lookup";
    
    private final String apiKey;

    public WeatherTool(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 查询城市实时天气
     */
    @Tool(description = "Query current weather for a city, returns temperature, weather condition, humidity, wind info etc.")
    public String getCurrentWeather(
            @ToolParam(description = "City name in Chinese, e.g. 上海, 北京, 杭州") String cityName) {
        try {
            // 先获取城市ID
            String locationId = getCityLocationId(cityName);
            if (locationId == null) {
                return "未找到城市：" + cityName;
            }
            
            // 查询实时天气
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("location", locationId);
            paramMap.put("key", apiKey);
            
            String response = get(WEATHER_API_URL + "now", paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"200".equals(jsonObject.getStr("code"))) {
                return "天气查询失败：" + jsonObject.getStr("code");
            }
            
            JSONObject now = jsonObject.getJSONObject("now");
            return String.format(
                    "【%s实时天气】\n温度：%s°C\n体感温度：%s°C\n天气：%s\n风向：%s\n风力：%s级\n湿度：%s%%\n能见度：%skm\n更新时间：%s",
                    cityName,
                    now.getStr("temp"),
                    now.getStr("feelsLike"),
                    now.getStr("text"),
                    now.getStr("windDir"),
                    now.getStr("windScale"),
                    now.getStr("humidity"),
                    now.getStr("vis"),
                    now.getStr("obsTime")
            );
        } catch (Exception e) {
            return "天气查询出错：" + e.getMessage();
        }
    }

    /**
     * 查询未来7天天气预报
     */
    @Tool(description = "Query 7-day weather forecast for a city, helps user plan travel dates")
    public String getWeatherForecast(
            @ToolParam(description = "City name in Chinese, e.g. 上海, 北京, 杭州") String cityName) {
        try {
            String locationId = getCityLocationId(cityName);
            if (locationId == null) {
                return "未找到城市：" + cityName;
            }
            
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("location", locationId);
            paramMap.put("key", apiKey);
            
            String response = get(WEATHER_API_URL + "7d", paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"200".equals(jsonObject.getStr("code"))) {
                return "天气预报查询失败：" + jsonObject.getStr("code");
            }
            
            JSONArray daily = jsonObject.getJSONArray("daily");
            StringBuilder sb = new StringBuilder();
            sb.append("【").append(cityName).append("未来7天天气预报】\n");
            
            for (int i = 0; i < daily.size(); i++) {
                JSONObject day = daily.getJSONObject(i);
                sb.append(String.format(
                        "\n%s：%s转%s，%s~%s°C，%s%s级",
                        day.getStr("fxDate"),
                        day.getStr("textDay"),
                        day.getStr("textNight"),
                        day.getStr("tempMin"),
                        day.getStr("tempMax"),
                        day.getStr("windDirDay"),
                        day.getStr("windScaleDay")
                ));
            }
            
            return sb.toString();
        } catch (Exception e) {
            return "天气预报查询出错：" + e.getMessage();
        }
    }

    /**
     * 获取城市LocationID
     */
    private String getCityLocationId(String cityName) {
        try {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("location", cityName);
            paramMap.put("key", apiKey);
            
            String response = get(GEO_API_URL, paramMap);
            JSONObject jsonObject = JSONUtil.parseObj(response);
            
            if (!"200".equals(jsonObject.getStr("code"))) {
                return null;
            }
            
            JSONArray locations = jsonObject.getJSONArray("location");
            if (locations != null && !locations.isEmpty()) {
                return locations.getJSONObject(0).getStr("id");
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

