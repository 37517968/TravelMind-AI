package com.travelmind.aiagent.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AgentExecutorConfiguration {
    @Bean
    public ThreadPoolTaskExecutor agentNodeExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-node-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }

    @Bean(destroyMethod = "close")
    public ExecutorService agentNodeInvocationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
