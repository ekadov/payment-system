package ru.individuals.api.config;

import feign.RetryableException;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import ru.individuals.api.client.transaction.generated.api.TransactionsApi;
import ru.individuals.api.client.transaction.generated.model.TransactionRequest;
import ru.individuals.api.client.transaction.generated.model.TransactionType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionClientConfigTest {

    /**
     * Сервис закоммитил операцию, но соединение оборвалось до ответа: клиент не должен повторять confirm.
     */
    @Test
    void confirmIsNotRetriedWhenConnectionDropsBeforeResponse() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        AtomicReference<String> received = new AtomicReference<>("");
        UUID idempotencyKey = UUID.randomUUID();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        connections.incrementAndGet();
                        byte[] buffer = new byte[8192];
                        int length = socket.getInputStream().read(buffer);
                        received.set(new String(buffer, 0, Math.max(length, 0), StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                        return;
                    }
                }
            });

            TransactionClientConfig config = new TransactionClientConfig();
            TransactionsApi api = config.transactionsApi(config.transactionApiClient(
                    "http://localhost:" + server.getLocalPort(), ObservationRegistry.NOOP));
            TransactionRequest request = new TransactionRequest()
                    .userUid(UUID.randomUUID())
                    .walletUid(UUID.randomUUID())
                    .amount(new BigDecimal("10.00"))
                    .currency("RUB")
                    .paymentMethodId(1L);

            assertThatThrownBy(() -> api.confirmTransaction(TransactionType.WITHDRAWAL, idempotencyKey, request))
                    .isInstanceOf(RetryableException.class);
            server.close();
            acceptor.join();
        }
        assertThat(connections).hasValue(1);
        assertThat(received.get()).containsIgnoringCase("Idempotency-Key: " + idempotencyKey);
    }
}
