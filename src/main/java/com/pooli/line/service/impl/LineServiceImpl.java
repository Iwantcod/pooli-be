package com.pooli.line.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.error.LineErrorCode;
import com.pooli.line.mapper.LineMapper;
import com.pooli.line.service.LineService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LineServiceImpl implements LineService {

	private final LineMapper lineMapper;
    private final SecurityContextRepository securityContextRepository;

	@Override
	public List<LineSimpleResDto> getLines(Long userId, Long lineId) {
		
		List<LineSimpleResDto> list = lineMapper.selectLinesByUserId(userId, lineId);
		
		if(list.isEmpty()) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return list;
	}
	
	@Override
	public Void switchLine(AuthUserDetails principal, Long lineId, HttpServletRequest request,
			HttpServletResponse response) {
		
		AuthUserDetails updated = AuthUserDetails.builder()
	              .userId(principal.getUserId())
	              .email(principal.getEmail())
	              .password(null)
	              .lineId(lineId)
	              .authorities(new ArrayList<>(principal.getAuthorities()))
	              .build();

		Authentication newAuth = new UsernamePasswordAuthenticationToken(
		          updated, null, principal.getAuthorities());
		
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(newAuth);
		SecurityContextHolder.setContext(context);

		securityContextRepository.saveContext(context, request, response);
		return null;
	}

	@Override
	public IndividualThresholdResDto getIndividualThreshold(Long lineId) {
		
		IndividualThresholdResDto result = lineMapper.selectIndividualThresholdByLineId(lineId);
		
		if(result == null) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return result;
		
	}

	@Override
	public Void updateIndividualThreshold(Long lineId, UpdateIndividualThresholdReqDto request) {
		
		int result = lineMapper.updateIndividualThreshold(lineId, request.getIndividualThreshold(), request.getIsThresholdActive());
		
		if(result == 0) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return null;
	}


	
	
	
	
	
	
	
	
	
}
