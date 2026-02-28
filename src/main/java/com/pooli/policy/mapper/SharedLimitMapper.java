package com.pooli.policy.mapper;

import com.pooli.policy.domain.entity.SharedLimit;
import org.apache.ibatis.annotations.Mapper;

import java.util.Optional;

@Mapper
public interface SharedLimitMapper {
    Optional<SharedLimit> getSharedLimitByLineId(Long lineId);
}
