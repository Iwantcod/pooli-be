package com.pooli.family.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;

@Mapper
public interface FamilyMapper {
	
	FamilyMembersResDto selectFamilyMembersHeader(
		      @Param("familyId") Integer familyId,
		      @Param("lineId") Long lineId
    );

	List<FamilyMembersResDto.FamilyMemberDto> selectFamilyMembers(
        @Param("familyId") Integer familyId
    );
	
	List<FamilyMembersSimpleResDto> selectFamilyMembersSimple(
	          @Param("familyId") Integer familyId,
	          @Param("lineId") Long lineId);
	
	
	
	
	Boolean isPermissionEnabledByTitle(
	     @Param("lineId") Long lineId,
	     @Param("title") String title
	);
	
	int updateFamilyLineVisibility(
	    @Param("lineId") Long lineId,
	    @Param("isPublic") Boolean isPublic
	);

	
}
