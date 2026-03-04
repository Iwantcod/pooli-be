package com.pooli.family.repository;

import com.pooli.family.domain.entity.SharedPoolDomain;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FamilySharedPoolMapper {

    // GET /api/shared-pools/my 용
    SharedPoolDomain selectMySharedPoolStatus(@Param("lineId") Long lineId);

    // GET /api/shared-pools 용
    SharedPoolDomain selectFamilySharedPool(@Param("familyId") Long familyId);

    // POST /api/shared-pools 용 (데이터 담기) - 3개 쿼리가 하나의 트랜잭션으로 묶임
    void updateLineRemainingData(@Param("lineId") Long lineId, @Param("amount") Long amount);
    void updateFamilyPoolData(@Param("familyId") Long familyId, @Param("amount") Long amount);
    void insertContribution(@Param("familyId") Long familyId, @Param("lineId") Long lineId, @Param("amount") Long amount);

    // GET /api/shared-pools/detail/remaining-amount 용
    SharedPoolDomain selectSharedPoolDetail(@Param("familyId") Long familyId, @Param("lineId") Long lineId);

    // GET /api/shared-pools/main/remaining-amount 용
    SharedPoolDomain selectSharedPoolMain(@Param("familyId") Long familyId);

    // GET /api/shared-pools/limit 용
    SharedPoolDomain selectSharedDataThreshold(@Param("familyId") Long familyId);

    // PATCH /api/shared-pools/limit 용
    void updateSharedDataThreshold(@Param("familyId") Long familyId, @Param("newThreshold") Long newThreshold);

    // POST /api/shared-pools 검증용: 해당 lineId가 familyId의 구성원인지 확인
    int countFamilyLineMembership(@Param("familyId") Long familyId, @Param("lineId") Long lineId);

    // POST /api/shared-pools 검증용: 해당 lineId의 잔여 데이터량 조회
    Long selectRemainingData(@Param("lineId") Long lineId);

    // GET /api/shared-pools/detail 용: 해당 lineId의 공유풀 데이터 사용 제한량 조회
    Long selectSharedDataLimit(@Param("lineId") Long lineId);

    // PATCH /api/shared-pools/limit 검증용: 해당 userId가 familyId의 대표자(OWNER)인지 확인
    int countFamilyOwner(@Param("familyId") Long familyId, @Param("userId") Long userId);
}
