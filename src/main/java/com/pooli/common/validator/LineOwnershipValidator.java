package com.pooli.common.validator;

import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.line.mapper.LineMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 회선 소유권 검증 공통 Validator.
 * 요청한 사용자가 해당 lineId를 실제로 소유하고 있는지 확인합니다.
 */
@Component
@RequiredArgsConstructor
public class LineOwnershipValidator {

    private final LineMapper lineMapper;

    /**
     * userId가 lineId를 소유하는지 검증합니다.
     * 
     * lineId가 존재하지 않거나 소유자가 아닌 경우 동일한 FORBIDDEN을 반환
     * (보안상 lineId의 존재 유무를 외부에 노출하지 않음)
     *
     * @param userId 현재 로그인한 사용자 ID
     * @param lineId 검증 대상 회선 ID
     * @throws ApplicationException LINE_OWNERSHIP_FORBIDDEN — 소유권 검증 실패 시
     */
    public void validate(Long userId, Long lineId) {
        Long ownerUserId = lineMapper.findOwnerUserIdByLineId(lineId);

        if (ownerUserId == null || !ownerUserId.equals(userId)) {
            throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        }
    }
}
