package ru.person.service.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.person.service.entity.UserEntity;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    @Override
    @EntityGraph(attributePaths = {"address", "address.country", "individual"})
    Optional<UserEntity> findById(UUID id);

    @EntityGraph(attributePaths = {"address", "address.country", "individual"})
    Optional<UserEntity> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndIdNot(String email, UUID id);
}
