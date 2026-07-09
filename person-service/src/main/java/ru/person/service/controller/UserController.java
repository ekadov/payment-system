package ru.person.service.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.person.service.generated.api.UsersApiDelegate;
import ru.person.service.generated.dto.CreateUserRequest;
import ru.person.service.generated.dto.UpdateUserRequest;
import ru.person.service.generated.dto.UserResponse;
import ru.person.service.service.UserService;

import java.util.UUID;

@Service
@AllArgsConstructor
public class UserController implements UsersApiDelegate {

    private final UserService userService;

    @Override
    public ResponseEntity<UserResponse> createUser(CreateUserRequest createUserRequest) {
        UserResponse response = userService.create(createUserRequest);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Override
    public ResponseEntity<UserResponse> getUserById(UUID id) {
        return ResponseEntity.ok(userService.getById(id));
    }

    @Override
    public ResponseEntity<UserResponse> getUserByEmail(String email) {
        return ResponseEntity.ok(userService.getByEmail(email));
    }

    @Override
    public ResponseEntity<UserResponse> updateUser(UUID id, UpdateUserRequest updateUserRequest) {
        return ResponseEntity.ok(userService.update(id, updateUserRequest));
    }

    @Override
    public ResponseEntity<Void> deleteUser(UUID id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
