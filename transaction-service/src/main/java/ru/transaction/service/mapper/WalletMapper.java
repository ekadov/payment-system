package ru.transaction.service.mapper;

import org.springframework.stereotype.Component;
import ru.transaction.service.entity.WalletEntity;
import ru.transaction.service.entity.WalletTypesEntity;
import ru.transaction.service.generated.dto.WalletResponse;
import ru.transaction.service.generated.dto.WalletStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Component
public class WalletMapper {

    public WalletResponse toResponse(WalletEntity wallet, WalletTypesEntity walletType) {
        return new WalletResponse()
                .uid(wallet.getUid())
                .balance(wallet.getBalance())
                .name(wallet.getName())
                .currencyCode(walletType == null ? null : walletType.getCurrencyCode())
                .status(WalletStatus.valueOf(wallet.getStatus()))
                .walletTypeUid(wallet.getWalletTypeUid())
                .createdAt(toOffset(wallet.getCreatedAt()))
                .modifiedAt(toOffset(wallet.getModifiedAt()))
                .userUid(wallet.getUserUid());
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
