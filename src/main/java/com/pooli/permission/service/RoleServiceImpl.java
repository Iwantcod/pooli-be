package com.pooli.permission.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final FamilyLineMapper familyLineMapper;
    private final AlarmHistoryService alarmHistoryService;

    @Override
    @Transactional
    public RepresentativeRoleTransferResDto transferRepresentativeRole(Long currentLineId, Long changeLineId, AuthUserDetails userDetails) {
        Long resolvedLineId = resolveCurrentLineId(currentLineId, userDetails);

        if (resolvedLineId.equals(changeLineId)) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SELF);
        }

        FamilyLine currentFamilyLine = familyLineMapper.findByLineId(resolvedLineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SOURCE_NOT_FOUND));

        FamilyLine targetFamilyLine = familyLineMapper.findByLineId(changeLineId)
                .orElseThrow(() -> new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_TARGET_NOT_FOUND));

        if (!currentFamilyLine.getFamilyId().equals(targetFamilyLine.getFamilyId())) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_DIFFERENT_FAMILY);
        }

        if (targetFamilyLine.getRole() == FamilyRole.OWNER) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_TARGET_ALREADY_REPRESENTATIVE);
        }

        familyLineMapper.updateRole(FamilyLine.builder()
                .familyId(currentFamilyLine.getFamilyId())
                .lineId(currentFamilyLine.getLineId())
                .role(FamilyRole.MEMBER)
                .build());

        familyLineMapper.updateRole(FamilyLine.builder()
                .familyId(targetFamilyLine.getFamilyId())
                .lineId(targetFamilyLine.getLineId())
                .role(FamilyRole.OWNER)
                .build());

        familyLineMapper.findAllFamilyIdByLineId(resolvedLineId)
                .forEach(lineId ->
                        alarmHistoryService.createAlarm(lineId, AlarmCode.PERMISSION, AlarmType.ROLE_TRANSFERRED));

        return RepresentativeRoleTransferResDto.builder()
                .currentOwnerLineId(resolvedLineId)
                .changeOwnerLineId(changeLineId)
                .build();
    }

    // ADMIN이면 currentLineId 필수, OWNER이면 세션 lineId 강제 사용
    private Long resolveCurrentLineId(Long currentLineId, AuthUserDetails userDetails) {
        boolean isAdmin = userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (isAdmin) {
            if (currentLineId == null) {
                throw new ApplicationException(CommonErrorCode.MISSING_REQUEST_PARAM);
            }
            return currentLineId;
        }
        if (currentLineId != null) {
            log.warn("OWNER supplied currentLineId={} ignored, using session lineId={}", currentLineId, userDetails.getLineId());
        }
        return userDetails.getLineId();
    }
}
