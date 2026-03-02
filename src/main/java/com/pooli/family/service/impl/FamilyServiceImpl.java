package com.pooli.family.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.error.FamilyErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.family.service.FamilyService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class FamilyServiceImpl implements FamilyService {
	
	private final FamilyMapper familyMapper;

	@Override
	public FamilyMembersResDto getFamilyMembers(Integer familyId, Long lineId) {
		
		FamilyMembersResDto header = familyMapper.selectFamilyMembersHeader(familyId, lineId);
		if (header == null) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		List<FamilyMembersResDto.FamilyMemberDto> members =
		    familyMapper.selectFamilyMembers(familyId);
		
		return FamilyMembersResDto.builder()
		      .isEnable(header.getIsEnable())
		      .familyId(header.getFamilyId())
		      .sharedPoolTotalData(header.getSharedPoolTotalData())
		      .members(members)
		      .build();
	}

	@Override
	public List<FamilyMembersSimpleResDto> getFamilyMembersSimple(Integer familyId, Long lineId) {
		
		// 현재 로그인한 계정이 해당 가족 정보에 접근 가능한가(가족 구성원인가)
		Boolean isMember = familyMapper.existsFamilyLine(familyId, lineId);
	      if (!Boolean.TRUE.equals(isMember)) {
	          throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
	      }
		
		
		List<FamilyMembersSimpleResDto> list = familyMapper.selectFamilyMembersSimple(familyId, lineId);
		
		if(list.isEmpty()) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		
		return list;
	}

	@Override
	public Void updateVisibility(Long lineId, UpdateVisibilityReqDto request) {
		
		// 현재 인증 계정과 접근 회선이 다를 경우
		if(!request.getLineId().equals(lineId)) {
			throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
		}
		
		// Permission 여부 확인
		Boolean permissionId = familyMapper.isPermissionEnabledByTitle(lineId, "가족원 정보 공개 여부");
		
		if (!Boolean.TRUE.equals(permissionId)) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		// 공개 여부 수정
		int updated = familyMapper.updateFamilyLineVisibility(lineId, request.getIsPublic());
	    if (updated == 0) {
	        throw new ApplicationException(FamilyErrorCode.FAM_CONFLICT);
	    }
	    
		return null;
	    
		
	}

}
