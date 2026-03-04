package com.pooli.notification.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface NotificationLineMapper {

    List<Long> findAllLineIds();
    List<Long> findLineIdsByRole(String role);
    List<Long> findExistingLineIds(List<Long> lineIds);
}
