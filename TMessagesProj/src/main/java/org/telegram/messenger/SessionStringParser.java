/*
 * Parser for the session string layouts produced by the common third party MTProto libraries.
 *
 * Supported:
 *   Telethon StringSession  "1" + base64url(dc_id:1, ip:4|16, port:2 BE, auth_key:256)
 *   Pyrogram SESSION_STRING base64url(dc_id:1, [api_id:4], test_mode:1, auth_key:256, user_id:4|8, is_bot:1)
 *   A bare 256 byte auth key given as hex or base64, with the dc id supplied separately.
 *
 * Nothing here is ever logged - the parsed key is secret material.
 */
package org.telegram.messenger;

import android.util.Base64;

public class SessionStringParser {

    public static final int AUTH_KEY_SIZE = 256;
    public static final int MIN_DC_ID = 1;
    public static final int MAX_DC_ID = 10;

    public static class Session {
        public int dcId;
        /** IPv4 of the datacenter if the session string carried one, otherwise null. */
        public String ip;
        public int port;
        /** User id if the format carried one, otherwise 0. */
        public long userId;
        public byte[] authKey;
    }

    private SessionStringParser() {
    }

    /**
     * @param input        the pasted session string, or a bare 256 byte key in hex/base64
     * @param fallbackDcId dc id typed by the user, only used for a bare key (0 when not given)
     * @return parsed session, or null when nothing matched
     */
    public static Session parse(String input, int fallbackDcId) {
        if (input == null) {
            return null;
        }
        String cleaned = stripWhitespace(input);
        if (cleaned.length() == 0) {
            return null;
        }

        Session session = parseTelethon(cleaned);
        if (session != null) {
            return session;
        }
        session = parsePyrogram(cleaned);
        if (session != null) {
            return session;
        }
        return parseRawKey(cleaned, fallbackDcId);
    }

    // region formats

    private static Session parseTelethon(String cleaned) {
        if (cleaned.length() < 2 || cleaned.charAt(0) != '1') {
            return null;
        }
        byte[] data = decodeBase64(cleaned.substring(1));
        if (data == null) {
            return null;
        }
        final int ipLength;
        if (data.length == 1 + 4 + 2 + AUTH_KEY_SIZE) {
            ipLength = 4;
        } else if (data.length == 1 + 16 + 2 + AUTH_KEY_SIZE) {
            ipLength = 16;
        } else {
            return null;
        }
        int dcId = data[0] & 0xFF;
        if (!isValidDcId(dcId)) {
            return null;
        }
        int offset = 1;
        String ip = null;
        if (ipLength == 4) {
            // only IPv4 is usable, importAuthorizationKey replaces the IPv4 address list
            ip = (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                    + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
        }
        offset += ipLength;
        int port = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        offset += 2;

        Session session = new Session();
        session.dcId = dcId;
        session.ip = ip;
        session.port = port > 0 && port <= 65535 ? port : 0;
        session.userId = 0;
        session.authKey = copy(data, offset, AUTH_KEY_SIZE);
        wipe(data);
        if (session.ip == null) {
            session.port = 0;
        }
        return session;
    }

    private static Session parsePyrogram(String cleaned) {
        byte[] data = decodeBase64(cleaned);
        if (data == null) {
            return null;
        }
        // >BI?256sQ? (v2), >B?256sQ? (v1 64 bit ids), >B?256sI? (v1 32 bit ids)
        final int apiIdSize;
        final int userIdSize;
        if (data.length == 1 + 4 + 1 + AUTH_KEY_SIZE + 8 + 1) {
            apiIdSize = 4;
            userIdSize = 8;
        } else if (data.length == 1 + 1 + AUTH_KEY_SIZE + 8 + 1) {
            apiIdSize = 0;
            userIdSize = 8;
        } else if (data.length == 1 + 1 + AUTH_KEY_SIZE + 4 + 1) {
            apiIdSize = 0;
            userIdSize = 4;
        } else {
            return null;
        }
        int dcId = data[0] & 0xFF;
        if (!isValidDcId(dcId)) {
            return null;
        }
        int offset = 1 + apiIdSize + 1; // api_id + test_mode
        byte[] key = copy(data, offset, AUTH_KEY_SIZE);
        offset += AUTH_KEY_SIZE;
        long userId = 0;
        for (int a = 0; a < userIdSize; a++) {
            userId = (userId << 8) | (data[offset + a] & 0xFFL);
        }
        wipe(data);

        Session session = new Session();
        session.dcId = dcId;
        session.ip = null;
        session.port = 0;
        session.userId = userId;
        session.authKey = key;
        return session;
    }

    private static Session parseRawKey(String cleaned, int fallbackDcId) {
        if (!isValidDcId(fallbackDcId)) {
            return null;
        }
        byte[] key = null;
        if (cleaned.length() == AUTH_KEY_SIZE * 2 && isHex(cleaned)) {
            key = decodeHex(cleaned);
        }
        if (key == null) {
            byte[] decoded = decodeBase64(cleaned);
            if (decoded != null && decoded.length == AUTH_KEY_SIZE) {
                key = decoded;
            } else if (decoded != null) {
                wipe(decoded);
            }
        }
        if (key == null) {
            return null;
        }
        Session session = new Session();
        session.dcId = fallbackDcId;
        session.ip = null;
        session.port = 0;
        session.userId = 0;
        session.authKey = key;
        return session;
    }

    // endregion

    // region helpers

    public static boolean isValidDcId(int dcId) {
        return dcId >= MIN_DC_ID && dcId <= MAX_DC_ID;
    }

    private static String stripWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int a = 0; a < value.length(); a++) {
            char c = value.charAt(a);
            if (c > ' ') {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    /** Accepts both the standard and the url safe alphabet, with or without padding. */
    private static byte[] decodeBase64(String value) {
        if (value.length() == 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder(value.length() + 3);
        for (int a = 0; a < value.length(); a++) {
            char c = value.charAt(a);
            if (c == '-') {
                c = '+';
            } else if (c == '_') {
                c = '/';
            } else if (c == '=') {
                continue;
            }
            builder.append(c);
        }
        while (builder.length() % 4 != 0) {
            builder.append('=');
        }
        try {
            return Base64.decode(builder.toString(), Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isHex(String value) {
        for (int a = 0; a < value.length(); a++) {
            char c = value.charAt(a);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    private static byte[] decodeHex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int a = 0; a < result.length; a++) {
            int hi = Character.digit(value.charAt(a * 2), 16);
            int lo = Character.digit(value.charAt(a * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            result[a] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    private static byte[] copy(byte[] source, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(source, offset, result, 0, length);
        return result;
    }

    private static void wipe(byte[] data) {
        if (data != null) {
            java.util.Arrays.fill(data, (byte) 0);
        }
    }

    // endregion
}
