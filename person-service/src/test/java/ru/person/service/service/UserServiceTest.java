package ru.person.service.service;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import ru.person.service.entity.*;
import ru.person.service.exception.BadRequestException;
import ru.person.service.exception.EmailAlreadyExistsException;
import ru.person.service.exception.NotFoundException;
import ru.person.service.generated.dto.UpdateAddressRequest;
import ru.person.service.generated.dto.UpdateIndividualRequest;
import ru.person.service.generated.dto.UpdateUserRequest;
import ru.person.service.generated.dto.UserResponse;
import ru.person.service.mapper.UserMapper;
import ru.person.service.repository.CountryRepository;
import ru.person.service.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CountryRepository countryRepository;

    private UserService userService;

    private static CountryEntity country() {
        CountryEntity country = new CountryEntity();
        country
                .setAlpha2("RU")
                .setName("Russia")
                .setAlpha3("RUS")
                .setStatus("ACTIVE");
        return country;
    }

    private static UserEntity existingUser() {
        AddressEntity address = new AddressEntity();
        ReflectionTestUtils.setField(address, "id", UUID.randomUUID());
        address
                .setCountry(country())
                .setCity("Moscow")
                .setAddressLine("Example st. 1");

        IndividualEntity individual = new IndividualEntity();
        ReflectionTestUtils.setField(individual, "id", UUID.randomUUID());
        individual
                .setPassportNumber("1234 567890")
                .setPhoneNumber("+79991234567")
                .setStatus(IndividualStatus.NEW);

        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user
                .setEmail("ivan.petrov@example.org")
                .setFirstName("Ivan")
                .setLastName("Petrov")
                .setAddress(address)
                .setIndividual(individual);

        individual.setUser(user);
        return user;
    }

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository, countryRepository, new UserMapper());
    }

    @Test
    void shouldCreateUserAndMarkFilled() {
        when(userRepository.existsByEmailIgnoreCase("ivan.petrov@example.org")).thenReturn(false);
        when(countryRepository.findByAlpha2IgnoreCaseAndAlpha3IgnoreCase("RU", "RUS"))
                .thenReturn(Optional.of(country()));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = userService.create(TestData.createUserRequest());

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getEmail()).isEqualTo("ivan.petrov@example.org");
        softAssertions.assertThat(response.getFilled()).isTrue();
        softAssertions.assertThat(response.getAddress().getCity()).isEqualTo("Moscow");
        softAssertions.assertThat(response.getAddress().getCountryAlpha3()).isEqualTo("RUS");
        softAssertions.assertThat(response.getIndividual().getStatus()).isEqualTo("NEW");

        softAssertions.assertAll();
    }

    @Test
    void shouldRejectDuplicateEmailOnCreate() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(true);

        assertThatThrownBy(() -> userService.create(TestData.createUserRequest()))
                .isInstanceOf(EmailAlreadyExistsException.class);

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void shouldFailWhenCountryPairIsUnknown() {
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(countryRepository.findByAlpha2IgnoreCaseAndAlpha3IgnoreCase(anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.create(TestData.createUserRequest()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Country");
    }

    @Test
    void shouldThrowNotFoundWhenGetByIdMissing() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(id))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldThrowNotFoundWhenGetByEmailMissing() {
        when(userRepository.findByEmailIgnoreCase("nobody@example.org")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail("nobody@example.org"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldUpdateNestedEntitiesInSingleOperation() {
        UserEntity existing = existingUser();
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateUserRequest request = new UpdateUserRequest()
                .lastName("Petrov-Senior")
                .address(new UpdateAddressRequest().city("Saint Petersburg"))
                .individual(new UpdateIndividualRequest().phoneNumber("+79990000000"));

        UserResponse response = userService.update(existing.getId(), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getLastName()).isEqualTo("Petrov-Senior");
        softAssertions.assertThat(response.getAddress().getCity()).isEqualTo("Saint Petersburg");
        softAssertions.assertThat(response.getIndividual().getPhoneNumber()).isEqualTo("+79990000000");

        softAssertions.assertAll();
    }

    @Test
    void shouldRejectPartialCountryOnUpdate() {
        UserEntity existing = existingUser();
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        UpdateUserRequest request = new UpdateUserRequest()
                .address(new UpdateAddressRequest().countryAlpha2("US"));

        assertThatThrownBy(() -> userService.update(existing.getId(), request))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("both");
    }

    @Test
    void shouldRejectDuplicateEmailOnUpdate() {
        UserEntity existing = existingUser();
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot(eq("taken@example.org"), any(UUID.class)))
                .thenReturn(true);

        UpdateUserRequest request = new UpdateUserRequest().email("taken@example.org");

        assertThatThrownBy(() -> userService.update(existing.getId(), request))
                .isInstanceOf(EmailAlreadyExistsException.class);
    }

    @Test
    void shouldDeleteExistingUser() {
        UserEntity existing = existingUser();
        when(userRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        userService.delete(existing.getId());

        verify(userRepository).delete(existing);
    }
}
