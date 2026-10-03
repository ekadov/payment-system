package ru.transaction.service.controller;

import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.transaction.service.generated.api.WalletsApiDelegate;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.generated.dto.WalletResponse;
import ru.transaction.service.service.WalletService;

import java.util.List;
import java.util.UUID;

@Service
@AllArgsConstructor
public class WalletController implements WalletsApiDelegate {

    private final WalletService walletService;

    @Override
    public ResponseEntity<WalletResponse> createWallet(CreateWalletRequest createWalletRequest) {
        var response = walletService.createWallet(createWalletRequest);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.getUid())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Override
    public ResponseEntity<List<WalletResponse>> getUserWallets(UUID userUid) {
        return ResponseEntity.ok(walletService.getByUserUid(userUid));
    }

    @Override
    public ResponseEntity<WalletResponse> getWallet(UUID walletUid) {
        return ResponseEntity.ok(walletService.getByUid(walletUid));
    }
}
