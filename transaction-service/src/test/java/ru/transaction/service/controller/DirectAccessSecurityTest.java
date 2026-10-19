package ru.transaction.service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.transaction.service.config.SecurityConfig;
import ru.transaction.service.generated.configuration.EnumConverterConfiguration;
import ru.transaction.service.generated.dto.TransactionPageResponse;
import ru.transaction.service.generated.dto.TransactionStatus;
import ru.transaction.service.generated.dto.TransactionStatusResponse;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.generated.dto.WalletResponse;
import ru.transaction.service.generated.dto.WalletStatus;
import ru.transaction.service.metrics.TransactionMetrics;
import ru.transaction.service.service.TransactionService;
import ru.transaction.service.service.WalletService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.transaction.service.testsupport.TestData.USD_WALLET_TYPE;

/**
 * Запросы в обход шлюза: без токена или с токеном другого пользователя чужие данные недоступны.
 */
@WebMvcTest
@Import({SecurityConfig.class, CurrentUser.class, WalletController.class, TransactionController.class,
        EnumConverterConfiguration.class})
class DirectAccessSecurityTest {

    private static final UUID CALLER = UUID.randomUUID();
    private static final UUID VICTIM = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private WalletService walletService;
    @MockitoBean
    private TransactionService transactionService;
    @MockitoBean
    private TransactionMetrics metrics;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/wallets/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/transactions/withdrawal/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(withdrawal(VICTIM, UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(walletService, transactionService);
    }

    @Test
    void foreignWalletCannotBeRead() throws Exception {
        UUID walletUid = UUID.randomUUID();
        when(walletService.getByUid(walletUid)).thenReturn(wallet(walletUid, VICTIM));

        mockMvc.perform(get("/wallets/{id}", walletUid).with(caller())).andExpect(status().isForbidden());
        mockMvc.perform(get("/wallets/user/{userUid}", VICTIM).with(caller())).andExpect(status().isForbidden());
    }

    @Test
    void ownWalletCanBeRead() throws Exception {
        UUID walletUid = UUID.randomUUID();
        when(walletService.getByUid(walletUid)).thenReturn(wallet(walletUid, CALLER));

        mockMvc.perform(get("/wallets/{id}", walletUid).with(caller())).andExpect(status().isOk());
    }

    @Test
    void operationsOnBehalfOfAnotherUserAreForbidden() throws Exception {
        String foreign = withdrawal(VICTIM, UUID.randomUUID());

        mockMvc.perform(post("/transactions/withdrawal/confirm").with(caller())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(foreign))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/transactions/withdrawal/init").with(caller())
                        .contentType(MediaType.APPLICATION_JSON).content(foreign))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/wallets").with(caller()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userUid\":\"%s\",\"walletTypeUid\":\"%s\",\"name\":\"stolen\"}"
                                .formatted(VICTIM, USD_WALLET_TYPE)))
                .andExpect(status().isForbidden());
        verifyNoInteractions(transactionService, walletService, metrics);
    }

    @Test
    void foreignTransactionsCannotBeRead() throws Exception {
        UUID transactionId = UUID.randomUUID();
        when(transactionService.getStatus(transactionId)).thenReturn(new TransactionStatusResponse(transactionId,
                VICTIM, UUID.randomUUID(), TransactionType.WITHDRAWAL, BigDecimal.TEN, TransactionStatus.PENDING,
                OffsetDateTime.now()));

        mockMvc.perform(get("/transactions/{id}/status", transactionId).with(caller()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/transactions").param("userUid", VICTIM.toString()).with(caller()))
                .andExpect(status().isForbidden());
    }

    @Test
    void searchWithoutUserIsLimitedToCaller() throws Exception {
        when(transactionService.find(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new TransactionPageResponse(List.of(), 0, 20, 0L, 0));

        mockMvc.perform(get("/transactions").with(caller())).andExpect(status().isOk());

        verify(transactionService).find(eq(CALLER), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));
    }

    private static RequestPostProcessor caller() {
        return jwt().jwt(token -> token.subject(CALLER.toString()));
    }

    private static String withdrawal(UUID userUid, UUID walletUid) {
        return """
                {"userUid":"%s","walletUid":"%s","amount":10.00,"currency":"USD","paymentMethodId":1}
                """.formatted(userUid, walletUid);
    }

    private static WalletResponse wallet(UUID walletUid, UUID owner) {
        return new WalletResponse(walletUid, owner, USD_WALLET_TYPE, "main", WalletStatus.ACTIVE,
                BigDecimal.ZERO, OffsetDateTime.now());
    }
}
