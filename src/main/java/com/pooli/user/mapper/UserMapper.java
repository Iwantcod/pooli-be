package com.pooli.user.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.pooli.user.domain.entity.User;

@Mapper
public interface UserMapper {

    User findByEmail(@Param("email") String email);

    List<String> findRoleNamesByUserId(@Param("userId") Long userId);

    String findFamilyRoleByMainLineUserId(@Param("userId") Long userId);

    Long findMainLineIdByUserId(@Param("userId") Long userId);
}
