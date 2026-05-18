package ru.individuals.api.models;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@AllArgsConstructor
public class CredentialRepresentation {
    private String type;
    private String value;
    private boolean temporary;
}
