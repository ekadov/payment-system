package ru.individuals.api.config;

import feign.Retryer;
import feign.micrometer.MicrometerObservationCapability;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.individuals.api.client.transaction.generated.api.TransactionsApi;
import ru.individuals.api.client.transaction.generated.api.WalletsApi;
import ru.individuals.api.client.transaction.generated.invoker.ApiClient;

@Configuration
public class TransactionClientConfig {

    @Bean
    public ApiClient transactionApiClient(@Value("${transaction-service.base-url}") String baseUrl,
                                          ObservationRegistry observationRegistry) {
        ApiClient apiClient = new ApiClient().setBasePath(baseUrl);

        apiClient.getFeignBuilder()
                .client(new feign.okhttp.OkHttpClient(new okhttp3.OkHttpClient.Builder()
                        .retryOnConnectionFailure(false)
                        .build()))
                .retryer(Retryer.NEVER_RETRY)
                .requestInterceptor(TransactionServiceTokenRelay.interceptor())
                .addCapability(new MicrometerObservationCapability(observationRegistry));
        return apiClient;
    }

    @Bean
    public TransactionsApi transactionsApi(ApiClient transactionApiClient) {
        return transactionApiClient.buildClient(TransactionsApi.class);
    }

    @Bean
    public WalletsApi walletsApi(ApiClient transactionApiClient) {
        return transactionApiClient.buildClient(WalletsApi.class);
    }
}
