package com.pooli.policy.mapper;

import com.pooli.policy.domain.entity.DailyLimit;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface DailyLimitMapper {
    // 특정 lineId의 DailyLimit 조회
    Optional<DailyLimit> getDailyLimitByLineId(Long lineId);
}
