package com.pooli.traffic.service.invoke;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.pooli.common.dto.Violation;
import com.pooli.traffic.domain.dto.request.TrafficPayloadReqDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TrafficPayloadValidationService {

    private final Validator validator;

    public List<Violation> validate(TrafficPayloadReqDto payload) {
        if (payload == null) {
            return List.of(
                    Violation.builder()
                            .target("payload")
                            .name("payload")
                            .reason("payload는 필수입니다.")
                            .build()
            );
        }

        Set<ConstraintViolation<TrafficPayloadReqDto>> violations = validator.validate(payload);
        return violations.stream()
                .map(this::toViolation)
                .toList();
    }

    private Violation toViolation(ConstraintViolation<TrafficPayloadReqDto> violation) {
        String fieldName = violation.getPropertyPath() == null
                ? "unknown"
                : violation.getPropertyPath().toString();

        return Violation.builder()
                .target("payload")
                .name(fieldName)
                .reason(violation.getMessage())
                .build();
    }
}
