package com.pooli.permission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.pooli.common.exception.ApplicationException;
import com.pooli.permission.domain.dto.request.MemberPermissionUpsertReqDto;
import com.pooli.permission.domain.dto.response.MemberPermissionListResDto;
import com.pooli.permission.domain.dto.response.MemberPermissionResDto;
import com.pooli.permission.domain.entity.Permission;
import com.pooli.permission.exception.PermissionErrorCode;
import com.pooli.permission.mapper.PermissionLineMapper;
import com.pooli.permission.mapper.PermissionMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MemberPermissionServiceImplTest {

    @Mock
    private PermissionLineMapper permissionLineMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @InjectMocks
    private MemberPermissionServiceImpl memberPermissionService;

    @Nested
    @DisplayName("내 권한 상태 조회")
    class GetMyPermissions {

        @Test
        @DisplayName("lineId로 조회하면 해당 권한 목록을 반환한다")
        void success() {
            Long lineId = 1001L;
            List<MemberPermissionResDto> permissions = List.of(
                    memberPermissionResDto(10L, lineId, 1, "데이터 차단", false)
            );
            given(permissionLineMapper.findByLineId(lineId)).willReturn(permissions);

            MemberPermissionListResDto result = memberPermissionService.getMyPermissions(lineId);

            assertThat(result.getMemberPermissions()).hasSize(1);
            assertThat(result.getMemberPermissions().get(0).getLineId()).isEqualTo(lineId);
        }

        @Test
        @DisplayName("권한이 없으면 빈 목록을 반환한다")
        void emptyResult() {
            Long lineId = 1001L;
            given(permissionLineMapper.findByLineId(lineId)).willReturn(List.of());

            MemberPermissionListResDto result = memberPermissionService.getMyPermissions(lineId);

            assertThat(result.getMemberPermissions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("구성원 권한 목록 조회 (RY1-104)")
    class GetMemberPermissions {

        @Test
        @DisplayName("familyId + lineId로 조회하면 해당 권한 목록을 반환한다")
        void success() {
            Long familyId = 10L;
            Long lineId = 1001L;
            List<MemberPermissionResDto> permissions = List.of(
                    memberPermissionResDto(familyId, lineId, 1, "데이터 차단", true),
                    memberPermissionResDto(familyId, lineId, 2, "앱 차단", false)
            );
            given(permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId)).willReturn(permissions);

            MemberPermissionListResDto result = memberPermissionService.getMemberPermissions(familyId, lineId);

            assertThat(result.getMemberPermissions()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("구성원 권한 변경 ")
    class UpdateMemberPermission {

        @Test
        @DisplayName("권한이 존재하면 upsert 후 변경된 권한을 반환한다")
        void success() {
            Long familyId = 10L;
            Long lineId = 1001L;
            Integer permissionId = 1;

            MemberPermissionUpsertReqDto reqDto = new MemberPermissionUpsertReqDto();
            reqDto.setPermissionId(permissionId);
            reqDto.setIsEnable(true);

            Permission permission = Permission.builder()
                    .permissionId(permissionId)
                    .permissionTitle("데이터 차단")
                    .createdAt(LocalDateTime.now())
                    .build();

            MemberPermissionResDto expected = memberPermissionResDto(familyId, lineId, permissionId, "데이터 차단", true);

            given(permissionMapper.findById(permissionId)).willReturn(Optional.of(permission));
            given(permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId)).willReturn(List.of(expected));

            MemberPermissionResDto result = memberPermissionService.updateMemberPermission(familyId, lineId, reqDto);

            assertThat(result.getPermissionId()).isEqualTo(permissionId);
            assertThat(result.getIsEnable()).isTrue();
            then(permissionLineMapper).should().upsert(any());
        }

        @Test
        @DisplayName("존재하지 않는 권한 ID면 PERMISSION_NOT_FOUND 예외를 던진다")
        void permissionNotFound() {
            Long familyId = 10L;
            Long lineId = 1001L;
            Integer permissionId = 999;

            MemberPermissionUpsertReqDto reqDto = new MemberPermissionUpsertReqDto();
            reqDto.setPermissionId(permissionId);
            reqDto.setIsEnable(true);

            given(permissionMapper.findById(permissionId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> memberPermissionService.updateMemberPermission(familyId, lineId, reqDto))
                    .isInstanceOf(ApplicationException.class)
                    .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                            .isEqualTo(PermissionErrorCode.PERMISSION_NOT_FOUND));

            then(permissionLineMapper).should(never()).upsert(any());
        }

        @Test
        @DisplayName("upsert 후 결과 조회 실패 시 MEMBER_PERMISSION_NOT_FOUND 예외를 던진다")
        void memberPermissionNotFound() {
            Long familyId = 10L;
            Long lineId = 1001L;
            Integer permissionId = 1;

            MemberPermissionUpsertReqDto reqDto = new MemberPermissionUpsertReqDto();
            reqDto.setPermissionId(permissionId);
            reqDto.setIsEnable(true);

            Permission permission = Permission.builder()
                    .permissionId(permissionId)
                    .permissionTitle("데이터 차단")
                    .createdAt(LocalDateTime.now())
                    .build();

            given(permissionMapper.findById(permissionId)).willReturn(Optional.of(permission));
            given(permissionLineMapper.findByFamilyIdAndLineId(familyId, lineId)).willReturn(List.of());

            assertThatThrownBy(() -> memberPermissionService.updateMemberPermission(familyId, lineId, reqDto))
                    .isInstanceOf(ApplicationException.class)
                    .satisfies(ex -> assertThat(((ApplicationException) ex).getErrorCode())
                            .isEqualTo(PermissionErrorCode.MEMBER_PERMISSION_NOT_FOUND));
        }
    }

    private MemberPermissionResDto memberPermissionResDto(
            Long familyId, Long lineId, Integer permissionId, String permissionTitle, Boolean isEnable) {
        return MemberPermissionResDto.builder()
                .familyId(familyId)
                .lineId(lineId)
                .permissionId(permissionId)
                .permissionTitle(permissionTitle)
                .isEnable(isEnable)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
