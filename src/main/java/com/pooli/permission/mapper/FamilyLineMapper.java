package com.pooli.permission.mapper;

import com.pooli.family.domain.entity.FamilyLine;

import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FamilyLineMapper {

    // lineId로 FamilyLine 조회 (역할 양도 대상 검증)
    Optional<FamilyLine> findByLineId(Long lineId);

    // familyId + lineId로 FamilyLine 조회 (가족-회선 매핑 검증)
    Optional<FamilyLine> findByFamilyIdAndLineId(@Param("familyId") Long familyId, @Param("lineId") Long lineId);

    // 역할 변경 (OWNER <-> MEMBER)
    void updateRole(FamilyLine familyLine);

    // '특정 회선이 속한 가족 그룹'에 속한 모든 회선 식별자 조회
    List<Long> findAllFamilyIdByLineId(Long lineId);

    // 비공개 권한 해제 시 공개 상태를 true로 복구 (단건)
    void forcePublicByLineId(Long lineId);

    // 비공개 권한 해제 시 공개 상태를 true로 일괄 복구
    void forcePublicByLineIds(@Param("lineIds") List<Long> lineIds);
}
