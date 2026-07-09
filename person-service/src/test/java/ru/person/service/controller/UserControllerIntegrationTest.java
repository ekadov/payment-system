package ru.person.service.controller;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import ru.person.service.repository.UserRepository;
import ru.person.service.testsupport.IntegrationTestContainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class UserControllerIntegrationTest extends IntegrationTestContainers {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext).build();
    }

    @Test
    void shouldCreateGetUpdateAndDeleteUser() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"ivan.petrov@example.org",
                                  "firstName":"Ivan",
                                  "lastName":"Petrov",
                                  "address":{
                                    "countryAlpha3":"RUS",
                                    "countryAlpha2":"RU",
                                    "city":"Moscow",
                                    "state":"Moscow",
                                    "zipCode":"101000",
                                    "addressLine":"Example st. 1"
                                  },
                                  "individual":{
                                    "passportNumber":"1234 567890",
                                    "phoneNumber":"+79991234567"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ivan.petrov@example.org"))
                .andExpect(jsonPath("$.filled").value(true))
                .andReturn();

        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID id = UUID.fromString(createJson.get("id").asText());
        UUID addressId = UUID.fromString(createJson.get("address").get("id").asText());
        UUID individualId = UUID.fromString(createJson.get("individual").get("id").asText());

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.address.city").value("Moscow"));

        mockMvc.perform(get("/api/v1/users/by-email")
                        .param("email", "IVAN.PETROV@EXAMPLE.ORG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "lastName":"Petrov-Senior",
                                  "address":{"city":"Saint Petersburg"},
                                  "individual":{"phoneNumber":"+79990000000"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastName").value("Petrov-Senior"))
                .andExpect(jsonPath("$.address.city").value("Saint Petersburg"))
                .andExpect(jsonPath("$.individual.phoneNumber").value("+79990000000"));

        mockMvc.perform(delete("/api/v1/users/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isNotFound());

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(userRepository.findById(id)).isEmpty();
        softAssertions.assertThat(countById("person.users", id)).isZero();
        softAssertions.assertThat(countById("person.addresses", addressId)).isZero();
        softAssertions.assertThat(countById("person.individuals", individualId)).isZero();

        softAssertions.assertAll();
    }

    @Test
    void shouldReturnConflictWhenEmailAlreadyExists() throws Exception {
        String body = """
                {
                  "email":"duplicate@example.org",
                  "firstName":"Ivan",
                  "lastName":"Petrov",
                  "address":{
                    "countryAlpha3":"RUS",
                    "countryAlpha2":"RU",
                    "city":"Moscow",
                    "state":"Moscow",
                    "zipCode":"101000",
                    "addressLine":"Example st. 1"
                  },
                  "individual":{
                    "passportNumber":"1234 567890",
                    "phoneNumber":"+79991234567"
                  }
                }
                """;

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Email already exists"));
    }

    @Test
    void shouldReturnProblemDetailsForValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"",
                                  "firstName":"Ivan",
                                  "lastName":"Petrov",
                                  "address":{
                                    "countryAlpha3":"RUS",
                                    "countryAlpha2":"RU",
                                    "city":"Moscow",
                                    "addressLine":"Example st. 1"
                                  },
                                  "individual":{
                                    "passportNumber":"1234 567890",
                                    "phoneNumber":"+79991234567"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/api/v1/users"));
    }

    @Test
    void shouldRollbackWholeAggregateWhenExceptionAfterPartialChange() throws Exception {
        UUID id = createUser("rollback@example.org");

        // firstName валиден и будет применён к сущности ДО обращения к неизвестной стране,
        // которое бросит исключение и вся операция должна откатиться.
        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName":"Changed",
                                  "address":{"countryAlpha2":"XX","countryAlpha3":"XXX"}
                                }
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/users/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Ivan"));
    }

    @Test
    void shouldRecordEnversRevisionsAfterChange() throws Exception {
        UUID id = createUser("envers@example.org");

        mockMvc.perform(patch("/api/v1/users/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lastName":"Petrov-Senior"}
                                """))
                .andExpect(status().isOk());

        Integer revisions = jdbcTemplate.queryForObject(
                "select count(*) from person.users_aud where id = ?", Integer.class, id);
        assertThat(revisions).isGreaterThanOrEqualTo(2);

        Boolean lastNameModified = jdbcTemplate.queryForObject(
                "select last_name_mod from person.users_aud where id = ? order by rev desc limit 1",
                Boolean.class, id);
        assertThat(lastNameModified).isTrue();
    }

    @Test
    void shouldExposeActuatorEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    private UUID createUser(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"%s",
                                  "firstName":"Ivan",
                                  "lastName":"Petrov",
                                  "address":{
                                    "countryAlpha3":"RUS",
                                    "countryAlpha2":"RU",
                                    "city":"Moscow",
                                    "state":"Moscow",
                                    "zipCode":"101000",
                                    "addressLine":"Example st. 1"
                                  },
                                  "individual":{
                                    "passportNumber":"1234 567890",
                                    "phoneNumber":"+79991234567"
                                  }
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }

    private int countById(String tableName, UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + tableName + " where id = ?",
                Integer.class,
                id
        );
        return count == null ? 0 : count;
    }
}
