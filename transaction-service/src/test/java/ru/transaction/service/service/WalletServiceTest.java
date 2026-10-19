package ru.transaction.service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.transaction.service.entity.WalletEntity;
import ru.transaction.service.entity.WalletTypesEntity;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.WalletAlreadyExistsException;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.mapper.WalletMapper;
import ru.transaction.service.repository.WalletTypesRepository;
import ru.transaction.service.repository.WalletsRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock
    private WalletsRepository walletsRepository;
    @Mock
    private WalletTypesRepository walletTypesRepository;
    @Mock
    private WalletMapper walletMapper;
    @InjectMocks
    private WalletService walletService;

    @Test
    void rejectsDuplicateWalletNameForUser() {
        UUID userUid = UUID.randomUUID();
        CreateWalletRequest request = new CreateWalletRequest(userUid, UUID.randomUUID(), "main");
        when(walletsRepository.existsByNameAndUserUid("main", userUid)).thenReturn(true);

        assertThatThrownBy(() -> walletService.createWallet(request))
                .isInstanceOf(WalletAlreadyExistsException.class);
        verify(walletsRepository, never()).save(any());
    }

    @Test
    void rejectsInactiveWalletType() {
        CreateWalletRequest request = new CreateWalletRequest(UUID.randomUUID(), UUID.randomUUID(), "main");
        when(walletTypesRepository.findById(request.getWalletTypeUid()))
                .thenReturn(Optional.of(new WalletTypesEntity().setUid(request.getWalletTypeUid()).setStatus("ARCHIVED")));

        assertThatThrownBy(() -> walletService.createWallet(request)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void createsActiveWalletWithZeroBalance() {
        CreateWalletRequest request = new CreateWalletRequest(UUID.randomUUID(), UUID.randomUUID(), "main");
        WalletTypesEntity type = new WalletTypesEntity().setUid(request.getWalletTypeUid()).setStatus("ACTIVE");
        when(walletTypesRepository.findById(request.getWalletTypeUid())).thenReturn(Optional.of(type));
        when(walletsRepository.save(any())).thenAnswer(invocation -> {
            WalletEntity wallet = invocation.getArgument(0);
            wallet.setUid(UUID.randomUUID()).setCreatedAt(Instant.now());
            return wallet;
        });
        when(walletMapper.toResponse(any(), any())).thenAnswer(invocation -> {
            WalletEntity wallet = invocation.getArgument(0);
            return new ru.transaction.service.generated.dto.WalletResponse(wallet.getUid(), wallet.getUserUid(),
                    wallet.getWalletTypeUid(), wallet.getName(),
                    ru.transaction.service.generated.dto.WalletStatus.ACTIVE, wallet.getBalance(),
                    wallet.getCreatedAt().atOffset(java.time.ZoneOffset.UTC));
        });

        var response = walletService.createWallet(request);

        assertThat(response.getBalance()).isZero();
        assertThat(response.getStatus().name()).isEqualTo("ACTIVE");
    }
}
