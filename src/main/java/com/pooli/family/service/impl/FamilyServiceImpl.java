package com.pooli.family.service.impl;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.common.exception.CommonErrorCode;
import com.pooli.family.domain.dto.request.UpdateVisibilityReqDto;
import com.pooli.family.domain.dto.response.FamilyMemberSummaryResDto;
import com.pooli.family.domain.dto.response.FamilyMembersResDto;
import com.pooli.family.domain.dto.response.FamilyMembersSimpleResDto;
import com.pooli.family.exception.FamilyErrorCode;
import com.pooli.family.mapper.FamilyMapper;
import com.pooli.family.service.FamilyService;
import com.pooli.traffic.service.runtime.TrafficRemainingBalanceQueryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class FamilyServiceImpl implements FamilyService {

    private final FamilyMapper familyMapper;
    private final TrafficRemainingBalanceQueryService trafficRemainingBalanceQueryService;

    @Override
    @Transactional(readOnly = true)
    public FamilyMembersResDto getFamilyMembers(AuthUserDetails principal) {
        FamilyMembersResDto header = familyMapper.selectFamilyMembersHeader(principal.getLineId());
        if (header == null) {
            throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
        }

        Long familyId = header.getFamilyId() == null ? null : header.getFamilyId().longValue();
        Long actualSharedPoolRemaining = trafficRemainingBalanceQueryService.resolveSharedActualRemaining(
                familyId,
                header.getSharedPoolRemainingData()
        );

        List<FamilyMembersResDto.FamilyMemberDto> members = familyMapper
                .selectFamilyMembers(header.getFamilyId(), principal.getLineId())
                .stream()
                .map(member -> enrichFamilyMember(member, actualSharedPoolRemaining))
                .toList();

        return FamilyMembersResDto.builder()
                .isEnable(header.getIsEnable())
                .familyId(header.getFamilyId())
                .sharedPoolTotalData(header.getSharedPoolTotalData())
                .members(members)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FamilyMembersSimpleResDto> getFamilyMembersSimple(AuthUserDetails principal) {
        List<FamilyMembersSimpleResDto> list = familyMapper.selectFamilyMembersSimpleByLineId(principal.getLineId());

        if (list.isEmpty()) {
            throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
        }

        return list;
    }

    @Override
    public Void updateVisibility(UpdateVisibilityReqDto request, AuthUserDetails principal) {
        if (!Objects.equals(request.getLineId(), principal.getLineId())) {
            log.info("{} / {}", request.getLineId(), principal.getLineId());
            throw new ApplicationException(CommonErrorCode.LINE_OWNERSHIP_FORBIDDEN);
        }

        Boolean permissionId = familyMapper.isPermissionEnabledByTitle(principal.getLineId());
        if (!Boolean.TRUE.equals(permissionId)) {
            throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
        }

        int updated = familyMapper.updateFamilyLineVisibility(principal.getLineId(), request.getIsPublic());
        if (updated == 0) {
            throw new ApplicationException(FamilyErrorCode.FAM_CONFLICT);
        }

        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public FamilyMemberSummaryResDto getFamilyMembersByLineId(Long lineId) {
        Integer familyId = familyMapper.selectFamilyIdByLineId(lineId);
        if (familyId == null) {
            throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
        }

        List<FamilyMemberSummaryResDto.FamilyMemberSummaryDto> members =
                familyMapper.selectFamilyMemberSummaryByFamilyIdAndLineId(familyId, lineId);

        if (members.isEmpty()) {
            throw new ApplicationException(FamilyErrorCode.FAM_NOT_FOUND);
        }

        return FamilyMemberSummaryResDto.builder()
                .familyId(familyId)
                .members(members)
                .build();
    }

    private FamilyMembersResDto.FamilyMemberDto enrichFamilyMember(
            FamilyMembersResDto.FamilyMemberDto member,
            Long actualSharedPoolRemaining
    ) {
        Long lineId = member.getLineId() == null ? null : member.getLineId().longValue();
        Long actualRemainingData = trafficRemainingBalanceQueryService.resolveIndividualActualRemaining(
                lineId,
                member.getRemainingData()
        );

        return FamilyMembersResDto.FamilyMemberDto.builder()
                .isMe(member.getIsMe())
                .userId(member.getUserId())
                .lineId(member.getLineId())
                .planId(member.getPlanId())
                .userName(member.getUserName())
                .phone(member.getPhone())
                .planName(member.getPlanName())
                .remainingData(actualRemainingData)
                .basicDataAmount(member.getBasicDataAmount())
                .role(member.getRole())
                .sharedPoolTotalAmount(member.getSharedPoolTotalAmount())
                .sharedPoolRemainingAmount(resolveSharedPoolRemainingAmount(member, actualSharedPoolRemaining))
                .sharedLimitActive(member.getSharedLimitActive())
                .build();
    }

    private Long resolveSharedPoolRemainingAmount(
            FamilyMembersResDto.FamilyMemberDto member,
            Long actualSharedPoolRemaining
    ) {
        if (actualSharedPoolRemaining == null) {
            return member.getSharedPoolRemainingAmount();
        }
        if (!Boolean.TRUE.equals(member.getSharedLimitActive())) {
            return actualSharedPoolRemaining;
        }

        Long policyRemaining = member.getSharedPoolRemainingAmount();
        if (policyRemaining == null) {
            return null;
        }
        if (policyRemaining == -1L) {
            return actualSharedPoolRemaining;
        }
        if (actualSharedPoolRemaining == -1L) {
            return Math.max(0L, policyRemaining);
        }
        return Math.min(Math.max(0L, actualSharedPoolRemaining), Math.max(0L, policyRemaining));
    }
}
