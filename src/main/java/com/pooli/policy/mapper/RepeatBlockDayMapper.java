package com.pooli.policy.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.policy.domain.dto.request.RepeatBlockDayReqDto;

@Mapper
public interface RepeatBlockDayMapper {
	
    // 특정 구성원의 반복적 차단 정책 생성 -> 반복적 차단 차단 요일/시간대 정보 생성
    int insertRepeatBlockDays(@Param("repeatBlockId") Long repeatBlockId,
                              @Param("days") List<RepeatBlockDayReqDto> days);
    
}
