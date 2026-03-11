package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.entity.TrafficDeductDone;

/**
 * TRAFFIC_DEDUCT_DONE 영속화 전용 MyBatis Mapper입니다.
 * traceId 중복을 허용하지 않는 insert/조회 연산을 제공합니다.
 */
@Mapper
public interface TrafficDeductDoneMapper {

    int insertIgnore(@Param("done") TrafficDeductDone done);

    boolean existsByTraceId(@Param("traceId") String traceId);
}
