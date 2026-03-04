package com.pooli.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.pooli.auth.service.AuthUserDetails;
import com.pooli.common.exception.ApplicationException;
import com.pooli.policy.domain.dto.request.ImmediateBlockReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockDayReqDto;
import com.pooli.policy.domain.dto.request.RepeatBlockPolicyReqDto;
import com.pooli.policy.domain.dto.response.ActivePolicyResDto;
import com.pooli.policy.domain.dto.response.AppliedPolicyResDto;
import com.pooli.policy.domain.dto.response.ImmediateBlockResDto;
import com.pooli.policy.domain.dto.response.RepeatBlockPolicyResDto;
import com.pooli.policy.domain.enums.DayOfWeek;
import com.pooli.policy.exception.PolicyErrorCode;
import com.pooli.policy.mapper.ImmediateBlockMapper;
import com.pooli.policy.mapper.PolicyBackOfficeMapper;
import com.pooli.policy.mapper.RepeatBlockDayMapper;
import com.pooli.policy.mapper.RepeatBlockMapper;

@ExtendWith(MockitoExtension.class)
class UserPolicyServiceImplTest {

    @Mock
    private PolicyBackOfficeMapper policyBackOfficeMapper;
    @Mock
    private RepeatBlockMapper repeatBlockMapper;
    @Mock
    private RepeatBlockDayMapper repeatBlockDayMapper;
    @Mock
    private ImmediateBlockMapper immediateBlockMapper;

    @InjectMocks
    private UserPolicyServiceImpl userPolicyService;

    @Mock
    private AuthUserDetails authUserDetails;

