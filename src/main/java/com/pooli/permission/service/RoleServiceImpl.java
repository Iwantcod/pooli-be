package com.pooli.permission.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.common.exception.ApplicationException;
import com.pooli.family.domain.entity.FamilyLine;
import com.pooli.family.domain.enums.FamilyRole;
import com.pooli.notification.domain.enums.AlarmCode;
import com.pooli.notification.domain.enums.AlarmType;
import com.pooli.notification.service.AlarmHistoryService;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.FamilyLineMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final FamilyLineMapper familyLineMapper;
    private final AlarmHistoryService alarmHistoryService;

    @Override
    @Transactional
    public RepresentativeRoleTransferResDto transferRepresentativeRole(Long currentLineId, Long changeLineId) {

        if (currentLineId.equals(changeLineId)) {
            throw new ApplicationException(PermissionErrorCode.ROLE_TRANSFER_SELF);
        }

        FamilyLine currentFamilyLine = familyLineMapper.findByLineId(currentLineId)
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

        familyLineMapper.findAllFamilyIdByLineId(currentLineId)
                .forEach(lineId ->
                        alarmHistoryService.createAlarm(lineId, AlarmCode.PERMISSION, AlarmType.ROLE_TRANSFERRED));

        return RepresentativeRoleTransferResDto.builder()
                .currentOwnerLineId(currentLineId)
                .changeOwnerLineId(changeLineId)
                .build();
    }
}
