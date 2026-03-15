package com.pooli.traffic.domain.dto.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

public class TrafficGenerateReqDtoValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("requires all request fields and validates positive ids with non-negative apiTotalData")
    void validateRequiredFieldsAndPositiveValues() {
        TrafficGenerateReqDto request = TrafficGenerateReqDto.builder()
                .lineId(0L)
                .familyId(null)
                .appId(-1)
                .apiTotalData(0L)
                .build();

        List<String> invalidFields = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .sorted()
                .toList();

        assertEquals(List.of("appId", "familyId", "lineId"), invalidFields);
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> "lineId".equals(violation.getPropertyPath().toString())
                        && "lineId는 1 이상이어야 합니다.".equals(violation.getMessage())));
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> "familyId".equals(violation.getPropertyPath().toString())
                        && "familyId는 필수입니다.".equals(violation.getMessage())));
        assertTrue(validator.validate(request).stream()
                .anyMatch(violation -> "appId".equals(violation.getPropertyPath().toString())
                        && "appId는 1 이상이어야 합니다.".equals(violation.getMessage())));
    }

    @Test
    @DisplayName("accepts zero apiTotalData as a no-op request")
    void acceptsZeroApiTotalData() {
        TrafficGenerateReqDto request = TrafficGenerateReqDto.builder()
                .lineId(1L)
                .familyId(1L)
                .appId(1)
                .apiTotalData(0L)
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }
}
