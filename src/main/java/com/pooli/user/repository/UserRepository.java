package com.pooli.user.repository;

import com.pooli.user.domain.entity.User;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserRepository {

    @Select("""
        SELECT
            user_id,
            user_name,
            email,
            password,
            age,
            created_at,
            deleted_at,
            updated_at
        FROM USERS
        WHERE email = #{email}
          AND deleted_at IS NULL
        """)
    User findByEmail(@Param("email") String email);

    @Select("""
        SELECT r.role_name
        FROM ROLE r
        JOIN USER_ROLE ur ON ur.role_id = r.role_id
        WHERE ur.user_id = #{userId}
        """)
    List<String> findRoleNamesByUserId(@Param("userId") Long userId);

    @Select("""
        SELECT fl.role
        FROM LINE l
        JOIN FAMILY_LINE fl ON fl.line_id = l.line_id
        WHERE l.user_id = #{userId}
          AND l.is_main = TRUE
        LIMIT 1
        """)
    String findFamilyRoleByMainLineUserId(@Param("userId") Long userId);
}
