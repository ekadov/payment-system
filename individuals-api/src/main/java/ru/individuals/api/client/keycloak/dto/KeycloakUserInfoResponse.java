package ru.individuals.api.client.keycloak.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class KeycloakUserInfoResponse {

    @JsonProperty("sub")
    private String sub;

    @JsonProperty("email")
    private String email;
}
