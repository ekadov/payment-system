package ru.person.service.mapper;

import org.springframework.stereotype.Component;
import ru.person.service.entity.AddressEntity;
import ru.person.service.entity.IndividualEntity;
import ru.person.service.entity.UserEntity;
import ru.person.service.generated.dto.AddressResponse;
import ru.person.service.generated.dto.IndividualResponse;
import ru.person.service.generated.dto.UserResponse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class UserMapper {

    public UserResponse toResponse(UserEntity user) {
        return new UserResponse()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .filled(user.isFilled())
                .createdAt(toOffset(user.getCreated()))
                .updatedAt(toOffset(user.getUpdated()))
                .address(toAddressResponse(user.getAddress()))
                .individual(toIndividualResponse(user.getIndividual()));
    }

    private AddressResponse toAddressResponse(AddressEntity address) {
        return new AddressResponse()
                .id(address.getId())
                .countryAlpha3(address.getCountry().getAlpha3())
                .countryAlpha2(address.getCountry().getAlpha2())
                .city(address.getCity())
                .state(address.getState())
                .zipCode(address.getZipCode())
                .addressLine(address.getAddressLine());
    }

    private IndividualResponse toIndividualResponse(IndividualEntity individual) {
        return new IndividualResponse()
                .id(individual.getId())
                .passportNumber(individual.getPassportNumber())
                .phoneNumber(individual.getPhoneNumber())
                .status(individual.getStatus().name())
                .verifiedAt(toOffset(individual.getVerifiedAt()))
                .archivedAt(toOffset(individual.getArchivedAt()));
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
