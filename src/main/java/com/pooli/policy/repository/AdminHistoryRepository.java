package com.pooli.policy.repository;

import com.pooli.policy.domain.entity.AdminHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminHistoryRepository extends MongoRepository<AdminHistory, String> {
}
