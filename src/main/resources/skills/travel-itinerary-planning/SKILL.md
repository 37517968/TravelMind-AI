---
name: travel-itinerary-planning
description: >
  根据用户的旅行需求（目的地、天数、预算、偏好等），生成详细的旅行行程规划。
  适用场景：用户询问"帮我规划XX旅游"、"去XX玩几天"、需要完整的行程安排、想知道某地的旅游攻略。
  核心价值：综合调用天气、POI、路线等工具，结合知识库中的优质方案，生成个性化的行程建议。
version: 2.0.0
---

# 旅行行程规划技能

## 使用时机
当用户的问题涉及旅行行程规划、景点安排、旅游攻略时，调用此技能。

## 执行步骤

### 1. 参数提取
从用户输入中提取以下参数：
- **destination** (必需): 目的地城市，如"上海"、"北京"
- **days** (可选): 旅行天数，默认3天
- **budget** (可选): 预算（元），默认3000元
- **travelers** (可选): 出行人数，默认2人
- **interests** (可选): 兴趣偏好，如["美食", "文化", "自然"]

### 2. 查询天气信息
使用 `weather-tool` 查询目的地未来几天的天气预报。

### 3. 搜索景点和服务
使用 `poi-search-tool` 分别搜索：
- 热门景点（keyword="景点"）
- 特色餐厅（keyword="美食"）
- 合适酒店（keyword="酒店"）

### 4. 生成行程规划
综合天气、景点、餐厅、酒店信息，生成详细的每日行程：
- 根据天气调整活动（雨天安排室内，晴天安排户外）
- 同一区域的景点安排在同一天
- 合理安排上午、中午、下午、晚上的活动
- 推荐当地特色餐厅和合适住宿

### 5. 规划路线
使用 `route-planning-tool` 为每日行程规划最优路线。

### 6. 输出格式
按以下格式输出：

```
【{destination} {days}天智能行程规划】

🌤️ 天气情况：
{weather_info}

📅 详细行程安排：

第1天（{date}）
上午：{attraction1} - {description}
中午：{restaurant1}
下午：{attraction2} - {description}
晚上：{activity}
住宿：{hotel}
🗺️ 路线：{route_info}

第2天...

💡 旅行建议：
- {tip1}
- {tip2}
- {tip3}

💰 预算参考：
- 交通：约 {transport_cost} 元
- 住宿：约 {accommodation_cost} 元
- 餐饮：约 {meal_cost} 元
- 门票：约 {ticket_cost} 元
```

## 注意事项
1. 如果用户未提供目的地，询问用户
2. 优雅处理工具调用失败的情况
3. 优先参考知识库中的优质方案
4. 考虑季节、节假日等因素

## 可用工具
- weather-tool
- poi-search-tool
- route-planning-tool
- web-search-tool
- web-scraping-tool
- resource-download-tool

