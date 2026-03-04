package com.pooli.line.service;

import java.util.List;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.domain.dto.response.LineUserSummaryResDto;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public interface LineService {
	
	List<LineSimpleResDto> getLines(Long userId, Long lineId);
	
	Void switchLine(AuthUserDetails principal, Long lineId,
            HttpServletRequest request, HttpServletResponse response);
	
	IndividualThresholdResDto getIndividualThreshold(AuthUserDetails principal);
	
	Void updateIndividualThreshold(AuthUserDetails principal, UpdateIndividualThresholdReqDto request);
	
	
	List<LineUserSummaryResDto> getLinesListByPhone(String phone);
}
