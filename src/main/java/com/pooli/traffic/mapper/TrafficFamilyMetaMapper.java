package com.pooli.traffic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.traffic.domain.TrafficFamilyMetaSnapshot;

/**
 * 공유풀 임계치 계산에 필요한 FAMILY 메타 조회 Mapper입니다.
 */
@Mapper
public interface TrafficFamilyMetaMapper {

    /**
     * 가족 공유풀 메타(총량/DB잔량/임계치 설정)를 조회합니다.
     */
    TrafficFamilyMetaSnapshot selectFamilyMeta(@Param("familyId") Long familyId);
}
