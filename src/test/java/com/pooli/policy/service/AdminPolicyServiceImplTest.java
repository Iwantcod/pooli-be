package com.pooli.policy.service;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyActiveResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import com.pooli.policy.mapper.AdminPolicyMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPolicyServiceImplTest {

    @Mock
    private AdminPolicyMapper adminPolicyMapper;

    @InjectMocks
    private AdminPolicyServiceImpl adminPolicyService;

    @Nested
    @DisplayName("정책(Policy) 관련 테스트")
    class PolicyTests {

        @Test
        @DisplayName("전체 정책 목록 조회 성공")
        void getAllPolicies_success() {
            // given
            AdminPolicyResDto policy = AdminPolicyResDto.builder()
                    .policyId(1)
                    .policyName("테스트 정책")
                    .build();
            when(adminPolicyMapper.selectAllPolicies()).thenReturn(List.of(policy));

            // when
            List<AdminPolicyResDto> result = adminPolicyService.getAllPolicies();

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyName()).isEqualTo("테스트 정책");
            verify(adminPolicyMapper, times(1)).selectAllPolicies();
        }

        @Test
        @DisplayName("정책 추가 성공")
        void createPolicy_success() {
            // given
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("신규 정책");
            req.setPolicyCategoryId(1);
            
            // MyBatis useGeneratedKeys 시뮬레이션: 파라미터 객체에 ID를 세팅하도록 mock 설정
            doAnswer(invocation -> {
                AdminPolicyReqDto dto = invocation.getArgument(0);
                dto.setPolicyId(100);
                return 1;
            }).when(adminPolicyMapper).insertPolicy(any());

            // when
            AdminPolicyResDto result = adminPolicyService.createPolicy(req);

            // then
            assertThat(req.getPolicyId()).isEqualTo(100);
            assertThat(result.getPolicyId()).isEqualTo(100);
            assertThat(result.getPolicyName()).isEqualTo("신규 정책");
            verify(adminPolicyMapper, times(1)).insertPolicy(req);
        }

        @Test
        @DisplayName("정책 수정 성공")
        void updatePolicy_success() {
            // given
            Integer policyId = 1;
            AdminPolicyReqDto req = new AdminPolicyReqDto();
            req.setPolicyName("수정된 정책");
            req.setIsActive(true);

            when(adminPolicyMapper.updatePolicy(eq(policyId), any())).thenReturn(1);

            // when
            AdminPolicyResDto result = adminPolicyService.updatePolicy(policyId, req);

            // then
            assertThat(result.getPolicyId()).isEqualTo(policyId);
            assertThat(result.getPolicyName()).isEqualTo("수정된 정책");
            verify(adminPolicyMapper, times(1)).updatePolicy(eq(policyId), any());
        }

        @Test
        @DisplayName("정책 삭제 성공")
        void deletePolicy_success() {
            // given
            Integer policyId = 1;
            when(adminPolicyMapper.deletePolicy(policyId)).thenReturn(1);

            // when
            AdminPolicyResDto result = adminPolicyService.deletePolicy(policyId);

            // then
            assertThat(result.getPolicyId()).isEqualTo(policyId);
            verify(adminPolicyMapper, times(1)).deletePolicy(policyId);
        }

        @Test
        @DisplayName("정책 활성화 상태 변경 성공")
        void updateActivationPolicy_success() {
            // given
            Integer policyId = 1;
            AdminPolicyActiveReqDto req = new AdminPolicyActiveReqDto();
            req.setIsActive(true);

            when(adminPolicyMapper.updatePolicyActiveStatus(eq(policyId), any())).thenReturn(1);

            // when
            AdminPolicyActiveResDto result = adminPolicyService.updateActivationPolicy(policyId, req);

            // then
            assertThat(result.getPolicyId()).isEqualTo(policyId);
            assertThat(result.getIsActive()).isTrue();
            verify(adminPolicyMapper, times(1)).updatePolicyActiveStatus(eq(policyId), any());
        }
    }

    @Nested
    @DisplayName("카테고리(Category) 관련 테스트")
    class CategoryTests {

        @Test
        @DisplayName("카테고리 목록 조회 성공")
        void getCategories_success() {
            // given
            AdminPolicyCateResDto cate = AdminPolicyCateResDto.builder()
                    .policyCategoryId(1)
                    .policyCategoryName("차단")
                    .build();
            when(adminPolicyMapper.selectAllCategories()).thenReturn(List.of(cate));

            // when
            List<AdminPolicyCateResDto> result = adminPolicyService.getCategories();

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPolicyCategoryName()).isEqualTo("차단");
            verify(adminPolicyMapper, times(1)).selectAllCategories();
        }

        @Test
        @DisplayName("카테고리 추가 성공")
        void createCategory_success() {
            // given
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("광고");
            
            doAnswer(invocation -> {
                AdminCategoryReqDto dto = invocation.getArgument(0);
                dto.setPolicyCategoryId(200);
                return 1;
            }).when(adminPolicyMapper).insertCategory(any());

            // when
            AdminPolicyCateResDto result = adminPolicyService.createCategory(req);

            // then
            assertThat(req.getPolicyCategoryId()).isEqualTo(200);
            assertThat(result.getPolicyCategoryId()).isEqualTo(200);
            assertThat(result.getPolicyCategoryName()).isEqualTo("광고");
            verify(adminPolicyMapper, times(1)).insertCategory(req);
        }

        @Test
        @DisplayName("카테고리 수정 성공")
        void updateCategory_success() {
            // given
            Integer cateId = 1;
            AdminCategoryReqDto req = new AdminCategoryReqDto();
            req.setPolicyCategoryName("수정된 카테고리");

            when(adminPolicyMapper.updateCategory(eq(cateId), any())).thenReturn(1);

            // when
            AdminPolicyCateResDto result = adminPolicyService.updateCategory(cateId, req);

            // then
            assertThat(result.getPolicyCategoryId()).isEqualTo(cateId);
            assertThat(result.getPolicyCategoryName()).isEqualTo("수정된 카테고리");
            verify(adminPolicyMapper, times(1)).updateCategory(eq(cateId), any());
        }

        @Test
        @DisplayName("카테고리 삭제 성공")
        void deleteCategory_success() {
            // given
            Integer cateId = 1;
            when(adminPolicyMapper.deleteCategory(cateId)).thenReturn(1);

            // when
            AdminPolicyCateResDto result = adminPolicyService.deleteCategory(cateId);

            // then
            assertThat(result.getPolicyCategoryId()).isEqualTo(cateId);
            verify(adminPolicyMapper, times(1)).deleteCategory(cateId);
        }
    }
}
