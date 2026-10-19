package ru.individuals.api.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import ru.individuals.api.client.transaction.generated.model.TransactionType;

@Component
public class TransactionTypeConverter implements Converter<String, TransactionType> {

    @Override
    public TransactionType convert(String source) {
        return TransactionType.fromValue(source.toLowerCase());
    }
}
