package com.pooli.data.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.data.domain.dto.response.AppDataUsageResDto.AppUsageDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto.MonthlyUsageDto;

@Mapper
public interface DataMapper {
	
	List<MonthlyUsageDto> findRecentMonthlyUsageByLineId(
            @Param("lineId") Long lineId,
            @Param("month") Integer month
    );
	
    List<AppUsageDto> findAppDataUsageByLineIdAndMonth(
            @Param("lineId") Long lineId,
            @Param("month") Integer month
    );

}
