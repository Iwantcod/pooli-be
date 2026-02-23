package com.pooli.policy.domain.entity;

import com.pooli.policy.domain.enums.DayOfWeek;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 반복 차단 요일 (REPEAT_BLOCK_DAY)
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RepeatBlockDay {
    private DayOfWeek dayOfWeek;       // ENUM('SUN','MON','TUE','WED','THU','FRI','SAT')
    private Long repeatBlockId;     // FK -> REPEAT_BLOCK
    private LocalDateTime createdAt;
}