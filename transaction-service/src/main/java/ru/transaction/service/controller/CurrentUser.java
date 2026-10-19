package ru.transaction.service.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import ru.transaction.service.exception.ForbiddenException;

import java.util.UUID;

/**
 * Пользователь, от имени которого выполняется запрос: subject JWT, пересланного шлюзом.
 */
@Component
public class CurrentUser {

    public UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ForbiddenException("Caller is not authenticated with JWT");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ForbiddenException("JWT subject is not an UUID");
        }
    }

    public void requireOwner(UUID ownerUid) {
        if (!id().equals(ownerUid)) {
            throw new ForbiddenException("Access to another user's data is forbidden");
        }
    }
}
