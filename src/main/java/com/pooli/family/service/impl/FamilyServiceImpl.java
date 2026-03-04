package com.pooli.family.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMemberSummaryResDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.error.FamilyErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.family.service.FamilyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class FamilyServiceImpl implements FamilyService {
	
	private final FamilyMapper familyMapper;

	@Override
	@Transactional(readOnly = true)
	public FamilyMembersResDto getFamilyMembers(AuthUserDetails principal) {
		
		FamilyMembersResDto header = familyMapper.selectFamilyMembersHeader(principal.getLineId());
		if (header == null) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		List<FamilyMembersResDto.FamilyMemberDto> members =
		    familyMapper.selectFamilyMembers(header.getFamilyId());
		
		return FamilyMembersResDto.builder()
		      .isEnable(header.getIsEnable())
		      .familyId(header.getFamilyId())
		      .sharedPoolTotalData(header.getSharedPoolTotalData())
		      .members(members)
		      .build();
	}

	@Override
	@Transactional(readOnly = true)
	public List<FamilyMembersSimpleResDto> getFamilyMembersSimple(AuthUserDetails principal) {
		
		List<FamilyMembersSimpleResDto> list = familyMapper.selectFamilyMembersSimpleByLineId(principal.getLineId());
		
		if (list.isEmpty()) {
		    throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		return list;
	}

	@Override
	public Void updateVisibility(UpdateVisibilityReqDto request, AuthUserDetails principal) {
		
		// 현재 인증 계정과 접근 회선이 다를 경우
		if(!Objects.equals(request.getLineId(), principal.getLineId())) {
			log.info( request.getLineId() + " / " + principal.getLineId());
			throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
		}
		
		// Permission 여부 확인
		Boolean permissionId = familyMapper.isPermissionEnabledByTitle(principal.getLineId());
		
		if (!Boolean.TRUE.equals(permissionId)) {
			throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
		}
		
		// 공개 여부 수정
		int updated = familyMapper.updateFamilyLineVisibility(principal.getLineId(), request.getIsPublic());
	    if (updated == 0) {
	        throw new ApplicationException(FamilyErrorCode.FAM_CONFLICT);
	    }
	    
		return null;
	}

	@Override
	  @Transactional(readOnly = true)
	  public FamilyMemberSummaryResDto getFamilyMembersByLineId(Long lineId) {

	      Integer familyId = familyMapper.selectFamilyIdByLineId(lineId);
	      if (familyId == null) {
	          throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
	      }

	      List<FamilyMemberSummaryResDto.FamilyMemberSummaryDto> members =
	          familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(familyId, lineId);

	      if (members.isEmpty()) {
	          throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
	      }

	      /* 추후 전화번호 마스킹 처리 예정 */
	      
	      return FamilyMemberSummaryResDto.builder()
	          .familyId(familyId)
	          .members(members)
	          .build();
	  }

}
