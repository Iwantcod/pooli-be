package com.pooli.family.mapper;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.family.domain.dto.response.SharedPoolMonthlyUsageResDto;
import com.pooli.family.domain.entity.SharedPoolDomain;

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

    // POST /api/shared-pools 검증용: 해당 lineId의 잔여 데이터량 조회
    Long selectRemainingData(@Param("lineId") Long lineId);

    // GET /api/shared-pools/detail 용: 해당 lineId의 공유풀 데이터 사용 제한량 조회
    Long selectSharedDataLimit(@Param("lineId") Long lineId);

    // 세션의 lineId로 familyId를 조회
    Long selectFamilyIdByLineId(@Param("lineId") Long lineId);

    // 알람 전송용: 가족의 모든 멤버 lineId 조회
    List<Long> selectLineIdsByFamilyId(@Param("familyId") Long familyId);
    
    // 가족 공유풀 총 데이터 조회
    Long selectFamilyPoolTotalData(@Param("familyId") Long familyId);

    // 당 월 가족 
    List<SharedPoolMonthlyUsageResDto.MemberUsageDto> selectFamilyMonthlySharedUsageByLine(
            @Param("familyId") Long familyId
    );

    Long selectMonthlyContributionByFamilyId(
            @Param("familyId") Long familyId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );
    
}
