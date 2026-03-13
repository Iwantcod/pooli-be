package com.pooli.family.repository.mongo;

import java.time.Instant;
import java.util.List;

import com.pooli.family.domain.dto.mongo.SharedPoolTransferLog;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;


public interface SharedPoolTransferLogRepository
        extends MongoRepository<SharedPoolTransferLog, String> {

    @Query("{ 'familyId': ?0, 'createdAt': { $gte: ?1, $lt: ?2 } }")
    List<SharedPoolTransferLog> findByFamilyIdAndCreatedAtBetween(
            Long familyId,
            Instant startInclusive,
            Instant endExclusive,
            Sort sort
    );
}
