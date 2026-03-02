package com.pooli.policy.mapper;

import com.pooli.policy.domain.dto.request.LimitPolicyUpdateReqDto;
import com.pooli.policy.domain.entity.SharedLimit;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface SharedLimitMapper {
    /*
     =========== SELECT ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 SharedLimit 조회
    Optional<SharedLimit> getExistSharedLimitByLineId(Long lineId);
    // 특정 pk의 삭제 상태가 아닌 SharedLimit 조회
    Optional<SharedLimit> getExistSharedLimitById(Long sharedLimitId);

    /*
     =========== UPDATE ===========
     */
    // 특정 lineId의 삭제 상태가 아닌 SharedLimit 활성화 상태로 update
    int activateSharedLimitBySharedLimitId(Long sharedLimitId);
    // 특정 lineId의 삭제 상태가 아닌 SharedLimit 비활성화 상태로 update
    int deactivateSharedLimitBySharedLimitId(Long sharedLimitId);
    // 특정 lineId의 삭제 상태가 아닌 SharedLimit 레코드의 shared_data_limit update
    int updateSharedDataLimit(LimitPolicyUpdateReqDto request);

    /*
     =========== INSERT ===========
     */
    // 새 SharedLimit 레코드 insert
    int createSharedLimit(SharedLimit sharedLimit);
}