    @Test
    @DisplayName("활성 정책 목록 조회 성공")
    void getActivePolicies_success() {
        // given
        ActivePolicyResDto policy = ActivePolicyResDto.builder()
                .policyId(1L)
                .policyName("테스트 정책")
                .build();
        when(policyBackOfficeMapper.selectActivePolicies()).thenReturn(List.of(policy));

        // when
        List<ActivePolicyResDto> result = userPolicyService.getActivePolicies();

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getPolicyName()).isEqualTo("테스트 정책");
        verify(policyBackOfficeMapper, times(1)).selectActivePolicies();
    }

    @Nested
    @DisplayName("반복적 차단(Repeat Block) 관련 테스트")
    class RepeatBlockTests {

        @Test
        @DisplayName("반복적 차단 목록 조회 성공")
        void getRepeatBlockPolicies_success() {
            // given
            Long lineId = 1L;
            RepeatBlockPolicyResDto policy = RepeatBlockPolicyResDto.builder()
                    .repeatBlockId(10L)
                    .lineId(lineId)
                    .build();
            when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId)).thenReturn(List.of(policy));

            // when
            List<RepeatBlockPolicyResDto> result = userPolicyService.getRepeatBlockPolicies(lineId, authUserDetails);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getRepeatBlockId()).isEqualTo(10L);
            verify(repeatBlockMapper, times(1)).selectRepeatBlocksByLineId(lineId);
        }

        @Test
        @DisplayName("반복적 차단 생성 성공")
        void createRepeatBlockPolicy_success() {
            // given
            Long lineId = 1L;
            when(authUserDetails.getLineId()).thenReturn(lineId);
            
            RepeatBlockDayReqDto dayReq = new RepeatBlockDayReqDto();
            dayReq.setDayOfWeek(DayOfWeek.MON);
            dayReq.setStartAt(LocalTime.of(9, 0));
            dayReq.setEndAt(LocalTime.of(18, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setIsActive(true);
            req.setDays(List.of(dayReq));

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(false);
            
            // MyBatis useGeneratedKeys 시뮬레이션
            doAnswer(invocation -> {
                RepeatBlockPolicyReqDto dto = invocation.getArgument(0);
                dto.setRepeatBlockId(100L);
                return 1;
            }).when(repeatBlockMapper).insertRepeatBlock(any());

            // when
            RepeatBlockPolicyResDto result = userPolicyService.createRepeatBlockPolicy(req, authUserDetails);

            // then
            assertThat(result.getRepeatBlockId()).isEqualTo(100L);
            assertThat(result.getDays()).hasSize(1);
            verify(repeatBlockMapper).insertRepeatBlock(req);
            verify(repeatBlockDayMapper).insertRepeatBlockDays(eq(100L), any());
        }

        @Test
        @DisplayName("반복적 차단 생성 실패 - 중복된 정책 존재")
        void createRepeatBlockPolicy_conflict() {
            // given
            Long lineId = 1L;
            when(authUserDetails.getLineId()).thenReturn(lineId);

            RepeatBlockDayReqDto dayReq = new RepeatBlockDayReqDto();
            dayReq.setDayOfWeek(DayOfWeek.MON);
            dayReq.setStartAt(LocalTime.of(9, 0));
            dayReq.setEndAt(LocalTime.of(18, 0));

            RepeatBlockPolicyReqDto req = new RepeatBlockPolicyReqDto();
            req.setDays(List.of(dayReq));

            when(repeatBlockMapper.isDuplicatedRepeatBlocks(eq(lineId), any(), any(), any())).thenReturn(true);

            // when & then
            assertThatThrownBy(() -> userPolicyService.createRepeatBlockPolicy(req, authUserDetails))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(PolicyErrorCode.BLOCK_POLICY_CONFLICT.getMessage());
        }

        @Test
        @DisplayName("반복적 차단 삭제 성공")
        void deleteRepeatBlockPolicy_success() {
            // given
            Long repeatBlockId = 10L;
            when(authUserDetails.getLineId()).thenReturn(1L);
            when(repeatBlockMapper.selectRepeatBlockById(repeatBlockId)).thenReturn(RepeatBlockPolicyResDto.builder().build());

            // when
            RepeatBlockPolicyResDto result = userPolicyService.deleteRepeatBlockPolicy(repeatBlockId, authUserDetails);

            // then
            assertThat(result.getRepeatBlockId()).isEqualTo(repeatBlockId);
            assertThat(result.getIsActive()).isFalse();
            verify(repeatBlockMapper).deleteRepeatBlock(repeatBlockId);
            verify(repeatBlockDayMapper).deleteRepeatDayBlock(repeatBlockId);
        }

        @Test
        @DisplayName("반복적 차단 삭제 실패 - 존재하지 않는 차단 정보")
        void deleteRepeatBlockPolicy_notFound() {
            // given
            Long repeatBlockId = 999L;
            when(repeatBlockMapper.selectRepeatBlockById(repeatBlockId)).thenReturn(null);

            // when & then
            assertThatThrownBy(() -> userPolicyService.deleteRepeatBlockPolicy(repeatBlockId, authUserDetails))
                    .isInstanceOf(ApplicationException.class)
                    .hasMessageContaining(PolicyErrorCode.REPEAT_BLOCK_NOT_FOUND.getMessage());
        }
    }

    @Nested
    @DisplayName("즉시 차단(Immediate Block) 관련 테스트")
    class ImmediateBlockTests {

        @Test
        @DisplayName("즉시 차단 정책 조회 성공 - 정보 존재함")
        void getImmediateBlockPolicy_exists() {
            // given
            Long lineId = 1L;
            LocalDateTime endAt = LocalDateTime.of(2026, 3, 4, 20, 1, 9, 3);
            ImmediateBlockResDto mockRes = ImmediateBlockResDto.builder()
                    .lineId(lineId)
                    .blockEndAt(endAt)
                    .build();
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId)).thenReturn(mockRes);

            // when
            ImmediateBlockResDto result = userPolicyService.getImmediateBlockPolicy(lineId, authUserDetails);

            // then
            assertThat(result.getBlockEndAt()).isEqualTo(endAt);
            verify(immediateBlockMapper).selectImmediateBlockPolicy(lineId);
        }

        @Test
        @DisplayName("즉시 차단 정책 조회 성공 - 정보 없음")
        void getImmediateBlockPolicy_notExists() {
            // given
            Long lineId = 1L;
            when(immediateBlockMapper.selectImmediateBlockPolicy(lineId)).thenReturn(null);

            // when
            ImmediateBlockResDto result = userPolicyService.getImmediateBlockPolicy(lineId, authUserDetails);

            // then
            assertThat(result.getBlockEndAt()).isNull();
            assertThat(result.getLineId()).isEqualTo(lineId);
        }

        @Test
        @DisplayName("즉시 차단 정책 업데이트 성공")
        void updateImmediateBlockPolicy_success() {
            // given
            Long lineId = 1L;
            ImmediateBlockReqDto req = new ImmediateBlockReqDto();
            req.setBlockEndAt(LocalDateTime.of(2026, 3, 4, 20, 1, 9, 3));

            // when
            ImmediateBlockResDto result = userPolicyService.updateImmediateBlockPolicy(lineId, req, authUserDetails);

            // then
            assertThat(result.getBlockEndAt()).isEqualTo(req.getBlockEndAt());
            verify(immediateBlockMapper).updateImmediateBlockPolicy(lineId, req);
        }
    }

    @Test
    @DisplayName("적용된 전체 정책 조회 성공")
    void getAppliedPolicies_success() {
        // given
        Long lineId = 1L;
        RepeatBlockPolicyResDto repeatPolicy = RepeatBlockPolicyResDto.builder().repeatBlockId(10L).build();
        ImmediateBlockResDto immBlock = ImmediateBlockResDto.builder().blockEndAt(LocalDateTime.of(2026, 3, 4, 12, 0)).build();

        when(repeatBlockMapper.selectRepeatBlocksByLineId(lineId)).thenReturn(List.of(repeatPolicy));
        when(immediateBlockMapper.selectImmediateBlockPolicy(lineId)).thenReturn(immBlock);

        // when
        AppliedPolicyResDto result = userPolicyService.getAppliedPolicies(lineId, authUserDetails);

        // then
        assertThat(result.getRepeatBlockPolicyList()).hasSize(1);
        assertThat(result.getImmediateBlock().getBlockEndAt()).isEqualTo(LocalDateTime.of(2026, 3, 4, 12, 0));
        verify(repeatBlockMapper).selectRepeatBlocksByLineId(lineId);
        verify(immediateBlockMapper).selectImmediateBlockPolicy(lineId);
    }
}
