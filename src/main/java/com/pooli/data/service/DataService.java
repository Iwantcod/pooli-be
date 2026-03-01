package com.pooli.data.service;

import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;

public interface DataService {
	
	MonthlyDataUsageResDto getMonthlyDataUsage(Long lineId, Integer month);

	AppDataUsageResDto getAppDataUsage(Long lineId, Integer month);
	
}
