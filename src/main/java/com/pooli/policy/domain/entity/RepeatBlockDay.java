package com.pooli.policy.domain.entity;

import java.time.LocalDateTime;
import java.time.LocalTime;

import com.pooli.policy.domain.enums.DayOfWeek;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 반복 차단 요일 (REPEAT_BLOCK_DAY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RepeatBlockDay {
	
	private Long repeatBlockDayId;	// pk
    private Long repeatBlockId;     // fk -> REPEAT_BLOCK
    private DayOfWeek dayOfWeek;       
    private LocalTime startAt;        
    private LocalTime endAt;         
    private LocalDateTime createdAt;
    private LocalDateTime deletedAt;
    private LocalDateTime updatedAt;
}