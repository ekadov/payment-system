package ru.transaction.service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.transaction.service.entity.WalletTypesEntity;

import java.util.Optional;
import java.util.UUID;

public interface WalletTypesRepository extends JpaRepository<WalletTypesEntity, UUID> {

    @Override
    Optional<WalletTypesEntity> findById(UUID uid);
}
