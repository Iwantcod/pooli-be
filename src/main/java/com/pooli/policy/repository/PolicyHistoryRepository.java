package com.pooli.policy.repository;

import com.pooli.policy.domain.entity.PolicyHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PolicyHistoryRepository extends MongoRepository<PolicyHistory, String> {
}
