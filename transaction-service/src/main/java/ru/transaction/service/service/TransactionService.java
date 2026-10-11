package ru.transaction.service.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.transaction.service.config.TransactionKafkaProperties;
import ru.transaction.service.entity.*;
import ru.transaction.service.entity.WalletStatus;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.ConflictException;
import ru.transaction.service.exception.NotFoundException;
import ru.transaction.service.generated.dto.*;
import ru.transaction.service.messaging.event.DepositRequested;
import ru.transaction.service.messaging.event.WithdrawalRequested;
import ru.transaction.service.metrics.TransactionMetrics;
import ru.transaction.service.repository.TransactionsRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
@AllArgsConstructor
@Slf4j
public class TransactionService {

    private static final BigDecimal DEPOSIT_FEE_RATE = new BigDecimal("0.01");
    private static final BigDecimal WITHDRAWAL_FEE_RATE = new BigDecimal("0.01");
    private static final BigDecimal TRANSFER_FEE_RATE = new BigDecimal("0.005");
    private static final int AMOUNT_SCALE = 2;
    private static final int AMOUNT_INTEGER_DIGITS = 13;

    private final TransactionsRepository transactionsRepository;
    private final WalletService walletService;
    private final OutboxService outboxService;
    private final TransactionKafkaProperties kafkaProperties;
    private final TransactionMetrics metrics;

    @Transactional(readOnly = true)
    public TransactionInitResponse init(TransactionType type, TransactionRequest request) {
        TransactionCommand command = fromRequest(type, request);
        WalletEntity source = requireUsableSource(command, false);
        if (type == TransactionType.TRANSFER) {
            requireUsableTarget(command, source);
        }
        BigDecimal fee = calculateFee(type, command.amount());
        BigDecimal total = command.amount().add(fee);
        boolean available = type == TransactionType.DEPOSIT || source.getBalance().compareTo(total) >= 0;
        metrics.initialized(type, available);
        log.info("Transaction initialized: type={}, walletId={}, amount={}, fee={}, available={}",
                type, command.walletUid(), command.amount(), fee, available);
        return new TransactionInitResponse(type, command.amount(), fee, total, available)
                .failureReason(available ? null : "INSUFFICIENT_FUNDS");
    }

    @Transactional
    public TransactionConfirmResponse confirm(TransactionType type, TransactionRequest request) {
        TransactionCommand command = fromRequest(type, request);
        TransactionsEntity transaction = switch (type) {
            case DEPOSIT -> confirmDeposit(command);
            case WITHDRAWAL -> confirmWithdrawal(command);
            case TRANSFER -> confirmTransfer(command);
        };
        metrics.confirmed(type, transaction.getStatus());
        log.info("Transaction confirmed: transactionId={}, type={}, status={}, amount={}, fee={}",
                transaction.getUid(), type, transaction.getStatus(), transaction.getAmount(), transaction.getFee());
        return toConfirmResponse(transaction);
    }

    @Transactional(readOnly = true)
    public TransactionStatusResponse getStatus(UUID transactionId) {
        return toStatusResponse(getTransaction(transactionId));
    }

