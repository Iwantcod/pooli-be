package com.pooli.monitoring.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.annotation.Configuration;

import com.pooli.monitoring.interceptor.MybatisMetricsInterceptor;
import com.pooli.monitoring.metrics.MybatisQueryMetrics;

import jakarta.annotation.PostConstruct;

/**
 * MybatisMetricsInterceptor를 SqlSessionFactory에 등록한다.
 *
 * Spring Boot가 자동 생성하는 SqlSessionFactory에 플러그인을 추가하는 방식이라
 * 기존 MyBatis 설정(application.yaml)을 건드리지 않는다.
 */
@Configuration
public class MybatisMetricsConfig {

    private final SqlSessionFactory sqlSessionFactory;
    private final MybatisQueryMetrics mybatisQueryMetrics;

    public MybatisMetricsConfig(SqlSessionFactory sqlSessionFactory,
                                MybatisQueryMetrics mybatisQueryMetrics) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.mybatisQueryMetrics = mybatisQueryMetrics;
    }

    @PostConstruct
    void registerInterceptor() {
        sqlSessionFactory.getConfiguration()
                .addInterceptor(new MybatisMetricsInterceptor(mybatisQueryMetrics));
    }
}
