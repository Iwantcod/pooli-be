package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.entity.TrafficDeductDoneLog;

/**
 * TRAFFIC_DEDUCT_DONE 테이블 접근을 담당하는 MyBatis Mapper입니다.
 */
@Mapper
public interface TrafficDeductDoneLogMapper {

    /**
     * traceId 기준 완료 로그 존재 여부를 조회합니다.
     */
    boolean existsByTraceId(@Param("traceId") String traceId);

    /**
     * 완료 로그를 신규로 삽입합니다.
     */
    int insert(TrafficDeductDoneLog doneLog);
}
