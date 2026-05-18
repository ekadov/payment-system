package ru.individuals.api.client.keycloak.mapper;

import org.mapstruct.Mapping;
import org.mapstruct.Mapper;
import ru.individuals.api.client.keycloak.generated.model.TokenEndpointResponse;
import ru.individuals.api.dto.TokenResponse;

@Mapper(componentModel = "spring")
public interface TokenMapper {

    @Mapping(source = "accessToken", target = "accessToken")
    @Mapping(source = "refreshToken", target = "refreshToken")
    @Mapping(source = "expiresIn", target = "expiresIn")
    @Mapping(source = "tokenType", target = "tokenType")
    TokenResponse toDto(TokenEndpointResponse response);
}
