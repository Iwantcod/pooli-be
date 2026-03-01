package com.pooli.policy.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;

@Mapper
public interface RepeatBlockMapper {

	 // 특정 구성원의 반복 차단 목록 조회 (day 포함)
	 List<RepeatBlockPolicyResDto> selectRepeatBlocksByLineId(@Param("lineId") Long lineId);

	 // 특정 구성원의 반복적 차단 정책 생성 -> 반복적 차단 정보 생성
	 int insertRepeatBlock(RepeatBlockPolicyReqDto request);

     // 특정 구성원의 반복적 차단 정책 수정 -> 로직 결정 후 수정하기
	 int updateRepeatBlock(@Param("repeatBlockId") Long repeatBlockId, RepeatBlockPolicyReqDto request);
	 
	 // 삭제 전 반환할 정보 조회
	 RepeatBlockPolicyResDto selectRepeatBlockById(@Param("repeatBlockId") Long repeatBlockId);
	 
     // 특정 구성원의 반복적 차단 정책 삭제
	 int deleteRepeatBlock(@Param("repeatBlockId") Long repeatBlockId);

}
