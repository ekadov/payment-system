package ru.transaction.service.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.transaction.service.entity.TransactionsEntity;

import java.util.Optional;
import java.util.UUID;

public interface TransactionsRepository extends JpaRepository<TransactionsEntity, UUID>,
        JpaSpecificationExecutor<TransactionsEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select transaction from TransactionsEntity transaction where transaction.uid = :uid")
    Optional<TransactionsEntity> findByUidForUpdate(UUID uid);

    Optional<TransactionsEntity> findByUserUidAndIdempotencyKey(UUID userUid, UUID idempotencyKey);
}
