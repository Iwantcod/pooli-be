package com.pooli.permission.mapper;

import com.pooli.line.domain.entity.Line;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LineMapper {

    // userId로 메인 회선 조회 (역할 양도 시 userId → lineId 변환)
    Optional<Line> findMainLineByUserId(Long userId);
}
