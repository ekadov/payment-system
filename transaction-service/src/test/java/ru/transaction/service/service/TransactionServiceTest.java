package ru.transaction.service.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.transaction.service.config.TransactionKafkaProperties;
import ru.transaction.service.entity.PaymentTypeEntity;
import ru.transaction.service.entity.TransactionsEntity;
import ru.transaction.service.entity.WalletEntity;
import ru.transaction.service.entity.WalletTypesEntity;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.ConflictException;
import ru.transaction.service.generated.dto.TransactionRequest;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.messaging.event.DepositRequested;
import ru.transaction.service.messaging.event.WithdrawalRequested;
import ru.transaction.service.metrics.TransactionMetrics;
import ru.transaction.service.repository.TransactionsRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final TransactionKafkaProperties KAFKA_PROPERTIES = new TransactionKafkaProperties(
            new TransactionKafkaProperties.Topics("deposit-requested", "deposit-completed",
                    "withdrawal-requested", "withdrawal-completed", "withdrawal-failed"),
            1, new TransactionKafkaProperties.Retry(1, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    private final UUID userUid = UUID.randomUUID();
    private final UUID walletUid = UUID.randomUUID();
    @Mock
    private TransactionsRepository transactionsRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private OutboxService outboxService;
    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionsRepository, walletService, outboxService,
                KAFKA_PROPERTIES, new TransactionMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void initCalculatesConditionsWithoutCreatingTransaction() {
        WalletEntity wallet = wallet(walletUid, userUid, "100.00");
        when(walletService.getWalletEntity(walletUid)).thenReturn(wallet);
        when(walletService.getWalletType(wallet)).thenReturn(walletType("USD"));

        var response = transactionService.init(TransactionType.WITHDRAWAL, withdrawal("20.00"));

        assertThat(response.getAvailable()).isTrue();
        assertThat(response.getFee()).isEqualByComparingTo("0.20");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("20.20");
        verifyNoInteractions(transactionsRepository, outboxService);
    }

    @Test
    void initReportsInsufficientFunds() {
        WalletEntity wallet = wallet(walletUid, userUid, "20.00");
        when(walletService.getWalletEntity(walletUid)).thenReturn(wallet);
        when(walletService.getWalletType(wallet)).thenReturn(walletType("USD"));

        var response = transactionService.init(TransactionType.WITHDRAWAL, withdrawal("20.00"));

        assertThat(response.getAvailable()).isFalse();
        assertThat(response.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void confirmDepositCreatesPendingTransactionAndEnqueuesEventWithoutChangingBalance() {
        WalletEntity wallet = wallet(walletUid, userUid, "5.00");
        when(walletService.getWalletEntity(walletUid)).thenReturn(wallet);
        when(walletService.getWalletType(wallet)).thenReturn(walletType("USD"));
        stubSave();

        var response = transactionService.confirm(TransactionType.DEPOSIT, deposit("100.00"));

        assertThat(response.getStatus().name()).isEqualTo("PENDING");
        assertThat(wallet.getBalance()).isEqualByComparingTo("5.00");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq("deposit-requested"), eq(response.getTransactionId().toString()),
                eq(userUid), event.capture());
        DepositRequested deposit = (DepositRequested) event.getValue();
        assertThat(deposit.getAmount()).isEqualByComparingTo("100.00");
        assertThat(deposit.getFee()).isEqualByComparingTo("1.00");
        assertThat(deposit.getTotalAmount()).isEqualByComparingTo("101.00");
    }

    @Test
    void confirmWithdrawalDebitsAmountAndFeeAndEnqueuesEvent() {
        WalletEntity wallet = wallet(walletUid, userUid, "100.00");
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(wallet);
        when(walletService.getWalletType(wallet)).thenReturn(walletType("USD"));
        stubSave();

        var response = transactionService.confirm(TransactionType.WITHDRAWAL, withdrawal("50.00"));

        assertThat(response.getStatus().name()).isEqualTo("PENDING");
        assertThat(wallet.getBalance()).isEqualByComparingTo("49.50");
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outboxService).enqueue(eq("withdrawal-requested"), eq(response.getTransactionId().toString()),
                eq(userUid), event.capture());
        assertThat(((WithdrawalRequested) event.getValue()).getDestination()).isEqualTo("Visa **** 1234");
    }

    @Test
    void confirmWithdrawalWithInsufficientFundsFailsWithoutSideEffects() {
        WalletEntity wallet = wallet(walletUid, userUid, "10.00");
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(wallet);
        when(walletService.getWalletType(wallet)).thenReturn(walletType("USD"));

        assertThatThrownBy(() -> transactionService.confirm(TransactionType.WITHDRAWAL, withdrawal("10.00")))
                .isInstanceOf(ConflictException.class);
        assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
        verify(transactionsRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void confirmTransferDebitsAndCreditsWalletsInOneCall() {
        UUID targetUid = UUID.randomUUID();
        WalletEntity source = wallet(walletUid, userUid, "100.00");
        WalletEntity target = wallet(targetUid, UUID.randomUUID(), "10.00");
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(source);
        when(walletService.getWalletEntityForUpdate(targetUid)).thenReturn(target);
        when(walletService.getWalletType(source)).thenReturn(walletType("USD"));
        when(walletService.getWalletType(target)).thenReturn(walletType("USD"));
        stubSave();

        var response = transactionService.confirm(TransactionType.TRANSFER, transfer(targetUid, "20.00"));

        assertThat(response.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(source.getBalance()).isEqualByComparingTo("79.90");
        assertThat(target.getBalance()).isEqualByComparingTo("30.00");
        verifyNoInteractions(outboxService);
    }

    @Test
    void transferBetweenDifferentCurrenciesIsRejected() {
        UUID targetUid = UUID.randomUUID();
        WalletEntity source = wallet(walletUid, userUid, "100.00");
        WalletEntity target = wallet(targetUid, UUID.randomUUID(), "0.00");
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(source);
        when(walletService.getWalletEntityForUpdate(targetUid)).thenReturn(target);
        when(walletService.getWalletType(source)).thenReturn(walletType("USD"));
        when(walletService.getWalletType(target)).thenReturn(walletType("EUR"));

        assertThatThrownBy(() -> transactionService.confirm(TransactionType.TRANSFER, transfer(targetUid, "20.00")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("currencies");
        assertThat(source.getBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void walletOfAnotherUserIsRejected() {
        when(walletService.getWalletEntity(walletUid)).thenReturn(wallet(walletUid, UUID.randomUUID(), "100.00"));

        assertThatThrownBy(() -> transactionService.init(TransactionType.DEPOSIT, deposit("10.00")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRejected() {
        assertThatThrownBy(() -> transactionService.init(TransactionType.DEPOSIT, deposit("10.001")))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(walletService);
    }

    @Test
    void completedDepositCreditsStoredAmount() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.DEPOSIT, "PENDING", "100.00", "1.00");
        WalletEntity wallet = wallet(walletUid, userUid, "0.00");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(wallet);

        transactionService.finalizeDeposit(transaction.getUid(), "COMPLETED", new BigDecimal("100.0"), null);

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(transaction.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void depositWithMismatchedAmountIsFailedWithoutCredit() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.DEPOSIT, "PENDING", "100.00", "1.00");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));

        transactionService.finalizeDeposit(transaction.getUid(), "COMPLETED", new BigDecimal("1000000"), null);

        assertThat(transaction.getStatus()).isEqualTo("FAILED");
        assertThat(transaction.getFailureReason()).isEqualTo("AMOUNT_MISMATCH");
        verify(walletService, never()).getWalletEntityForUpdate(any());
    }

    @Test
    void duplicateFinalizationIsIgnored() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.DEPOSIT, "COMPLETED", "100.00", "1.00");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));

        transactionService.finalizeDeposit(transaction.getUid(), "COMPLETED", null, null);

        verifyNoInteractions(walletService);
        verify(transactionsRepository, never()).save(any());
    }

    @Test
    void unsupportedFinalStatusIsRejected() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.WITHDRAWAL, "PENDING", "20.00", "0.20");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.finalizeWithdrawal(transaction.getUid(), "UNKNOWN", null))
                .isInstanceOf(BadRequestException.class);
        assertThat(transaction.getStatus()).isEqualTo("PENDING");
        verifyNoInteractions(walletService);
    }

    @Test
    void completedWithdrawalKeepsBalance() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.WITHDRAWAL, "PENDING", "20.00", "0.20");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));

        transactionService.finalizeWithdrawal(transaction.getUid(), "COMPLETED", null);

        assertThat(transaction.getStatus()).isEqualTo("COMPLETED");
        verifyNoInteractions(walletService);
    }

    @Test
    void failedWithdrawalRefundsAmountAndFee() {
        TransactionsEntity transaction = transaction(PaymentTypeEntity.WITHDRAWAL, "PENDING", "20.00", "0.20");
        WalletEntity wallet = wallet(walletUid, userUid, "79.80");
        when(transactionsRepository.findByUidForUpdate(transaction.getUid())).thenReturn(Optional.of(transaction));
        when(walletService.getWalletEntityForUpdate(walletUid)).thenReturn(wallet);

        transactionService.finalizeWithdrawal(transaction.getUid(), "FAILED", "PROVIDER_REJECTED");

        assertThat(wallet.getBalance()).isEqualByComparingTo("100.00");
        assertThat(transaction.getStatus()).isEqualTo("FAILED");
        assertThat(transaction.getFailureReason()).isEqualTo("PROVIDER_REJECTED");
    }

    private void stubSave() {
        when(transactionsRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            TransactionsEntity entity = invocation.getArgument(0);
            entity.setUid(UUID.randomUUID()).setCreatedAt(Instant.now());
            return entity;
        });
    }

    private TransactionRequest deposit(String amount) {
        return new TransactionRequest(userUid, walletUid, new BigDecimal(amount)).currency("USD").paymentMethodId(10L);
    }

    private TransactionRequest withdrawal(String amount) {
        return deposit(amount).destination("Visa **** 1234");
    }

    private TransactionRequest transfer(UUID targetUid, String amount) {
        return new TransactionRequest(userUid, walletUid, new BigDecimal(amount)).targetWalletUid(targetUid);
    }

    private TransactionsEntity transaction(PaymentTypeEntity type, String status, String amount, String fee) {
        return new TransactionsEntity()
                .setUid(UUID.randomUUID())
                .setWalletUid(walletUid)
                .setType(type)
                .setStatus(status)
                .setAmount(new BigDecimal(amount))
                .setFee(fee == null ? null : new BigDecimal(fee));
    }

    private WalletEntity wallet(UUID uid, UUID owner, String balance) {
        return new WalletEntity()
                .setUid(uid)
                .setUserUid(owner)
                .setWalletTypeUid(UUID.randomUUID())
                .setStatus("ACTIVE")
                .setBalance(new BigDecimal(balance));
    }

    private WalletTypesEntity walletType(String currency) {
        return new WalletTypesEntity().setUid(UUID.randomUUID()).setCurrencyCode(currency).setStatus("ACTIVE");
    }
}
