package com.pooli.permission.mapper;

import com.pooli.family.domain.entity.FamilyLine;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FamilyLineMapper {

    // lineId로 FamilyLine 조회 (역할 양도 대상 검증)
    Optional<FamilyLine> findByLineId(Long lineId);

    // 역할 변경 (OWNER <-> MEMBER)
    void updateRole(FamilyLine familyLine);
}
