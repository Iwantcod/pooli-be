package com.pooli.permission.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.permission.domain.dto.request.MemberPermissionBulkUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.PermissionLine;

@Mapper
public interface PermissionLineMapper {

    // 내 권한 상태 조회 (lineId 기준, PERMISSION JOIN)
    List<MemberPermissionResDto> findByLineId(Long lineId);

    // 구성원 권한 목록 조회 (familyId + lineId 기준, PERMISSION JOIN)
    List<MemberPermissionResDto> findByFamilyIdAndLineId(@Param("familyId") Long familyId, @Param("lineId") Long lineId);

    // 권한명 기반 활성화 여부 조회
    Boolean isPermissionEnabledByTitle(@Param("lineId") Long lineId, @Param("permissionTitle") String permissionTitle);

    // 가족 전체 구성원 권한 목록 조회 (familyId 기준, PERMISSION JOIN)
    List<MemberPermissionResDto> findByFamilyId(Long familyId);

    // 구성원 권한 변경 (없으면 INSERT, 있으면 UPDATE)
    void upsert(PermissionLine permissionLine);

    // 구성원 권한 일괄 변경
    void bulkUpsert(@Param("items") List<MemberPermissionBulkUpsertReqDto> items);
}
