package be.fjodortypes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;

/**
 * Checks that an interaction really comes from Discord: every request is signed with Ed25519 over
 * timestamp + body, using the app's key pair. Discord refuses to save the endpoint URL unless this works.
 */
public final class Signature {
    /** What Java needs in front of a raw 32-byte Ed25519 public key (the X.509 header). */
    private static final byte[] X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private final PublicKey key;

    /** @param hexKey the 64-character "Public Key" from the developer portal */
    public Signature(String hexKey) {
        try {
            byte[] raw = HexFormat.of().parseHex(hexKey);
            byte[] spec = new byte[X509_PREFIX.length + raw.length];
            System.arraycopy(X509_PREFIX, 0, spec, 0, X509_PREFIX.length);
            System.arraycopy(raw, 0, spec, X509_PREFIX.length, raw.length);
            key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spec));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("discord.public-key isn't a valid public key", e);
        }
    }

    public boolean valid(String signatureHex, String timestamp, byte[] body) {
        if (signatureHex == null || timestamp == null || !signatureHex.matches("[0-9a-fA-F]{128}")) {
            return false;
        }
        try {
            java.security.Signature v = java.security.Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(timestamp.getBytes(StandardCharsets.UTF_8));
            v.update(body);
            return v.verify(HexFormat.of().parseHex(signatureHex));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
