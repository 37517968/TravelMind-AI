package com.travelmind.aiagent.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 业务库（MySQL）主数据源。
 *
 * prod 下 travel.knowledge.vectorstore.type=pgvector 会注册 pgVectorDataSource，
 * 而只要容器里已经存在 DataSource Bean，Spring Boot 的数据源自动配置就整体退避，
 * 结果是 Flyway 与 MyBatis 连到 PostgreSQL（报 Unsupported Database: PostgreSQL 16.15）。
 * 这里显式把业务库声明为 @Primary，保证建表迁移和 Mapper 始终走 MySQL。
 */
@Configuration(proxyBeanMethods = false)
public class MysqlDataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}