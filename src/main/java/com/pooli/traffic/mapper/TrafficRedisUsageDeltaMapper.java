package com.pooli.traffic.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.entity.TrafficRedisUsageDeltaRecord;

/**
 * Redis usage delta 레코드 저장/조회/상태전이를 담당하는 Mapper입니다.
 */
@Mapper
public interface TrafficRedisUsageDeltaMapper {

    /**
     * 신규 usage delta 레코드를 삽입합니다.
     */
    int insertIgnoreDuplicate(TrafficRedisUsageDeltaRecord record);

    /**
     * replay 대상 레코드를 잠금 조회합니다.
     */
    List<TrafficRedisUsageDeltaRecord> selectReplayCandidatesForUpdate(
            @Param("limit") int limit,
            @Param("processingStuckSeconds") int processingStuckSeconds
    );

    /**
     * 레코드를 PROCESSING으로 전이합니다.
     */
    int markProcessing(@Param("id") Long id);

    /**
     * 레코드를 SUCCESS로 전이합니다.
     */
    int markSuccess(@Param("id") Long id);

    /**
     * 레코드를 FAIL로 전이하고 retry_count를 1 증가시킵니다.
     */
    int markFailWithRetryIncrement(
            @Param("id") Long id,
            @Param("lastErrorMessage") String lastErrorMessage
    );

    /**
     * 레코드를 FAIL로 전이하고 retry_count를 지정 값으로 설정합니다.
     */
    int markFailWithRetryCount(
            @Param("id") Long id,
            @Param("retryCount") int retryCount,
            @Param("lastErrorMessage") String lastErrorMessage
    );

    /**
     * 미처리 backlog 개수를 조회합니다.
     */
    long countBacklog();
}
