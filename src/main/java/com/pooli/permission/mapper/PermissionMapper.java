package com.pooli.permission.mapper;

import com.pooli.permission.domain.entity.Permission;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PermissionMapper {

    List<Permission> findAll();

    Optional<Permission> findById(Integer permissionId);

    boolean existsByPermissionTitle(String permissionTitle);

    void insert(Permission permission);

    void updateTitle(Permission permission);

    void softDelete(Integer permissionId);
}
