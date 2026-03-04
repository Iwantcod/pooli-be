package com.pooli.family.service;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;

public interface FamilyService {
	
	FamilyMembersResDto getFamilyMembers(AuthUserDetails principal);
	
	List<FamilyMembersSimpleResDto> getFamilyMembersSimple(AuthUserDetails principal);
	
	Void updateVisibility(UpdateVisibilityReqDto request, AuthUserDetails principal);
}
