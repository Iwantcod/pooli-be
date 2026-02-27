package com.pooli.family.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.pooli.common.exception.ApplicationException;
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
		
		List<FamilyMembersSimpleResDto> list = familyMapper.selectFamilyMembersSimple(familyId, lineId);
		
		if(list.isEmpty()) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		
		return list;
	}

	@Override
	public Void updateVisibility(Long lineId, Boolean isPublic) {
		
		// Permission 여부 확인
		Boolean permissionId = familyMapper.isPermissionEnabledByTitle(lineId, "가족원 정보 공개 여부");
		
		if (permissionId == null) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		// 공개 여부 수정
		int updated = familyMapper.updateFamilyLineVisibility(lineId, isPublic);
	    if (updated == 0) {
//	        throw new ApplicationException();
	    }
	    
		return null;
	    
		
	}

}
