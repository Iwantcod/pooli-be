package com.pooli.family.repository.mongo;

import com.pooli.family.domain.dto.mongo.SharedPoolTransferLog;
import org.springframework.data.mongodb.repository.MongoRepository;


public interface SharedPoolTransferLogRepository
        extends MongoRepository<SharedPoolTransferLog, String> {
}