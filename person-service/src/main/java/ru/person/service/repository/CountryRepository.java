package ru.person.service.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.person.service.entity.CountryEntity;

import java.util.Optional;

public interface CountryRepository extends JpaRepository<CountryEntity, Integer> {

    Optional<CountryEntity> findByAlpha2IgnoreCaseAndAlpha3IgnoreCase(String alpha2, String alpha3);
}
