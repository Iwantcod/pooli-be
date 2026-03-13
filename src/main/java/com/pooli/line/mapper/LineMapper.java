package com.pooli.line.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.line.domain.dto.response.IndividualThresholdResDto;
import com.pooli.line.domain.dto.response.LineSimpleResDto;
import com.pooli.line.domain.dto.response.LineUserSummaryResDto;

@Mapper
public interface LineMapper {
	
	
    /**
     * lineId로 해당 회선의 소유자 userId를 조회합니다.
     *
     * @param lineId 회선 ID
     * @return 소유자 userId (존재하지 않으면 null)
     */
    Long findOwnerUserIdByLineId(@Param("lineId") Long lineId);
	
	List<LineSimpleResDto> selectLinesByUserIdOrderByLineId(
	          @Param("userId") Long userId,
	          @Param("lineId") Long lineId
	  );
	
	IndividualThresholdResDto selectIndividualThresholdByLineId(
            @Param("lineId") Long lineId
    );
	
	
	int updateIndividualThreshold(
	          @Param("lineId") Long lineId,
	          @Param("individualThreshold") Long individualThreshold,
	          @Param("isThresholdActive") Boolean isThresholdActive
	  );
	
	
	List<LineUserSummaryResDto> selectLineUserSummaryListByPhoneSuffix(
	          @Param("phoneSuffix") String phoneSuffix
	);
	
	List<LineSimpleResDto> selectLinesByUserId(
	          @Param("userId") Long userId
	  );
}
