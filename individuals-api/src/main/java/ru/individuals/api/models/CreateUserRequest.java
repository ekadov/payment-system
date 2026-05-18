package ru.individuals.api.models;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class CreateUserRequest {
    String email;
    String username;
    boolean enabled;
    List<CredentialRepresentation> credentials;
    boolean emailVerified;
}
