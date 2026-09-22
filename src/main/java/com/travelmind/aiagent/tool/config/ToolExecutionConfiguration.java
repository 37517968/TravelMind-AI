package com.travelmind.aiagent.tool.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;

@Configuration
public class ToolExecutionConfiguration {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService toolExecutor(
            @Value("${travel.tools.executor.core-size:8}") int coreSize,
            @Value("${travel.tools.executor.max-size:16}") int maxSize,
            @Value("${travel.tools.executor.queue-capacity:100}") int queueCapacity) {
        return new ThreadPoolExecutor(coreSize, maxSize, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("governed-tool-" + thread.threadId());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
