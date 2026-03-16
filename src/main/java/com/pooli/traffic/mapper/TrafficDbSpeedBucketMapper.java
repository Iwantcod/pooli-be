package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * DB fallback AppSpeed 판정을 위한 3초 버킷 저장소 접근 Mapper입니다.
 */
@Mapper
public interface TrafficDbSpeedBucketMapper {

    /**
     * owner+app의 최근 버킷 사용량 합계를 조회합니다.
     */
    Long selectRecentUsageSum(
            @Param("poolType") String poolType,
            @Param("ownerId") Long ownerId,
            @Param("appId") Integer appId,
            @Param("fromEpochSec") long fromEpochSec,
            @Param("toEpochSec") long toEpochSec
    );

    /**
     * 현재 버킷 사용량을 upsert+증분합니다.
     */
    int upsertUsage(
            @Param("poolType") String poolType,
            @Param("ownerId") Long ownerId,
            @Param("appId") Integer appId,
            @Param("bucketEpochSec") long bucketEpochSec,
            @Param("usedBytes") long usedBytes
    );
}
