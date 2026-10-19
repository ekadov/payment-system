package ru.individuals.api.config;

import feign.RequestInterceptor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.concurrent.Callable;

/**
 * Пересылает JWT пользователя в Transaction Service
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TransactionServiceTokenRelay {

    private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

    public static <T> T callAs(Jwt jwt, Callable<T> operation) throws Exception {
        TOKEN.set(jwt.getTokenValue());
        try {
            return operation.call();
        } finally {
            TOKEN.remove();
        }
    }

    static RequestInterceptor interceptor() {
        return template -> {
            String token = TOKEN.get();
            if (token != null) {
                template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            }
        };
    }
}
