package com.pooli.family.service;

import java.util.List;

import org.springframework.http.ResponseEntity;

import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;

public interface FamilyService {
	
	FamilyMembersResDto getFamilyMembers(Integer familyId,Long lineId);
	
	List<FamilyMembersSimpleResDto> getFamilyMembersSimple(Integer familyId, Long lineId);
	
	Void updateVisibility(Long lineId, Boolean isPublic);
}
