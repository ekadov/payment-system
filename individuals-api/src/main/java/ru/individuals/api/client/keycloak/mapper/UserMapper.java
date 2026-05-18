package ru.individuals.api.client.keycloak.mapper;

import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import ru.individuals.api.client.keycloak.generated.model.CreateUserRequest;
import ru.individuals.api.client.keycloak.generated.model.CredentialRepresentation;
import ru.individuals.api.dto.UserRegistrationRequest;

import java.util.List;

@org.mapstruct.Mapper(componentModel = "spring")
public interface UserMapper {
    UserMapper INSTANCE = Mappers.getMapper(UserMapper.class);

    @Mapping(source = "email", target = "email")
    @Mapping(source = "email", target = "username")
    @Mapping(target = "enabled", constant = "true")
    @Mapping(target = "emailVerified", constant = "true")
    @Mapping(target = "credentials", expression = "java(createPasswordCredential(request.getPassword()))")
    CreateUserRequest toDto(UserRegistrationRequest request);

    default List<CredentialRepresentation> createPasswordCredential(String password) {
        return List.of(new CredentialRepresentation()
                .type("password")
                .value(password)
                .temporary(false));
    }
}
