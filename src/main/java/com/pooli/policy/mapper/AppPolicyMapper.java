package com.pooli.policy.mapper;

import com.pooli.policy.domain.dto.response.AppPolicyResDto;
import com.pooli.policy.domain.entity.AppPolicy;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

@Mapper
public interface AppPolicyMapper {
    /*
    =================== SELECT ====================
     */
    // lineId, appId로 삭제되지 않은 레코드 조회(조회 결과: entity)
    Optional<AppPolicy> findEntityExistByLineIdAndAppId(@Param("lineId") Long lineId, @Param("appId") Integer appId);
    // lineId, appId로 삭제되지 않은 레코드 조회(조회 결과: dto)
    Optional<AppPolicyResDto> findDtoExistByLineIdAndAppId(@Param("lineId") Long lineId, @Param("appId") Integer appId);
    // pk 로 Entity 조회
    Optional<AppPolicy> findEntityExistById(Long appPolicyId);


    /*
    =================== UPDATE ====================
     */
    // pk로 삭제되지 않은 레코드의 is_active 값 update
    int updateIsActive(@Param("appPolicyId") Long appPolicyId, @Param("isActive") Boolean isActive);

    // pk로 삭제되지 않은 레코드의 is_whitelist 값 update
    int updateIsWhitelist(@Param("appPolicyId") Long appPolicyId, @Param("isWhitelist") Boolean isWhitelist);
    // pk로 삭제되지 않은 레코드의 data_limit 값 update
    int updateDataLimit(@Param("appPolicyId") Long appPolicyId, @Param("value") Long value);
    // pk로 삭제되지 않은 레코드의 speed_limit 값 update
    int updateSpeedLimit(@Param("appPolicyId") Long appPolicyId, @Param("value") Integer value);

    /*
    =================== DELETE ====================
     */
    // pk로 레코드 삭제처리
    int setDeleted(Long appPolicyId);

    /*
    =================== INSERT ====================
     */
    // 새 레코드 생성
    int insertAppPolicy(AppPolicy appPolicy);
}
