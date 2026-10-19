package ru.individuals.api.controller;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.individuals.api.client.transaction.generated.api.TransactionsApi;
import ru.individuals.api.client.transaction.generated.api.WalletsApi;
import ru.individuals.api.client.transaction.generated.model.CreateWalletRequest;
import ru.individuals.api.client.transaction.generated.model.TransactionRequest;
import ru.individuals.api.client.transaction.generated.model.TransactionType;
import ru.individuals.api.client.transaction.generated.model.WalletResponse;
import ru.individuals.api.client.transaction.generated.model.WalletStatus;
import ru.individuals.api.config.SecurityConfig;
import ru.individuals.api.config.TransactionTypeConverter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = TransactionProxyController.class)
@Import({SecurityConfig.class, TransactionTypeConverter.class})
class TransactionProxyControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER_USER = UUID.randomUUID();
    private static final UUID WALLET_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private TransactionsApi transactionsApi;

    @MockitoBean
    private WalletsApi walletsApi;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Test
    void createWalletShouldProxyRequestForCurrentUser() {
        when(walletsApi.createWallet(any(CreateWalletRequest.class))).thenReturn(wallet(USER));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest().userUid(USER).walletTypeUid(WALLET_TYPE).name("main"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.userUid").isEqualTo(USER.toString());

        verify(walletsApi).createWallet(any(CreateWalletRequest.class));
    }

    @Test
    void createWalletShouldForbidAnotherUser() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .post()
                .uri("/v1/wallets")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWalletRequest().userUid(OTHER_USER).walletTypeUid(WALLET_TYPE).name("main"))
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(walletsApi);
    }

    @Test
    void requestWithoutTokenShouldBeUnauthorized() {
        webTestClient.get()
                .uri("/v1/wallets/user/{userUid}", USER)
                .exchange()
                .expectStatus().isUnauthorized();

        verifyNoInteractions(walletsApi);
    }

    @Test
    void getWalletShouldForbidForeignWallet() {
        UUID walletUid = UUID.randomUUID();
        when(walletsApi.getWallet(walletUid)).thenReturn(wallet(OTHER_USER));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .get()
                .uri("/v1/wallets/{walletUid}", walletUid)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void confirmShouldConvertLowercaseTypeAndProxyRequestWithClientIdempotencyKey() {
        UUID idempotencyKey = UUID.randomUUID();
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .post()
                .uri("/v1/transactions/deposit/confirm")
                .header("Idempotency-Key", idempotencyKey.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransactionRequest().userUid(USER).walletUid(UUID.randomUUID())
                        .amount(new BigDecimal("100")).currency("USD").paymentMethodId(1L))
                .exchange()
                .expectStatus().isOk();

        verify(transactionsApi).confirmTransaction(eq(TransactionType.DEPOSIT), eq(idempotencyKey),
                any(TransactionRequest.class));
    }

    @Test
    void confirmWithoutIdempotencyKeyShouldBeRejectedBeforeProxying() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .post()
                .uri("/v1/transactions/deposit/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransactionRequest().userUid(USER).walletUid(UUID.randomUUID())
                        .amount(new BigDecimal("100")).currency("USD").paymentMethodId(1L))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(transactionsApi);
    }

    @Test
    void findShouldAlwaysUseUserFromToken() {
        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .get()
                .uri("/v1/transactions?page=0&size=10")
                .exchange()
                .expectStatus().isOk();

        verify(transactionsApi).findTransactions(eq(USER), any(), any(), any(), any(), any(), eq(0), eq(10));
    }

    @Test
    void downstreamErrorShouldBeReturnedWithSameStatus() {
        UUID walletUid = UUID.randomUUID();
        Request request = Request.create(Request.HttpMethod.GET, "/wallets/" + walletUid, Map.of(), null,
                StandardCharsets.UTF_8, null);
        byte[] body = "{\"title\":\"Not Found\",\"status\":404,\"detail\":\"Wallet not found\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(walletsApi.getWallet(walletUid))
                .thenThrow(new FeignException.NotFound("Not Found", request, body, Map.of()));

        webTestClient.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER.toString())))
                .get()
                .uri("/v1/wallets/{walletUid}", walletUid)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Wallet not found");
    }

    private static WalletResponse wallet(UUID userUid) {
        return new WalletResponse()
                .uid(UUID.randomUUID())
                .userUid(userUid)
                .walletTypeUid(WALLET_TYPE)
                .name("main")
                .status(WalletStatus.ACTIVE)
                .balance(BigDecimal.ZERO)
                .createdAt(OffsetDateTime.now());
    }
}
