package com.pooli.traffic.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.outbox.RedisOutboxRecord;

/**
 * Redis Outbox 테이블 접근을 담당하는 MyBatis Mapper입니다.
 */
@Mapper
public interface RedisOutboxMapper {

    /**
     * 신규 Outbox 레코드를 삽입합니다.
     */
    int insert(RedisOutboxRecord record);

    /**
     * 재시도 대상 레코드를 SKIP LOCK으로 잠금 조회합니다.
     */
    List<RedisOutboxRecord> selectRetryCandidatesForUpdate(
            @Param("limit") int limit,
            @Param("pendingDelaySeconds") int pendingDelaySeconds,
            @Param("processingStuckSeconds") int processingStuckSeconds
    );

    /**
     * 같은 trace_id로 이미 접수된 공유풀 기여 Outbox 레코드를 조회합니다.
     */
    RedisOutboxRecord selectSharedPoolContributionByTraceId(@Param("traceId") String traceId);

    /**
     * 레코드를 PROCESSING 상태로 전이합니다.
     */
    int markProcessing(@Param("id") Long id);

    /**
     * 레코드를 SUCCESS 상태로 전이합니다.
     */
    int markSuccess(@Param("id") Long id);

    /**
     * 레코드를 FAIL 상태로 전이합니다(재시도 횟수 유지).
     */
    int markFail(@Param("id") Long id);

    /**
     * 레코드를 FINAL_FAIL 터미널 상태로 전이합니다.
     */
    int markFinalFail(@Param("id") Long id);

    /**
     * 레코드를 FAIL 상태로 전이하고 재시도 횟수를 1 증가시킵니다.
     */
    int markFailWithRetryIncrement(@Param("id") Long id);

    /**
     * 레코드를 FAIL 상태로 전이하고 재시도 횟수를 지정 값으로 설정합니다.
     */
    int markFailWithRetryCount(
            @Param("id") Long id,
            @Param("retryCount") int retryCount
    );

    /**
     * 레코드를 CANCELED 터미널 상태로 전이합니다.
     */
    int markCanceled(@Param("id") Long id);

}
