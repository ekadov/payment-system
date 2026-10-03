package ru.transaction.service.testsupport;

import java.util.UUID;

public final class TestData {

    public static final UUID USD_WALLET_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TestData() {
    }

    public static String createWalletJson(UUID userUid, UUID walletTypeUid, String name) {
        return """
                {"userUid":"%s","walletTypeUid":"%s","name":"%s"}
                """.formatted(userUid, walletTypeUid, name);
    }

    public static String depositJson(UUID userUid, UUID walletUid, String amount) {
        return """
                {"userUid":"%s","walletUid":"%s","amount":%s,"currency":"USD","paymentMethodId":1}
                """.formatted(userUid, walletUid, amount);
    }

    public static String withdrawalJson(UUID userUid, UUID walletUid, String amount) {
        return """
                {"userUid":"%s","walletUid":"%s","amount":%s,"currency":"USD","paymentMethodId":1,
                 "destination":"Visa **** 1234"}
                """.formatted(userUid, walletUid, amount);
    }

    public static String transferJson(UUID userUid, UUID walletUid, UUID targetWalletUid, String amount) {
        return """
                {"userUid":"%s","walletUid":"%s","targetWalletUid":"%s","amount":%s}
                """.formatted(userUid, walletUid, targetWalletUid, amount);
    }
}
