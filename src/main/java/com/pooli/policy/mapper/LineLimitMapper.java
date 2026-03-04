package com.pooli.policy.mapper;

import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.entity.LineLimit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface LineLimitMapper {
    /*
     =========== SELECT ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 LineLimit 조회
    Optional<LineLimit> getExistLineLimitByLineId(Long lineId);

    // 특정 pk의 삭제 상태가 아닌 LineLimit 조회
    Optional<LineLimit> getExistLineLimitById(Long limitId);

    /*
     =========== UPDATE ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 LineLimit의 is_daily_limit_active update
    int updateIsDailyLimitActiveById(@Param("limitId") Long limitId, @Param("isActive") Boolean isActive);
    // 특정 lineId의 삭제 상태가 아닌 LineLimit의 is_shared_limit_active update
    int updateIsSharedLimitActiveById(@Param("limitId") Long limitId, @Param("isActive") Boolean isActive);
    // 특정 lineId의 삭제 상태가 아닌 LineLimit 레코드의 daily_data_limit update
    int updateDailyDataLimit(LimitPolicyUpdateReqDto request);
    // 특정 lineId의 삭제 상태가 아닌 LineLimit 레코드의 shared_data_limit update
    int updateSharedDataLimit(LimitPolicyUpdateReqDto request);

    /*
     =========== INSERT ===========
     */
    // 새 LineLimit 레코드 insert
    int createLineLimit(LineLimit lineLimit);
}
