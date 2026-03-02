package com.pooli.policy.mapper;

import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.entity.DailyLimit;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface DailyLimitMapper {
    /*
     =========== SELECT ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 DailyLimit 조회
    Optional<DailyLimit> getExistDailyLimitByLineId(Long lineId);

    // 특정 pk의 삭제 상태가 아닌 DailyLimit 조회
    Optional<DailyLimit> getExistDailyLimitById(Long dailyLimitId);

    /*
     =========== UPDATE ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 DailyLimit 활성화 상태로 update
    int activateDailyLimitByDailyLimitId(Long dailyLimitId);
    // 특정 lineId의 삭제 상태가 아닌 DailyLimit 비활성화 상태로 update
    int deactivateDailyLimitByDailyLimitId(Long dailyLimitId);
    // 특정 lineId의 삭제 상태가 아닌 DailyLimit 레코드의 daily_data_limit update
    int updateDailyDataLimit(LimitPolicyUpdateReqDto request);

    /*
     =========== INSERT ===========
     */
    // 새 DailyLimit 레코드 insert
    int createDailyLimit(DailyLimit dailyLimit);
}
