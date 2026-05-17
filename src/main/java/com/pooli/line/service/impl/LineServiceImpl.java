package com.pooli.line.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.common.util.MaskingUtils;
import com.pooli.line.domain.dto.request.UpdateIndividualThresholdReqDto;
import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.domain.dto.response.LineUserSummaryResDto;
import com.pooli.line.error.LineErrorCode;
import com.pooli.line.mapper.LineMapper;
import com.pooli.line.service.LineService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class LineServiceImpl implements LineService {

	private final LineMapper lineMapper;
    private final SecurityContextRepository securityContextRepository;
    private final TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;

	@Override
	public List<LineSimpleResDto> getLines(Long userId, Long lineId) {
		
		List<LineSimpleResDto> list = lineMapper.selectLinesByUserIdOrderByLineId(userId, lineId);
		
		if(list.isEmpty()) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return list;
	}
	
	@Override
	public Void switchLine(AuthUserDetails principal, Long lineId, HttpServletRequest request,
			HttpServletResponse response) {
		
		Long ownerUserId = lineMapper.findOwnerUserIdByLineId(lineId);
		if (ownerUserId == null) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND); // LINE:4401
		} else if(!Objects.equals(ownerUserId, principal.getUserId())) {
			throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN); // LINE:4302
		}
		
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
	public IndividualThresholdResDto getIndividualThreshold(AuthUserDetails principal) {
		
		IndividualThresholdResDto result = lineMapper.selectIndividualThresholdByLineId(principal.getLineId());
		
		if(result == null) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return applyActualThresholdMinValue(principal.getLineId(), result);
		
	}

	@Override
	public Void updateIndividualThreshold(AuthUserDetails principal, UpdateIndividualThresholdReqDto request) {
		
		int result = lineMapper.updateIndividualThreshold(principal.getLineId(), request.getIndividualThreshold(), request.getIsThresholdActive());
		
		if(result == 0) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return null;
	}

	@Override
	public List<LineUserSummaryResDto> getLinesListByPhone(String phone) {
		
		if (phone == null || !phone.matches("^\\d{4}$")) {
		    throw new ApplicationException(CommonErrorCode.INVALID_REQUEST_PARAM);
		}

		List<LineUserSummaryResDto> list = lineMapper.selectLineUserSummaryListByPhoneSuffix(phone);

	    if (list.isEmpty()) {
	        throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
	    }

	    List<LineUserSummaryResDto> maskedList = new ArrayList<>();
	      
	    return list.stream()
	              .map(item -> LineUserSummaryResDto.builder()
	                      .lineId(item.getLineId())
	                      .phone(MaskingUtils.maskingPhoneNumber(item.getPhone()))
	                      .userId(item.getUserId())
	                      .userName(item.getUserName())
	                      .email(item.getEmail())
	                      .build())
	              .toList();
	}

	@Override
	public List<LineSimpleResDto> getLinesListByUserId(Long userId) {
		List<LineSimpleResDto> list = lineMapper.selectLinesByUserId(userId);
		
		if(list.isEmpty()) {
			throw new ApplicationException(LineErrorCode.LINE_NOT_FOUND);
		}
		
		return list;
	}

    /**
     * 개인 임계치 최소값은 LINE.total_data와 Redis amount-only 잔량 기준의 현재 사용량으로 계산합니다.
     */
    private IndividualThresholdResDto applyActualThresholdMinValue(Long lineId, IndividualThresholdResDto source) {
        Long thresholdMaxValue = normalizeTotalAmount(source.getThresholdMaxValue());
        Long actualRemaining = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(lineId);
        Long thresholdMinValue = calculateUsedAmount(thresholdMaxValue, actualRemaining);

        return IndividualThresholdResDto.builder()
                .individualThreshold(source.getIndividualThreshold())
                .isThresholdActive(source.getIsThresholdActive())
                .thresholdMinValue(thresholdMinValue)
                .thresholdMaxValue(thresholdMaxValue)
                .build();
    }

    private Long calculateUsedAmount(Long totalAmount, Long actualRemaining) {
        if (totalAmount == null || actualRemaining == null) {
            return null;
        }
        if (totalAmount < 0L || actualRemaining < 0L) {
            return 0L;
        }
        return Math.max(0L, totalAmount - actualRemaining);
    }

    private Long normalizeTotalAmount(Long value) {
        if (value == null) {
            return null;
        }
        if (value < 0L) {
            return -1L;
        }
        return value;
    }


	
	
	
	
	
	
	
	
	
}
