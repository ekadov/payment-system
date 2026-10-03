package ru.transaction.service.service;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.transaction.service.entity.WalletEntity;
import ru.transaction.service.entity.WalletStatus;
import ru.transaction.service.entity.WalletTypesEntity;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.NotFoundException;
import ru.transaction.service.exception.WalletAlreadyExistsException;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.generated.dto.WalletResponse;
import ru.transaction.service.mapper.WalletMapper;
import ru.transaction.service.repository.WalletTypesRepository;
import ru.transaction.service.repository.WalletsRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
public class WalletService {

    private final WalletsRepository walletsRepository;
    private final WalletTypesRepository walletTypesRepository;
    private final WalletMapper walletMapper;

    @Transactional
    public WalletResponse createWallet(CreateWalletRequest createWalletRequest) {
        // Быстрая проверка для понятной ошибки
        if (walletsRepository.existsByNameAndUserUid(
                createWalletRequest.getName(),
                createWalletRequest.getUserUid())
        ) {
            throw new WalletAlreadyExistsException("The wallet '%s' already exists.".formatted(createWalletRequest.getName()));
        }

        WalletTypesEntity walletTypesEntity = walletTypesRepository
                .findById(createWalletRequest.getWalletTypeUid())
                .orElseThrow(() ->
                        new NotFoundException("Wallet type not found: " + createWalletRequest.getWalletTypeUid()));

        if (!"ACTIVE".equals(walletTypesEntity.getStatus())) {
            throw new BadRequestException("Wallet type is not active");
        }

        WalletEntity walletEntity = new WalletEntity();
        walletEntity
                .setName(createWalletRequest.getName())
                .setStatus(WalletStatus.ACTIVE.name())
                .setUserUid(createWalletRequest.getUserUid())
                .setWalletTypeUid(walletTypesEntity.getUid())
                .setBalance(BigDecimal.ZERO);

        return walletMapper.toResponse(walletsRepository.save(walletEntity), walletTypesEntity);
    }

    @Transactional(readOnly = true)
    public WalletResponse getByUid(UUID uid) {
        WalletEntity wallet = getWalletEntity(uid);
        return walletMapper.toResponse(wallet, getWalletType(wallet));
    }

    @Transactional(readOnly = true)
    public List<WalletResponse> getByUserUid(UUID userUid) {
        return walletsRepository.findByUserUid(userUid).stream()
                .map(wallet -> walletMapper.toResponse(wallet, getWalletType(wallet)))
                .toList();
    }

    WalletEntity getWalletEntity(UUID uid) {
        return walletsRepository.findById(uid)
                .orElseThrow(() -> new NotFoundException("Wallet with id '%s' not found".formatted(uid)));
    }

    WalletEntity getWalletEntityForUpdate(UUID uid) {
        return walletsRepository.findByUidForUpdate(uid)
                .orElseThrow(() -> new NotFoundException("Wallet with id '%s' not found".formatted(uid)));
    }

    WalletTypesEntity getWalletType(WalletEntity wallet) {
        return walletTypesRepository.findById(wallet.getWalletTypeUid())
                .orElseThrow(() -> new NotFoundException("Wallet type not found: " + wallet.getWalletTypeUid()));
    }
}
