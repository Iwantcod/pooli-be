package com.pooli.permission.mapper;

import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.PermissionLine;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PermissionLineMapper {

    // 내 권한 상태 조회 (lineId 기준, PERMISSION JOIN)
    List<MemberPermissionResDto> findByLineId(Long lineId);

    // 구성원 권한 목록 조회 (familyId + lineId 기준, PERMISSION JOIN)
    List<MemberPermissionResDto> findByFamilyIdAndLineId(@Param("familyId") Long familyId, @Param("lineId") Long lineId);

    // 구성원 권한 변경 (없으면 INSERT, 있으면 UPDATE)
    void upsert(PermissionLine permissionLine);
}
