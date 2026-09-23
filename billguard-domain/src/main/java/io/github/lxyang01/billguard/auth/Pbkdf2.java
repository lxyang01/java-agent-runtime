package io.github.lxyang01.billguard.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2-HMAC-SHA256,200k 迭代(参数)。 */
public final class Pbkdf2 {

    public static final int ITERATIONS = 200_000;

    /** 未知用户名的等代价哈希盐,用于抹平 verify 的计时差(逐字对齐 )。 */
    public static final byte[] DUMMY_SALT = "billguard-timing-equalizer"
        .getBytes(StandardCharsets.UTF_8);
    public static final String DUMMY_HASH = hex(hash("billguard-dummy", DUMMY_SALT));

    private Pbkdf2() {}

    public static byte[] hash(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /** 恒时比较(对齐 hmac.compare_digest)。 */
    public static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
