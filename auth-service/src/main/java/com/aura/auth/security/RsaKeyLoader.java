package com.aura.auth.security;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * Parses RSA keys supplied either as armoured PEM or as bare single-line base64 DER.
 *
 * <p>Both forms are accepted because both turn up in practice: a PEM file pasted into a config
 * value, and the single-line base64 that {@code docker/generate-signing-key.sh} emits for {@code
 * .env}. Literal {@code \n} escape sequences are stripped too — a {@code .env} file cannot hold a
 * real newline, so people write the escape, and nothing downstream unescapes it.
 *
 * <p>Hand-rolled rather than pulled from a library so there is no ambiguity about which parsing
 * helper is on the classpath, and no surprise when a transitive dependency moves.
 */
final class RsaKeyLoader {

    private RsaKeyLoader() {
    }

    static RSAPrivateKey privateKey(String pem) {
        byte[] der = decode(pem, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                "aura.auth.token.rsa.private-key-pem is not a valid PKCS#8 RSA private key", e);
        }
    }

    static RSAPublicKey publicKey(String pem) {
        byte[] der = decode(pem, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(
                "aura.auth.token.rsa.public-key-pem is not a valid X.509 RSA public key", e);
        }
    }

    private static byte[] decode(String pem, String label) {
        String base64 = pem
            .replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replace("-----BEGIN RSA " + label + "-----", "")
            .replace("-----END RSA " + label + "-----", "")
            .replace("\\n", "")
            .replace("\\r", "")
            .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("RSA key PEM is not valid base64", e);
        }
    }
}
