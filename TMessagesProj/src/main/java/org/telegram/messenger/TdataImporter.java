/*
 * Reader for a Telegram Desktop "tdata" folder.
 *
 * Only the common case is supported: a tdata directory that is NOT protected by a local
 * passcode. Anything else (passcode protected, corrupted, or an unknown layout) fails with a
 * TdataException carrying a code the UI can turn into a readable message - never with a crash.
 *
 * Layout notes (Telegram Desktop, "modern"/multi account storage):
 *   tdata/key_datas          - plain TDF file: salt, encrypted local key, encrypted account info
 *   tdata/<16 hex chars>s    - TDF file encrypted with the local key, holds the dbiMtpAuthorization
 *                              block with main dc id, user id and the per-dc 256 byte auth keys
 * TDF envelope: "TDF$" + int32le version + payload + md5(payload, int32le size, int32le version, "TDF$")
 */
package org.telegram.messenger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class TdataImporter {

    public static final int ERROR_UNKNOWN = 0;
    public static final int ERROR_ZIP = 1;
    public static final int ERROR_NO_TDATA = 2;
    public static final int ERROR_PASSCODE = 3;
    public static final int ERROR_NO_AUTHORIZATION = 4;
    public static final int ERROR_UNSUPPORTED = 5;

    private static final int AUTH_KEY_SIZE = 256;
    private static final int LOCAL_KEY_SIZE = 256;
    private static final int DBI_MTP_AUTHORIZATION = 0x4B;
    private static final long WIDE_IDS_TAG = -1L; // ~uint64(0)
    private static final int DC_SHIFT = 10000;

    private static final long MAX_UNZIPPED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_UNZIPPED_ENTRIES = 20000;

    private static final byte[] TDF_MAGIC = new byte[] {'T', 'D', 'F', '$'};

    public static class TdataException extends Exception {
        public final int code;

        public TdataException(int code, String message) {
            super(message);
            this.code = code;
        }

        public TdataException(int code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }
    }

    /**
     * What we need to hand to ConnectionsManager.importAuthorizationKey() plus the user id that
     * Telegram Desktop had stored, purely for sanity checks. Never log authKey.
     */
    public static class TdataAccount {
        public int dcId;
        public long userId;
        public byte[] authKey;
    }

    private TdataImporter() {
    }

    // region public API

    /**
     * Extracts a zip stream into targetDir. The caller owns targetDir and should delete it once
     * the import is done (see {@link #deleteRecursively(File)}).
     */
    public static void unzip(InputStream input, File targetDir) throws TdataException {
        if (input == null || targetDir == null) {
            throw new TdataException(ERROR_ZIP, "no input");
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new TdataException(ERROR_ZIP, "cannot create target dir");
        }
        final String canonicalRoot;
        try {
            canonicalRoot = targetDir.getCanonicalPath() + File.separator;
        } catch (IOException e) {
            throw new TdataException(ERROR_ZIP, "cannot resolve target dir", e);
        }

        long total = 0;
        int entries = 0;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(input);
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > MAX_UNZIPPED_ENTRIES) {
                    throw new TdataException(ERROR_ZIP, "too many entries");
                }
                File out = new File(targetDir, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(canonicalRoot)) {
                    // zip slip - ignore anything escaping the sandbox
                    zis.closeEntry();
                    continue;
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    zis.closeEntry();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(out);
                    int read;
                    while ((read = zis.read(buffer)) > 0) {
                        total += read;
                        if (total > MAX_UNZIPPED_BYTES) {
                            throw new TdataException(ERROR_ZIP, "archive too large");
                        }
                        fos.write(buffer, 0, read);
                    }
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (TdataException e) {
            throw e;
        } catch (Exception e) {
            throw new TdataException(ERROR_ZIP, "cannot read zip", e);
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (Exception ignore) {
                }
            }
        }
        if (entries == 0) {
            throw new TdataException(ERROR_ZIP, "empty archive");
        }
    }

    /**
     * Finds a tdata folder anywhere under root and pulls out the main dc id, user id and the
     * 256 byte auth key of that dc.
     */
    public static TdataAccount readAccount(File root) throws TdataException {
        if (root == null || !root.exists()) {
            throw new TdataException(ERROR_NO_TDATA, "no root");
        }
        File keyDataFile = null;
        List<File> candidates = new ArrayList<>();
        collectKeyDataFiles(root, 0, candidates);
        if (candidates.isEmpty()) {
            throw new TdataException(ERROR_NO_TDATA, "key_data not found");
        }
        // prefer the "safe" file (key_datas), then key_data1, then key_data0
        for (String suffix : new String[] {"s", "1", "0", ""}) {
            for (File file : candidates) {
                if (file.getName().equals("key_data" + suffix)) {
                    keyDataFile = file;
                    break;
                }
            }
            if (keyDataFile != null) {
                break;
            }
        }
        if (keyDataFile == null) {
            keyDataFile = candidates.get(0);
        }

        File tdataRoot = keyDataFile.getParentFile();
        if (tdataRoot == null) {
            throw new TdataException(ERROR_NO_TDATA, "key_data has no parent");
        }

        byte[] localKey = null;
        boolean sawPasscode = false;
        // key_data may exist in several revisions - try them all, the newest one usually wins
        for (File file : candidates) {
            File parent = file.getParentFile();
            if (parent == null || !parent.equals(tdataRoot)) {
                continue;
            }
            try {
                localKey = readLocalKey(file);
            } catch (TdataException e) {
                if (e.code == ERROR_PASSCODE) {
                    sawPasscode = true;
                }
            }
            if (localKey != null) {
                break;
            }
        }
        if (localKey == null) {
            throw new TdataException(sawPasscode ? ERROR_PASSCODE : ERROR_UNSUPPORTED, "cannot read local key");
        }

        try {
            TdataAccount account = findAuthorization(tdataRoot, localKey);
            if (account == null) {
                throw new TdataException(ERROR_NO_AUTHORIZATION, "no mtp authorization found");
            }
            return account;
        } finally {
            Arrays.fill(localKey, (byte) 0);
        }
    }

    public static void deleteRecursively(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            file.delete();
        } catch (Exception ignore) {
        }
    }

    // endregion

    // region tdata parsing

    private static void collectKeyDataFiles(File dir, int depth, List<File> out) {
        if (depth > 5 || out.size() > 32) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                collectKeyDataFiles(file, depth + 1, out);
            } else if (file.getName().startsWith("key_data")) {
                out.add(file);
            }
        }
    }

    private static byte[] readLocalKey(File keyDataFile) throws TdataException {
        byte[] payload = readTdf(keyDataFile);
        if (payload == null) {
            return null;
        }
        QReader reader = new QReader(payload);
        byte[] salt;
        byte[] keyEncrypted;
        try {
            salt = reader.readByteArray();
            keyEncrypted = reader.readByteArray();
        } catch (Exception e) {
            return null;
        }
        if (salt == null || salt.length == 0 || keyEncrypted == null) {
            return null;
        }

        byte[] passcodeKey = createLocalKeyModern(salt, new byte[0]);
        byte[] decrypted = decryptLocal(keyEncrypted, passcodeKey);
        Arrays.fill(passcodeKey, (byte) 0);
        if (decrypted == null) {
            // very old tdata used PBKDF2-HMAC-SHA1 for the passcode key
            byte[] legacyKey = createLocalKeyLegacy(salt, new byte[0]);
            decrypted = decryptLocal(keyEncrypted, legacyKey);
            Arrays.fill(legacyKey, (byte) 0);
        }
        if (decrypted == null) {
            throw new TdataException(ERROR_PASSCODE, "local key is passcode protected");
        }
        if (decrypted.length < 4 + LOCAL_KEY_SIZE) {
            Arrays.fill(decrypted, (byte) 0);
            return null;
        }
        byte[] localKey = Arrays.copyOfRange(decrypted, 4, 4 + LOCAL_KEY_SIZE);
        Arrays.fill(decrypted, (byte) 0);
        return localKey;
    }

    private static TdataAccount findAuthorization(File tdataRoot, byte[] localKey) {
        List<File> files = new ArrayList<>();
        File[] rootFiles = tdataRoot.listFiles();
        if (rootFiles != null) {
            for (File file : rootFiles) {
                if (file.isFile()) {
                    files.add(file);
                }
            }
            // one level down, in case an odd export nested the mtp file
            for (File file : rootFiles) {
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    if (children != null) {
                        for (File child : children) {
                            if (child.isFile()) {
                                files.add(child);
                            }
                        }
                    }
                }
            }
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("key_data")) {
                continue;
            }
            long length = file.length();
            if (length < 64 || length > 8L * 1024 * 1024) {
                continue;
            }
            byte[] payload;
            try {
                payload = readTdf(file);
            } catch (Exception e) {
                continue;
            }
            if (payload == null) {
                continue;
            }
            byte[] encrypted;
            try {
                encrypted = new QReader(payload).readByteArray();
            } catch (Exception e) {
                continue;
            }
            if (encrypted == null) {
                continue;
            }
            byte[] plain = decryptLocal(encrypted, localKey);
            if (plain == null) {
                continue;
            }
            TdataAccount account = null;
            try {
                account = extractAuthorization(plain);
            } catch (Exception e) {
                FileLog.e("TdataImporter: cannot parse block in " + name, e);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            if (account != null) {
                return account;
            }
        }
        return null;
    }

    private static TdataAccount extractAuthorization(byte[] plain) {
        // The mtp data file holds a single settings block: quint32(dbiMtpAuthorization) + QByteArray
        QReader reader = new QReader(plain);
        reader.seek(4); // skip the length prefix written by EncryptedDescriptor
        while (reader.remaining() >= 8) {
            int blockId;
            try {
                blockId = reader.readInt32();
            } catch (Exception e) {
                break;
            }
            if (blockId != DBI_MTP_AUTHORIZATION) {
                break; // unknown block, we cannot skip it safely
            }
            byte[] serialized;
            try {
                serialized = reader.readByteArray();
            } catch (Exception e) {
                break;
            }
            if (serialized == null) {
                break;
            }
            TdataAccount account = parseMtpAuthorization(serialized);
            Arrays.fill(serialized, (byte) 0);
            if (account != null) {
                return account;
            }
        }
        // fall back to scanning: some builds prepend other blocks we do not know how to skip
        for (int offset = 4; offset + 8 <= plain.length; offset += 4) {
            if (plain[offset] != 0 || plain[offset + 1] != 0 || plain[offset + 2] != 0
                    || (plain[offset + 3] & 0xFF) != DBI_MTP_AUTHORIZATION) {
                continue;
            }
            try {
                QReader reader2 = new QReader(plain);
                reader2.seek(offset + 4);
                byte[] serialized = reader2.readByteArray();
                if (serialized == null) {
                    continue;
                }
                TdataAccount account = parseMtpAuthorization(serialized);
                Arrays.fill(serialized, (byte) 0);
                if (account != null) {
                    return account;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    private static TdataAccount parseMtpAuthorization(byte[] serialized) {
        if (serialized == null || serialized.length < 12) {
            return null;
        }
        try {
            QReader reader = new QReader(serialized);
            long userId;
            int mainDcId;
            long wide = reader.readInt64();
            if (wide == WIDE_IDS_TAG) {
                userId = reader.readInt64();
                mainDcId = reader.readInt32();
            } else {
                reader.seek(0);
                userId = reader.readInt32();
                mainDcId = reader.readInt32();
            }
            if (mainDcId <= 0 || bareDcId(mainDcId) <= 0) {
                return null;
            }
            int count = reader.readInt32();
            if (count < 0 || count > 128) {
                return null;
            }
            byte[] mainKey = null;
            for (int a = 0; a < count; a++) {
                int dcId = reader.readInt32();
                byte[] key = reader.readRaw(AUTH_KEY_SIZE);
                if (mainKey == null && bareDcId(dcId) == bareDcId(mainDcId)) {
                    mainKey = key;
                }
            }
            if (mainKey == null) {
                return null;
            }
            TdataAccount account = new TdataAccount();
            account.dcId = bareDcId(mainDcId);
            account.userId = userId;
            account.authKey = mainKey;
            return account;
        } catch (Exception e) {
            return null;
        }
    }

    private static int bareDcId(int shiftedDcId) {
        return Math.abs(shiftedDcId) % DC_SHIFT;
    }

    // endregion

    // region TDF + crypto

    private static byte[] readTdf(File file) throws TdataException {
        byte[] all = readAllBytes(file);
        if (all == null || all.length < 4 + 4 + 16) {
            return null;
        }
        for (int a = 0; a < TDF_MAGIC.length; a++) {
            if (all[a] != TDF_MAGIC[a]) {
                return null;
            }
        }
        int version = readInt32Le(all, 4);
        int dataSize = all.length - 8 - 16;
        if (dataSize < 0) {
            return null;
        }
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            md5.update(all, 8, dataSize);
            md5.update(int32Le(dataSize));
            md5.update(int32Le(version));
            md5.update(TDF_MAGIC);
            byte[] digest = md5.digest();
            for (int a = 0; a < 16; a++) {
                if (digest[a] != all[8 + dataSize + a]) {
                    return null;
                }
            }
        } catch (Exception e) {
            throw new TdataException(ERROR_UNKNOWN, "md5 unavailable", e);
        }
        return Arrays.copyOfRange(all, 8, 8 + dataSize);
    }

    /**
     * Telegram Desktop DecryptLocal(): [16 byte msgKey][AES-IGE payload], key/iv derived with the
     * old MTProto 1.0 KDF. The plaintext starts with a little endian int32 with its real length.
     */
    private static byte[] decryptLocal(byte[] encrypted, byte[] key) {
        if (encrypted == null || key == null || key.length != LOCAL_KEY_SIZE) {
            return null;
        }
        if (encrypted.length <= 16 || ((encrypted.length - 16) & 0x0F) != 0) {
            return null;
        }
        byte[] msgKey = Arrays.copyOfRange(encrypted, 0, 16);
        byte[] data = Arrays.copyOfRange(encrypted, 16, encrypted.length);
        byte[][] aes = prepareAesOldMtp(key, msgKey);
        try {
            Utilities.aesIgeEncryptionByteArray(data, aes[0], aes[1], false, false, 0, data.length);
        } catch (Exception e) {
            FileLog.e("TdataImporter: aes-ige failed", e);
            return null;
        } finally {
            Arrays.fill(aes[0], (byte) 0);
            Arrays.fill(aes[1], (byte) 0);
        }
        byte[] sha1 = Utilities.computeSHA1(data);
        if (sha1 == null || sha1.length < 16) {
            return null;
        }
        for (int a = 0; a < 16; a++) {
            if (sha1[a] != msgKey[a]) {
                return null; // wrong key
            }
        }
        int dataLen = readInt32Le(data, 0);
        if (dataLen < 4 || dataLen > data.length || dataLen <= data.length - 16) {
            return null;
        }
        byte[] result = Arrays.copyOfRange(data, 0, dataLen);
        Arrays.fill(data, (byte) 0);
        return result;
    }

    /**
     * MTProto 1.0 key derivation as used by Telegram Desktop local storage
     * (AuthKey::prepareAES_oldmtp with send == false, i.e. x == 8).
     */
    private static byte[][] prepareAesOldMtp(byte[] authKey, byte[] msgKey) {
        final int x = 8;
        byte[] a = Utilities.computeSHA1(concat(msgKey, slice(authKey, x, 32)));
        byte[] b = Utilities.computeSHA1(concat(slice(authKey, 32 + x, 16), msgKey, slice(authKey, 48 + x, 16)));
        byte[] c = Utilities.computeSHA1(concat(slice(authKey, 64 + x, 32), msgKey));
        byte[] d = Utilities.computeSHA1(concat(msgKey, slice(authKey, 96 + x, 32)));

        byte[] aesKey = new byte[32];
        System.arraycopy(a, 0, aesKey, 0, 8);
        System.arraycopy(b, 8, aesKey, 8, 12);
        System.arraycopy(c, 4, aesKey, 20, 12);

        byte[] aesIv = new byte[32];
        System.arraycopy(a, 8, aesIv, 0, 12);
        System.arraycopy(b, 0, aesIv, 12, 8);
        System.arraycopy(c, 16, aesIv, 20, 4);
        System.arraycopy(d, 0, aesIv, 24, 8);

        return new byte[][] {aesKey, aesIv};
    }

    /**
     * Modern Telegram Desktop CreateLocalKey(): PBKDF2-HMAC-SHA512 over SHA512(salt|passcode|salt),
     * one iteration when there is no passcode.
     */
    private static byte[] createLocalKeyModern(byte[] salt, byte[] passcode) throws TdataException {
        try {
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(salt);
            sha512.update(passcode);
            sha512.update(salt);
            byte[] hash = sha512.digest();
            int iterations = passcode.length > 0 ? 100000 : 1;
            byte[] result = pbkdf2(hash, salt, iterations, LOCAL_KEY_SIZE, "HmacSHA512", 64);
            Arrays.fill(hash, (byte) 0);
            return result;
        } catch (Exception e) {
            throw new TdataException(ERROR_UNKNOWN, "cannot derive local key", e);
        }
    }

    /** Legacy Telegram Desktop CreateLegacyLocalKey(): PBKDF2-HMAC-SHA1 over the raw passcode. */
    private static byte[] createLocalKeyLegacy(byte[] salt, byte[] passcode) throws TdataException {
        try {
            int iterations = passcode.length > 0 ? 4000 : 4;
            return pbkdf2(passcode, salt, iterations, LOCAL_KEY_SIZE, "HmacSHA1", 20);
        } catch (Exception e) {
            throw new TdataException(ERROR_UNKNOWN, "cannot derive legacy local key", e);
        }
    }

    /**
     * PBKDF2 done by hand: the JCE PBEKeySpec API only takes char[] passwords and would mangle the
     * binary password Telegram Desktop uses.
     */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iterations, int outLength, String algorithm, int hashLength) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(password.length == 0 ? new byte[1] : password, algorithm));
        byte[] out = new byte[outLength];
        int blocks = (outLength + hashLength - 1) / hashLength;
        byte[] block = new byte[salt.length + 4];
        System.arraycopy(salt, 0, block, 0, salt.length);
        int offset = 0;
        for (int i = 1; i <= blocks; i++) {
            block[salt.length] = (byte) (i >>> 24);
            block[salt.length + 1] = (byte) (i >>> 16);
            block[salt.length + 2] = (byte) (i >>> 8);
            block[salt.length + 3] = (byte) i;
            byte[] u = mac.doFinal(block);
            byte[] t = u.clone();
            for (int j = 1; j < iterations; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) {
                    t[k] ^= u[k];
                }
            }
            int copy = Math.min(hashLength, outLength - offset);
            System.arraycopy(t, 0, out, offset, copy);
            offset += copy;
        }
        return out;
    }

    // endregion

    // region small helpers

    private static byte[] readAllBytes(File file) {
        long length = file.length();
        if (length <= 0 || length > 32L * 1024 * 1024) {
            return null;
        }
        byte[] result = new byte[(int) length];
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            int read = 0;
            while (read < result.length) {
                int r = fis.read(result, read, result.length - read);
                if (r <= 0) {
                    break;
                }
                read += r;
            }
            if (read != result.length) {
                return null;
            }
            return result;
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static byte[] slice(byte[] src, int offset, int length) {
        return Arrays.copyOfRange(src, offset, offset + length);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] int32Le(int value) {
        return new byte[] {
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)
        };
    }

    private static int readInt32Le(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    /** Big endian reader matching QDataStream defaults. */
    private static class QReader {
        private final byte[] buffer;
        private int position;

        QReader(byte[] buffer) {
            this.buffer = buffer;
        }

        void seek(int position) {
            this.position = position;
        }

        int remaining() {
            return buffer.length - position;
        }

        int readInt32() {
            require(4);
            int result = ((buffer[position] & 0xFF) << 24)
                    | ((buffer[position + 1] & 0xFF) << 16)
                    | ((buffer[position + 2] & 0xFF) << 8)
                    | (buffer[position + 3] & 0xFF);
            position += 4;
            return result;
        }

        long readInt64() {
            require(8);
            long result = 0;
            for (int a = 0; a < 8; a++) {
                result = (result << 8) | (buffer[position + a] & 0xFFL);
            }
            position += 8;
            return result;
        }

        byte[] readRaw(int length) {
            require(length);
            byte[] result = Arrays.copyOfRange(buffer, position, position + length);
            position += length;
            return result;
        }

        byte[] readByteArray() {
            int length = readInt32();
            if (length == -1) {
                return null; // null QByteArray
            }
            if (length < 0 || length > remaining()) {
                throw new IndexOutOfBoundsException("bad QByteArray length");
            }
            return readRaw(length);
        }

        private void require(int count) {
            if (count < 0 || position < 0 || position + count > buffer.length) {
                throw new IndexOutOfBoundsException(String.format(Locale.US, "want %d at %d of %d", count, position, buffer.length));
            }
        }
    }

    // endregion
}
