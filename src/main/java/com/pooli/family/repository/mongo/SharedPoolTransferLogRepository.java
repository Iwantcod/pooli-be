package com.pooli.family.repository.mongo;

import java.time.Instant;
import java.util.List;

import com.pooli.family.domain.dto.mongo.SharedPoolTransferLog;
import org.springframework.data.mongodb.repository.MongoRepository;


public interface SharedPoolTransferLogRepository
        extends MongoRepository<SharedPoolTransferLog, String> {

    List<SharedPoolTransferLog> findByFamilyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            Long familyId,
            Instant startInclusive,
            Instant endExclusive
    );
}
