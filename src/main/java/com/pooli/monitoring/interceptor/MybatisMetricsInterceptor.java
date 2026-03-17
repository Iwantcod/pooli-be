package com.pooli.monitoring.interceptor;

import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import com.pooli.monitoring.metrics.MybatisQueryMetrics;

/**
 * MyBatis Executor의 query/update 호출을 가로채서 실행 시간을 메트릭으로 기록한다.
 *
 * MappedStatement.getId()는 "com.pooli.user.mapper.UserMapper.selectById" 형태이므로
 * 마지막 두 토큰을 mapper, operation으로 분리한다.
 *
 * 예: com.pooli.user.mapper.UserMapper.selectById
 *   → mapper = "UserMapper"
 *   → operation = "selectById"
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class MybatisMetricsInterceptor implements Interceptor {

    private final MybatisQueryMetrics metrics;

    public MybatisMetricsInterceptor(MybatisQueryMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        String statementId = ms.getId();

        String mapper = extractMapper(statementId);
        String operation = extractOperation(statementId);

        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long duration = System.currentTimeMillis() - start;
            metrics.record(mapper, operation, duration);
        }
    }

    private String extractMapper(String statementId) {
        int lastDot = statementId.lastIndexOf('.');
        if (lastDot <= 0) return statementId;

        String beforeMethod = statementId.substring(0, lastDot);
        int secondLastDot = beforeMethod.lastIndexOf('.');
        return secondLastDot >= 0
                ? beforeMethod.substring(secondLastDot + 1)
                : beforeMethod;
    }

    private String extractOperation(String statementId) {
        int lastDot = statementId.lastIndexOf('.');
        return lastDot >= 0
                ? statementId.substring(lastDot + 1)
                : statementId;
    }

    @Override
    public void setProperties(Properties properties) {
        // 설정 필요 없음
    }
}
