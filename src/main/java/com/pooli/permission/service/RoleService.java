package com.pooli.permission.service;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.permission.domain.dto.response.RepresentativeRoleTransferResDto;

public interface RoleService {

    // RY1-292: 대표자 권한 양도
    RepresentativeRoleTransferResDto transferRepresentativeRole(Long currentLineId, Long changeLineId, AuthUserDetails userDetails);
}
