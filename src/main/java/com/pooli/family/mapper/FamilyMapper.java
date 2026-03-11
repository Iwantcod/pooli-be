package com.pooli.family.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.family.domain.dto.response.FamilyMemberSummaryResDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;

@Mapper
public interface FamilyMapper {
	
	FamilyMembersResDto selectFamilyMembersHeader(
		      @Param("lineId") Long lineId
    );

	List<FamilyMembersResDto.FamilyMemberDto> selectFamilyMembers(
        @Param("familyId") Integer familyId,
        @Param("lineId") Long lineId
    );
	
	List<FamilyMembersSimpleResDto> selectFamilyMembersSimpleByLineId(
		      @Param("lineId") Long lineId
		  );
	
	Boolean existsFamilyLine(
			@Param("familyId") Integer familyId,
			@Param("lineId") Long lineId);
	
	
	Boolean isPermissionEnabledByTitle(
	     @Param("lineId") Long lineId
	);
	
	int updateFamilyLineVisibility(
	    @Param("lineId") Long lineId,
	    @Param("isPublic") Boolean isPublic
	);
	
		
	Integer selectFamilyIdByLineId(
	    @Param("lineId") Long lineId
	);
	
	List<FamilyMemberSummaryResDto.FamilyMemberSummaryDto>
	    selectFamilyMemberSummaryByFamilyIdAndLineId(
	        @Param("familyId") Integer familyId,
	        @Param("lineId") Long lineId
	);

	Long selectPoolBaseDataByLineId(@Param("lineId") Long lineId);
}
