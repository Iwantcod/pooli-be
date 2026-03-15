package com.pooli.traffic.service.invoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pooli.common.dto.Violation;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

public class TrafficPayloadValidationServiceTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final TrafficPayloadValidationService trafficPayloadValidationService =
            new TrafficPayloadValidationService(validator);

    @Test
    @DisplayName("returns payload-required violation when payload is null")
    void returnPayloadViolationWhenPayloadIsNull() {
        List<Violation> violations = trafficPayloadValidationService.validate(null);

        assertEquals(1, violations.size());
        assertEquals("payload", violations.get(0).getName());
        assertEquals("payload는 필수입니다.", violations.get(0).getReason());
    }

    @Test
    @DisplayName("converts bean validation failures into violation list")
    void returnViolationsForMissingFields() {
        TrafficPayloadReqDto payload = TrafficPayloadReqDto.builder()
                .traceId(" ")
                .lineId(0L)
                .familyId(null)
                .appId(-1)
                .apiTotalData(null)
                .enqueuedAt(0L)
                .build();

        List<Violation> violations = trafficPayloadValidationService.validate(payload);
        List<String> names = violations.stream()
                .map(Violation::getName)
                .sorted()
                .toList();

        assertEquals(List.of("apiTotalData", "appId", "enqueuedAt", "familyId", "lineId", "traceId"), names);
        assertTrue(violations.stream().anyMatch(v -> "traceId".equals(v.getName()) && "traceId는 필수입니다.".equals(v.getReason())));
        assertTrue(violations.stream().anyMatch(v -> "lineId".equals(v.getName()) && "lineId는 1 이상이어야 합니다.".equals(v.getReason())));
        assertTrue(violations.stream().anyMatch(v -> "familyId".equals(v.getName()) && "familyId는 필수입니다.".equals(v.getReason())));
        assertTrue(violations.stream().anyMatch(v -> "appId".equals(v.getName()) && "appId는 1 이상이어야 합니다.".equals(v.getReason())));
        assertTrue(violations.stream().anyMatch(v -> "apiTotalData".equals(v.getName()) && "apiTotalData는 필수입니다.".equals(v.getReason())));
        assertTrue(violations.stream().anyMatch(v -> "enqueuedAt".equals(v.getName()) && "enqueuedAt는 1 이상이어야 합니다.".equals(v.getReason())));
    }

    @Test
    @DisplayName("returns empty list for valid payload")
    void returnEmptyViolationsForValidPayload() {
        TrafficPayloadReqDto payload = TrafficPayloadReqDto.builder()
                .traceId("trace-001")
                .lineId(11L)
                .familyId(22L)
                .appId(33)
                .apiTotalData(100L)
                .enqueuedAt(1700000000000L)
                .build();

        List<Violation> violations = trafficPayloadValidationService.validate(payload);

        assertTrue(violations.isEmpty());
    }
}
