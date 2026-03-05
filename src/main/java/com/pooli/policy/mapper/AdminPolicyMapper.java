package com.pooli.policy.mapper;

import com.pooli.policy.domain.dto.request.AdminCategoryReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyActiveReqDto;
import com.pooli.policy.domain.dto.request.AdminPolicyReqDto;
import com.pooli.policy.domain.dto.response.AdminPolicyCateResDto;
import com.pooli.policy.domain.dto.response.AdminPolicyResDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AdminPolicyMapper {

    // 전체 정책 목록 조회
    List<AdminPolicyResDto> selectAllPolicies();

    // 정책 단건 조회
    AdminPolicyResDto selectPolicyById(@Param("policyId") Integer policyId);
    
    // 정책 추가
    int insertPolicy(@Param("request")AdminPolicyReqDto request);

    // 정책 수정
    int updatePolicy(@Param("policyId") Integer policyId, @Param("request") AdminPolicyReqDto request);

    // 정책 삭제 (Soft Delete)
    int deletePolicy(@Param("policyId") Integer policyId);

    // 정책 활성/비활성 상태 변경
    int updatePolicyActiveStatus(@Param("policyId") Integer policyId, @Param("request") AdminPolicyActiveReqDto request);
    
    // 정책 카테고리 목록 조회
    List<AdminPolicyCateResDto> selectAllCategories();

    // 정책 카테고리 단건 추가
    AdminPolicyCateResDto selectCategoryById(@Param("policyCategoryId") Integer policyCategoryId);
    
    // 정책 카테고리 추가
    int insertCategory(@Param("request") AdminCategoryReqDto request);

    // 정책 카테고리 수정
    int updateCategory(@Param("policyCategoryId") Integer policyCategoryId, @Param("request") AdminCategoryReqDto request);

    // 정책 카테고리 삭제 (Soft Delete)
    int deleteCategory(@Param("policyCategoryId") Integer policyCategoryId);

}
