package ru.person.service.mapper;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.person.service.entity.*;
import ru.person.service.generated.dto.UserResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

class UserMapperTest {

    private final UserMapper mapper = new UserMapper();

    @Test
    void shouldMapEntityGraphToResponseWithUtcDates() {
        Instant created = Instant.parse("2026-06-08T10:15:30Z");
        Instant verified = Instant.parse("2026-06-09T08:00:00Z");

        CountryEntity country = new CountryEntity();
        country
                .setAlpha2("RU")
                .setAlpha3("RUS");

        AddressEntity address = new AddressEntity();
        UUID addressId = UUID.randomUUID();
        ReflectionTestUtils.setField(address, "id", addressId);
        address
                .setCountry(country)
                .setCity("Moscow")
                .setState("Moscow")
                .setZipCode("101000")
                .setAddressLine("Example st. 1");

        IndividualEntity individual = new IndividualEntity();
        UUID individualId = UUID.randomUUID();
        ReflectionTestUtils.setField(individual, "id", individualId);
        individual
                .setPassportNumber("1234 567890")
                .setPhoneNumber("+79991234567")
                .setStatus(IndividualStatus.NEW)
                .setVerifiedAt(verified);

        UserEntity user = new UserEntity();
        UUID userId = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", userId);
        ReflectionTestUtils.setField(user, "created", created);
        ReflectionTestUtils.setField(user, "updated", created);
        user
                .setEmail("ivan.petrov@example.org")
                .setFirstName("Ivan")
                .setLastName("Petrov")
                .setFilled(true)
                .setAddress(address)
                .setIndividual(individual);

        UserResponse response = mapper.toResponse(user);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getId()).isEqualTo(userId);
        softAssertions.assertThat(response.getEmail()).isEqualTo("ivan.petrov@example.org");
        softAssertions.assertThat(response.getFilled()).isTrue();
        softAssertions.assertThat(response.getCreatedAt()).isEqualTo(OffsetDateTime.ofInstant(created, ZoneOffset.UTC));
        softAssertions.assertThat(response.getAddress().getId()).isEqualTo(addressId);
        softAssertions.assertThat(response.getAddress().getCountryAlpha2()).isEqualTo("RU");
        softAssertions.assertThat(response.getAddress().getZipCode()).isEqualTo("101000");
        softAssertions.assertThat(response.getIndividual().getId()).isEqualTo(individualId);
        softAssertions.assertThat(response.getIndividual().getStatus()).isEqualTo("NEW");
        softAssertions.assertThat(response.getIndividual().getVerifiedAt())
                .isEqualTo(OffsetDateTime.ofInstant(verified, ZoneOffset.UTC));
        softAssertions.assertThat(response.getIndividual().getArchivedAt()).isNull();

        softAssertions.assertAll();
    }
}
