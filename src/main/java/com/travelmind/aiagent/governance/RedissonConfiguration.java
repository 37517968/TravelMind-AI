package com.travelmind.aiagent.governance;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfiguration {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        var single = config.useSingleServer().setAddress("redis://" + host + ":" + port).setDatabase(database);
        if (password != null && !password.isBlank()) single.setPassword(password);
        config.setLockWatchdogTimeout(30_000);
        return Redisson.create(config);
    }
}
