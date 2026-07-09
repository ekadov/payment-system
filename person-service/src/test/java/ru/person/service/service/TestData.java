package ru.person.service.service;

import ru.person.service.generated.dto.CreateAddressRequest;
import ru.person.service.generated.dto.CreateIndividualRequest;
import ru.person.service.generated.dto.CreateUserRequest;

final class TestData {

    private TestData() {
    }

    static CreateUserRequest createUserRequest() {
        return new CreateUserRequest(
                "ivan.petrov@example.org",
                "Ivan",
                "Petrov",
                new CreateAddressRequest("RUS", "RU", "Moscow", "Example st. 1")
                        .state("Moscow")
                        .zipCode("101000"),
                new CreateIndividualRequest("1234 567890", "+79991234567")
        );
    }
}
