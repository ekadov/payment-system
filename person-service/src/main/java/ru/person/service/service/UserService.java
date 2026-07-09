package ru.person.service.service;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.person.service.entity.*;
import ru.person.service.exception.BadRequestException;
import ru.person.service.exception.EmailAlreadyExistsException;
import ru.person.service.exception.NotFoundException;
import ru.person.service.generated.dto.*;
import ru.person.service.mapper.UserMapper;
import ru.person.service.repository.CountryRepository;
import ru.person.service.repository.UserRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@AllArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final CountryRepository countryRepository;
    private final UserMapper userMapper;

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (userRepository.existsByEmailIgnoreCase(request.getEmail())) {
            throw new EmailAlreadyExistsException("User with email '%s' already exists".formatted(request.getEmail()));
        }

        UserEntity user = new UserEntity();
        user
                .setEmail(request.getEmail())
                .setFirstName(request.getFirstName())
                .setLastName(request.getLastName());

        AddressEntity address = buildAddress(request.getAddress());
        IndividualEntity individual = buildIndividual(request.getIndividual());

        user
                .setAddress(address)
                .setIndividual(individual);
        individual.setUser(user);
        user.setFilled(isFilled(user));

        try {
            return userMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            throw translateDataIntegrityViolation(exception, request.getEmail());
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userMapper.toResponse(getUserEntity(id));
    }

    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        return userMapper.toResponse(
                userRepository.findByEmailIgnoreCase(email)
                        .orElseThrow(() -> new NotFoundException("User with email '%s' not found".formatted(email)))
        );
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request) {
        UserEntity user = getUserEntity(id);

        if (request.getEmail() != null && userRepository.existsByEmailIgnoreCaseAndIdNot(request.getEmail(), id)) {
            throw new EmailAlreadyExistsException("User with email '%s' already exists".formatted(request.getEmail()));
        }

        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getFirstName() != null) {
            user.setFirstName(request.getFirstName());
        }
        if (request.getLastName() != null) {
            user.setLastName(request.getLastName());
        }
        if (request.getAddress() != null) {
            updateAddress(user.getAddress(), request.getAddress());
        }
        if (request.getIndividual() != null) {
            updateIndividual(user.getIndividual(), request.getIndividual());
        }

        user.setFilled(isFilled(user));
        try {
            return userMapper.toResponse(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            throw translateDataIntegrityViolation(
                    exception, request.getEmail() != null ? request.getEmail() : user.getEmail());
        }
    }

    @Transactional
    public void delete(UUID id) {
        userRepository.delete(getUserEntity(id));
    }

    private UserEntity getUserEntity(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User with id '%s' not found".formatted(id)));
    }

    private AddressEntity buildAddress(CreateAddressRequest request) {
        CountryEntity country = resolveCountry(request.getCountryAlpha2(), request.getCountryAlpha3());

        AddressEntity address = new AddressEntity();
        address
                .setCreated(Instant.now())
                .setUpdated(Instant.now())
                .setCountry(country)
                .setCity(request.getCity())
                .setState(request.getState())
                .setZipCode(request.getZipCode())
                .setAddressLine(request.getAddressLine());
        return address;
    }

    private IndividualEntity buildIndividual(CreateIndividualRequest request) {
        IndividualEntity individual = new IndividualEntity();
        individual
                .setPassportNumber(request.getPassportNumber())
                .setPhoneNumber(request.getPhoneNumber())
                .setStatus(IndividualStatus.NEW);
        return individual;
    }

    private void updateAddress(AddressEntity address, UpdateAddressRequest request) {
        if (request.getCountryAlpha2() != null || request.getCountryAlpha3() != null) {
            if (request.getCountryAlpha2() == null || request.getCountryAlpha3() == null) {
                throw new BadRequestException("To change country you must send both countryAlpha2 and countryAlpha3");
            }
            address.setCountry(resolveCountry(request.getCountryAlpha2(), request.getCountryAlpha3()));
        }
        if (request.getCity() != null) {
            address.setCity(request.getCity());
        }
        if (request.getState() != null) {
            address.setState(request.getState());
        }
        if (request.getZipCode() != null) {
            address.setZipCode(request.getZipCode());
        }
        if (request.getAddressLine() != null) {
            address.setAddressLine(request.getAddressLine());
        }
        address.setUpdated(Instant.now());
    }

    private void updateIndividual(IndividualEntity individual, UpdateIndividualRequest request) {
        if (request.getPassportNumber() != null) {
            individual.setPassportNumber(request.getPassportNumber());
        }
        if (request.getPhoneNumber() != null) {
            individual.setPhoneNumber(request.getPhoneNumber());
        }
    }

    private CountryEntity resolveCountry(String alpha2, String alpha3) {
        return countryRepository.findByAlpha2IgnoreCaseAndAlpha3IgnoreCase(alpha2, alpha3)
                .orElseThrow(() -> new BadRequestException(
                        "Country with alpha2='%s' and alpha3='%s' not found".formatted(alpha2, alpha3)
                ));
    }

    private boolean isFilled(UserEntity user) {
        return hasText(user.getEmail())
                && hasText(user.getFirstName())
                && hasText(user.getLastName())
                && hasText(user.getAddress().getCity())
                && hasText(user.getAddress().getAddressLine())
                && hasText(user.getIndividual().getPassportNumber())
                && hasText(user.getIndividual().getPhoneNumber());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private RuntimeException translateDataIntegrityViolation(
            DataIntegrityViolationException exception,
            String email
    ) {
        String message = exception.getMostSpecificCause() != null
                ? exception.getMostSpecificCause().getMessage()
                : exception.getMessage();
        if (message != null && message.toLowerCase().contains("uk_users_email_lower")) {
            return new EmailAlreadyExistsException("User with email '%s' already exists".formatted(email));
        }
        return exception;
    }
}
