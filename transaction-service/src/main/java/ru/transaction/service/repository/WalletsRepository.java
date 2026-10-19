package ru.transaction.service.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.transaction.service.entity.WalletEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletsRepository extends JpaRepository<WalletEntity, UUID> {

    @Override
    Optional<WalletEntity> findById(UUID uid);

    List<WalletEntity> findByUserUid(UUID userUid);

    boolean existsByNameAndUserUid(String name, UUID userUid);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from WalletEntity wallet where wallet.uid = :uid")
    Optional<WalletEntity> findByUidForUpdate(UUID uid);
}
