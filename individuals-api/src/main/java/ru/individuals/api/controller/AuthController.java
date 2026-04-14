package ru.individuals.api.controller;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.individuals.api.dto.*;
import ru.individuals.api.service.TokenService;
import ru.individuals.api.service.UserService;

@RestController
@RequestMapping("v1/auth")
@AllArgsConstructor
public class AuthController {

    private final TokenService tokenService;
    private final UserService userService;

    @PostMapping("/registration")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TokenResponse> register(@Valid @RequestBody UserRegistrationRequest userRegistrationRequest) {
        return userService.register(userRegistrationRequest);
    }

    @PostMapping("/login")
    public Mono<TokenResponse> login(@Valid @RequestBody UserLoginRequest userLoginRequest) {
        return tokenService.login(userLoginRequest);
    }

    @PostMapping("/refresh-token")
    public Mono<TokenResponse> refreshToken(@Valid @RequestBody TokenRefreshRequest tokenRefreshRequest) {
        return tokenService.refresh(tokenRefreshRequest);
    }

    @GetMapping("/me")
    public Mono<UserInfoResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return Mono.just(extractUserDto(jwt));
    }

    private UserInfoResponse extractUserDto(Jwt jwt) {
        var response = new UserInfoResponse();
        response.setId(jwt.getClaim("sub"));
        response.setEmail(jwt.getClaim("email"));

        return response;
    }

}
