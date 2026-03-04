package com.pooli.policy.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;

@Mapper
public interface ImmediateBlockMapper {

	 // 특정 구성원의 즉시 차단 정보 조회
	 ImmediateBlockResDto selectImmediateBlockPolicy(@Param("lineId") Long lineId);

     // 특정 구성원의 즉시 차단 정보 수정
	 int updateImmediateBlockPolicy(@Param("lineId") Long lineId, @Param("request") ImmediateBlockReqDto request);

}
