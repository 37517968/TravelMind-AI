package com.travelmind.aiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({"com.travelmind.aiagent.mapper", "com.travelmind.aiagent.task.mapper",
        "com.travelmind.aiagent.knowledge.mapper", "com.travelmind.aiagent.tool.mapper"})
@EnableScheduling
public class TravelMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelMindApplication.class, args);
    }

}
