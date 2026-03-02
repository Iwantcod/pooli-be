package com.pooli.policy.mapper;

import com.pooli.policy.domain.entity.SharedLimit;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface SharedLimitMapper {
    // 특정 lineId의 삭제 상태가 아닌 SharedLimit 조회
    Optional<SharedLimit> getActiveSharedLimitByLineId(Long lineId);
}
