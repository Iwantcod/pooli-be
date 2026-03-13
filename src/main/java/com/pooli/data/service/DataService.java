package com.pooli.data.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.data.domain.dto.response.AppDataUsageResDto;
import com.pooli.data.domain.dto.response.DataBalancesResDto;
import com.pooli.data.domain.dto.response.DataUsageResDto;
import com.pooli.data.domain.dto.response.MonthlyDataUsageResDto;

public interface DataService {
	
	MonthlyDataUsageResDto getMonthlyDataUsage(Long lineId, Integer yearMonth);

	AppDataUsageResDto getAppDataUsage(Long lineId, Integer yearMonth, AuthUserDetails principal );
	DataBalancesResDto getDataSummary(Long lineId);
	DataUsageResDto getDataUsage(Long lineId, Integer yearMonth);
}
