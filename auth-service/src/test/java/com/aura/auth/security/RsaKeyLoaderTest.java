package com.aura.auth.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These cover the formats the key actually arrives in, which is where this breaks in practice:
 * a {@code .env} value cannot contain a real newline, so operators write armoured PEM with literal
 * {@code \n} escapes, and nothing in the Spring property chain unescapes them.
 */
class RsaKeyLoaderTest {

    private static KeyPair keyPair;
    private static String privateBase64;
    private static String publicBase64;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        privateBase64 = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        publicBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    void readsBareSingleLineBase64() {
        RSAPrivateKey privateKey = RsaKeyLoader.privateKey(privateBase64);
        RSAPublicKey publicKey = RsaKeyLoader.publicKey(publicBase64);

        assertThat(privateKey.getEncoded()).isEqualTo(keyPair.getPrivate().getEncoded());
        assertThat(publicKey.getEncoded()).isEqualTo(keyPair.getPublic().getEncoded());
    }

    @Test
    void readsArmouredPemWithRealNewlines() {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + wrap(privateBase64)
            + "\n-----END PRIVATE KEY-----\n";

        assertThat(RsaKeyLoader.privateKey(pem).getEncoded())
            .isEqualTo(keyPair.getPrivate().getEncoded());
    }

    @Test
    void readsPemWhoseNewlinesSurvivedAsLiteralBackslashN() {
        String pem = "-----BEGIN PUBLIC KEY-----\\n" + publicBase64 + "\\n-----END PUBLIC KEY-----";

        assertThat(RsaKeyLoader.publicKey(pem).getEncoded())
            .isEqualTo(keyPair.getPublic().getEncoded());
    }

    @Test
    void rejectsGarbageWithAPointedMessage() {
        assertThatThrownBy(() -> RsaKeyLoader.privateKey("not a key"))
            .isInstanceOf(IllegalStateException.class);
    }

    /** Mimics openssl's 64-column PEM wrapping. */
    private static String wrap(String base64) {
        StringBuilder wrapped = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            if (i > 0) {
                wrapped.append('\n');
            }
            wrapped.append(base64, i, Math.min(i + 64, base64.length()));
        }
        return wrapped.toString();
    }
}