    @Transactional(readOnly = true)
    public TransactionPageResponse find(UUID userUid, UUID walletUid, TransactionType type,
                                        TransactionStatus status, OffsetDateTime dateFrom,
                                        OffsetDateTime dateTo, int page, int size) {
        Specification<TransactionsEntity> specification = (root, query, builder) -> builder.conjunction();
        if (userUid != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("userUid"), userUid));
        }
        if (walletUid != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("walletUid"), walletUid));
        }
        if (type != null) {
            PaymentTypeEntity entityType = PaymentTypeEntity.valueOf(type.name());
            specification = specification.and((root, query, builder) -> builder.equal(root.get("type"), entityType));
        }
        if (status != null) {
            specification = specification.and((root, query, builder) -> builder.equal(root.get("status"), status.name()));
        }
        if (dateFrom != null) {
            Instant from = dateFrom.toInstant();
            specification = specification.and((root, query, builder) -> builder.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (dateTo != null) {
            Instant to = dateTo.toInstant();
            specification = specification.and((root, query, builder) -> builder.lessThanOrEqualTo(root.get("createdAt"), to));
        }
        Page<TransactionsEntity> result = transactionsRepository.findAll(specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new TransactionPageResponse(result.getContent().stream().map(this::toStatusResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /**
     * Финализация пополнения по сообщению провайдера. Повторная доставка уже обработанного
     * сообщения игнорируется, поэтому обработка идемпотентна.
     */
    @Transactional
    public void finalizeDeposit(UUID transactionId, String status, BigDecimal completedAmount, String failureReason) {
        Optional<TransactionsEntity> pending = lockPendingTransaction(transactionId, PaymentTypeEntity.DEPOSIT);
        if (pending.isEmpty()) {
            return;
        }
        TransactionsEntity transaction = pending.get();
        TransactionStatusEntity result = parseFinalStatus(status);
        if (result == TransactionStatusEntity.COMPLETED) {
            if (completedAmount != null && completedAmount.compareTo(transaction.getAmount()) != 0) {
                // Зачисляем только подтверждённую при confirm сумму
                log.error("Deposit amount mismatch: transactionId={}, expected={}, received={}",
                        transactionId, transaction.getAmount(), completedAmount);
                transaction.setStatus(TransactionStatusEntity.FAILED.name()).setFailureReason("AMOUNT_MISMATCH");
            } else {
                WalletEntity wallet = walletService.getWalletEntityForUpdate(transaction.getWalletUid());
                wallet.setBalance(wallet.getBalance().add(transaction.getAmount()));
                transaction.setStatus(TransactionStatusEntity.COMPLETED.name()).setFailureReason(null);
            }
        } else {
            transaction.setStatus(TransactionStatusEntity.FAILED.name())
                    .setFailureReason(failureReason == null ? "DEPOSIT_PROVIDER_FAILED" : failureReason);
        }
        transactionsRepository.save(transaction);
        metrics.finalized(PaymentTypeEntity.DEPOSIT, transaction.getStatus(), transaction.getCreatedAt());
        log.info("Deposit finalized: transactionId={}, status={}, failureReason={}",
                transactionId, transaction.getStatus(), transaction.getFailureReason());
    }

    @Transactional
    public void finalizeWithdrawal(UUID transactionId, String status, String failureReason) {
        Optional<TransactionsEntity> pending = lockPendingTransaction(transactionId, PaymentTypeEntity.WITHDRAWAL);
        if (pending.isEmpty()) {
            return;
        }
        TransactionsEntity transaction = pending.get();
        if (parseFinalStatus(status) == TransactionStatusEntity.COMPLETED) {
            transaction.setStatus(TransactionStatusEntity.COMPLETED.name()).setFailureReason(null);
        } else {
            WalletEntity wallet = walletService.getWalletEntityForUpdate(transaction.getWalletUid());
            wallet.setBalance(wallet.getBalance().add(transaction.getAmount()).add(feeOf(transaction)));
            transaction.setStatus(TransactionStatusEntity.FAILED.name())
                    .setFailureReason(failureReason == null ? "WITHDRAWAL_PROVIDER_FAILED" : failureReason);
        }
        transactionsRepository.save(transaction);
        metrics.finalized(PaymentTypeEntity.WITHDRAWAL, transaction.getStatus(), transaction.getCreatedAt());
        log.info("Withdrawal finalized: transactionId={}, status={}, failureReason={}",
                transactionId, transaction.getStatus(), transaction.getFailureReason());
    }

    private TransactionsEntity confirmDeposit(TransactionCommand command) {
        WalletEntity wallet = requireUsableSource(command, false);
        BigDecimal fee = calculateFee(TransactionType.DEPOSIT, command.amount());
        TransactionsEntity transaction = saveTransaction(command, TransactionType.DEPOSIT,
                TransactionStatusEntity.PENDING, fee);
        // Провайдер списывает с карты amount + fee, на кошелёк зачисляется amount.
        DepositRequested event = new DepositRequested();
        event.setTransactionId(transaction.getUid());
        event.setUserId(command.userUid());
        event.setWalletId(wallet.getUid());
        event.setAmount(command.amount());
        event.setFee(fee);
        event.setTotalAmount(command.amount().add(fee));
        event.setCurrency(command.currency());
        event.setPaymentMethodId(command.paymentMethodId());
        event.setTimestamp(Instant.now());
        outboxService.enqueue(kafkaProperties.topics().depositRequested(), transaction.getUid().toString(),
                command.userUid(), event);
        return transaction;
    }

    private TransactionsEntity confirmWithdrawal(TransactionCommand command) {
        WalletEntity wallet = requireUsableSource(command, true);
        BigDecimal fee = calculateFee(TransactionType.WITHDRAWAL, command.amount());
        // Средства (сумма + комиссия) блокируются списанием сразу; при WithdrawalFailed они возвращаются.
        debit(wallet, command.amount().add(fee));
        TransactionsEntity transaction = saveTransaction(command, TransactionType.WITHDRAWAL,
                TransactionStatusEntity.PENDING, fee);
        WithdrawalRequested event = new WithdrawalRequested();
        event.setTransactionId(transaction.getUid());
        event.setUserId(command.userUid());
        event.setWalletId(wallet.getUid());
        event.setAmount(command.amount());
        event.setCurrency(command.currency());
        event.setPaymentMethodId(command.paymentMethodId());
        event.setDestination(command.destination());
        event.setTimestamp(Instant.now());
        outboxService.enqueue(kafkaProperties.topics().withdrawalRequested(), transaction.getUid().toString(),
                command.userUid(), event);
        return transaction;
    }

    /**
     * Кошельки могут находиться на разных шардах
     */
    private TransactionsEntity confirmTransfer(TransactionCommand command) {
        // Блокировки берутся в порядке UUID, чтобы встречные переводы не приводили к дедлоку.
        UUID firstId = command.walletUid().compareTo(command.targetWalletUid()) < 0
                ? command.walletUid() : command.targetWalletUid();
        UUID secondId = firstId.equals(command.walletUid()) ? command.targetWalletUid() : command.walletUid();
        WalletEntity first = walletService.getWalletEntityForUpdate(firstId);
        WalletEntity second = walletService.getWalletEntityForUpdate(secondId);
        WalletEntity source = first.getUid().equals(command.walletUid()) ? first : second;
        WalletEntity target = first.getUid().equals(command.targetWalletUid()) ? first : second;
        validateSource(source, command);
        validateTarget(source, target);
        BigDecimal fee = calculateFee(TransactionType.TRANSFER, command.amount());
        debit(source, command.amount().add(fee));
        target.setBalance(target.getBalance().add(command.amount()));
        return saveTransaction(command, TransactionType.TRANSFER, TransactionStatusEntity.COMPLETED, fee);
    }

    private TransactionsEntity saveTransaction(TransactionCommand command, TransactionType type,
                                               TransactionStatusEntity status, BigDecimal fee) {
        TransactionsEntity entity = new TransactionsEntity()
                .setUserUid(command.userUid())
                .setWalletUid(command.walletUid())
                .setTargetWalletUid(command.targetWalletUid())
                .setAmount(command.amount())
                .setType(PaymentTypeEntity.valueOf(type.name()))
                .setStatus(status.name())
                .setComment(command.comment())
                .setPaymentMethodId(command.paymentMethodId())
                .setFee(fee);
        return transactionsRepository.saveAndFlush(entity);
    }

    private WalletEntity requireUsableSource(TransactionCommand command, boolean lock) {
        WalletEntity wallet = lock ? walletService.getWalletEntityForUpdate(command.walletUid())
                : walletService.getWalletEntity(command.walletUid());
        validateSource(wallet, command);
        return wallet;
    }

    private void requireUsableTarget(TransactionCommand command, WalletEntity source) {
        WalletEntity target = walletService.getWalletEntity(command.targetWalletUid());
        validateTarget(source, target);
    }

    private void validateSource(WalletEntity wallet, TransactionCommand command) {
        if (!wallet.getUserUid().equals(command.userUid())) {
            throw new BadRequestException("Wallet does not belong to the user");
        }
        requireActive(wallet);
        if (command.currency() != null) {
            requireCurrency(wallet, command.currency());
        }
    }

    private void validateTarget(WalletEntity source, WalletEntity target) {
        requireActive(target);
        WalletTypesEntity sourceType = walletService.getWalletType(source);
        WalletTypesEntity targetType = walletService.getWalletType(target);
        if (!sourceType.getCurrencyCode().equalsIgnoreCase(targetType.getCurrencyCode())) {
            throw new BadRequestException("Wallet currencies do not match");
        }
    }

    private void requireActive(WalletEntity wallet) {
        if (!WalletStatus.ACTIVE.name().equals(wallet.getStatus())) {
            throw new ConflictException("Wallet is not active: " + wallet.getUid());
        }
    }

    private void requireCurrency(WalletEntity wallet, String currency) {
        String actualCurrency = walletService.getWalletType(wallet).getCurrencyCode();
        if (!actualCurrency.equalsIgnoreCase(currency)) {
            throw new BadRequestException("Wallet currency is %s, not %s".formatted(actualCurrency, currency));
        }
    }

    private void debit(WalletEntity wallet, BigDecimal amount) {
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new ConflictException("Insufficient funds");
        }
        wallet.setBalance(wallet.getBalance().subtract(amount));
    }

    private BigDecimal calculateFee(TransactionType type, BigDecimal amount) {
        BigDecimal rate = switch (type) {
            case DEPOSIT -> DEPOSIT_FEE_RATE;
            case WITHDRAWAL -> WITHDRAWAL_FEE_RATE;
            case TRANSFER -> TRANSFER_FEE_RATE;
        };
        return amount.multiply(rate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal feeOf(TransactionsEntity transaction) {
        return transaction.getFee() == null ? BigDecimal.ZERO : transaction.getFee();
    }

    private TransactionsEntity getTransaction(UUID transactionId) {
        return transactionsRepository.findById(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + transactionId));
    }

    /**
     * Возвращает PENDING-транзакцию под блокировкой или пусто, если она уже финализирована
     * (повторная доставка сообщения).
     */
    private Optional<TransactionsEntity> lockPendingTransaction(UUID transactionId, PaymentTypeEntity expectedType) {
        TransactionsEntity transaction = transactionsRepository.findByUidForUpdate(transactionId)
                .orElseThrow(() -> new NotFoundException("Transaction not found: " + transactionId));
        if (transaction.getType() != expectedType) {
            throw new BadRequestException("Unexpected transaction type %s for %s"
                    .formatted(transaction.getType(), transactionId));
        }
        if (!TransactionStatusEntity.PENDING.name().equals(transaction.getStatus())) {
            log.info("Duplicate finalization ignored: transactionId={}, currentStatus={}",
                    transactionId, transaction.getStatus());
            return Optional.empty();
        }
        return Optional.of(transaction);
    }

    private TransactionStatusEntity parseFinalStatus(String status) {
        if (status != null) {
            String normalized = status.trim().toUpperCase(Locale.ROOT);
            if (TransactionStatusEntity.COMPLETED.name().equals(normalized)) {
                return TransactionStatusEntity.COMPLETED;
            }
            if (TransactionStatusEntity.FAILED.name().equals(normalized)) {
                return TransactionStatusEntity.FAILED;
            }
        }
        throw new BadRequestException("Unsupported final status: " + status);
    }

    private TransactionConfirmResponse toConfirmResponse(TransactionsEntity entity) {
        return new TransactionConfirmResponse(entity.getUid(), TransactionType.valueOf(entity.getType().name()),
                TransactionStatus.valueOf(entity.getStatus()), entity.getAmount(), toOffset(entity.getCreatedAt()));
    }

    private TransactionStatusResponse toStatusResponse(TransactionsEntity entity) {
        return new TransactionStatusResponse(entity.getUid(), entity.getUserUid(), entity.getWalletUid(),
                TransactionType.valueOf(entity.getType().name()), entity.getAmount(),
                TransactionStatus.valueOf(entity.getStatus()), toOffset(entity.getCreatedAt()))
                .targetWalletUid(entity.getTargetWalletUid())
                .fee(entity.getFee())
                .comment(entity.getComment())
                .failureReason(entity.getFailureReason())
                .modifiedAt(entity.getModifiedAt() == null ? null : toOffset(entity.getModifiedAt()));
    }

    private OffsetDateTime toOffset(Instant instant) {
        return instant == null ? OffsetDateTime.now(ZoneOffset.UTC) : instant.atOffset(ZoneOffset.UTC);
    }

    private TransactionCommand fromRequest(TransactionType type, TransactionRequest request) {
        if (request.getUserUid() == null || request.getWalletUid() == null) {
            throw new BadRequestException("userUid and walletUid are required");
        }
        if (type == TransactionType.TRANSFER) {
            if (request.getTargetWalletUid() == null) {
                throw new BadRequestException("targetWalletUid is required for transfer");
            }
            if (request.getWalletUid().equals(request.getTargetWalletUid())) {
                throw new BadRequestException("Source and target wallets must be different");
            }
        } else if (request.getCurrency() == null || request.getPaymentMethodId() == null) {
            throw new BadRequestException("currency and paymentMethodId are required");
        }
        return new TransactionCommand(request.getUserUid(), request.getWalletUid(),
                type == TransactionType.TRANSFER ? request.getTargetWalletUid() : null,
                normalizeAmount(request.getAmount()), request.getCurrency(), request.getPaymentMethodId(),
                request.getDestination(), request.getComment());
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Amount must be greater than zero");
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > AMOUNT_SCALE) {
            throw new BadRequestException("Amount must have at most %d decimal places".formatted(AMOUNT_SCALE));
        }
        if (stripped.precision() - stripped.scale() > AMOUNT_INTEGER_DIGITS) {
            throw new BadRequestException("Amount is too large");
        }
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    private record TransactionCommand(UUID userUid, UUID walletUid, UUID targetWalletUid, BigDecimal amount,
                                      String currency, Long paymentMethodId, String destination, String comment) {
    }
}
