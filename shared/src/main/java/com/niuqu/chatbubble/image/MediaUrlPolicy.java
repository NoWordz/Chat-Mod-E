package com.niuqu.chatbubble.image;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Fetch policy for URLs that arrive from remote chat content.
 *
 * <p>A chat message can name any URL, so the loaders must not let one turn into
 * a local file read ({@code file:} / UNC) or a request to the player's own
 * machine or LAN (SSRF). Only the server media transport and plain HTTP(S) are
 * fetchable, and HTTP(S) hosts that are loopback / private / link-local
 * literals or local-only names are refused.
 *
 * <p>The host test is purely syntactic — <b>no DNS resolution is performed</b>,
 * so a public hostname that resolves to a private address (DNS rebinding), a
 * public host that redirects to a private one, and proxy environment variables
 * are explicitly out of scope for this guard.
 */
public final class MediaUrlPolicy {

    private MediaUrlPolicy() {}

    /**
     * Whether a URL may be handed to a fetcher at all: the server-hosted media
     * transport ({@code e33chat://media/<id>}, whose id is validated by the
     * per-platform MediaClient) or plain HTTP(S) to a non-local host.
     */
    public static boolean isFetchable(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("e33chat://media/")) return true;
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        String host = hostOf(url);
        return host != null && !isBlockedHost(host);
    }

    /**
     * Deny rules, listed as implemented:
     * <ul>
     *   <li>scheme: anything other than {@code http}/{@code https}/{@code e33chat}
     *       is not fetchable — {@code file:} (local or UNC) and bare UNC paths
     *       are refused before any I/O;</li>
     *   <li>names: {@code localhost}, {@code *.localhost} and {@code *.local}
     *       (trailing root dot ignored);</li>
     *   <li>IPv4 literals: {@code 0.0.0.0/8}, {@code 127.0.0.0/8},
     *       {@code 10.0.0.0/8}, {@code 172.16.0.0/12}, {@code 192.168.0.0/16},
     *       {@code 169.254.0.0/16}, {@code 100.64.0.0/10}, {@code 192.0.0.0/24},
     *       {@code 198.18.0.0/15}, {@code 224.0.0.0/4} and the broadcast address
     *       ({@code 255.255.255.255}) — every numeric spelling (dotted, octal,
     *       hex, single integer) is normalized first;</li>
     *   <li>IPv6 literals: {@code ::}, {@code ::1}, {@code fc00::/7},
     *       {@code fe80::/10}, {@code fec0::/10}, {@code ff00::/8}, plus any
     *       IPv4-mapped ({@code ::ffff:a.b.c.d}) or IPv4-compatible
     *       ({@code ::a.b.c.d}) form that decodes into a denied IPv4 range.</li>
     * </ul>
     */
    public static boolean isBlockedHost(String host) {
        if (host == null || host.isBlank()) return true;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        int zone = h.indexOf('%');                                   // IPv6 zone id (fe80::1%eth0)
        if (zone >= 0) h = h.substring(0, zone);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);  // FQDN root dot
        if (h.isEmpty()) return true;
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local")) {
            return true;
        }
        byte[] literal = parseIpLiteral(h);
        return literal != null && isBlockedAddress(literal);
    }

    /** Host component of an absolute URL, without needing a full/ASCII URI parse. */
    static String hostOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return null;
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);        // drop userinfo
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            return close < 0 ? null : authority.substring(0, close + 1);
        }
        int colon = authority.indexOf(':');
        return colon >= 0 ? authority.substring(0, colon) : authority;
    }

    /** 4 or 16 bytes when the text is a numeric IP literal, else null. Never resolves. */
    static byte[] parseIpLiteral(String h) {
        return h.indexOf(':') >= 0 ? parseIpv6(h) : parseIpv4(h);
    }

    /**
     * Any numeric IPv4 spelling — {@code inet_aton} forms with 1..4 parts plus
     * octal/hex parts ({@code 127.0.0.1}, {@code 127.1}, {@code 2130706433},
     * {@code 0x7f000001}, {@code 0177.0.0.1}) — to 4 bytes, else null.
     */
    static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length < 1 || parts.length > 4) return null;
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long value = parseIpv4Part(parts[i]);
            if (value == null || value < 0) return null;
            values[i] = value;
        }
        long value;
        switch (parts.length) {
            case 1 -> value = values[0];
            case 2 -> {
                if (values[0] > 0xFF) return null;
                value = (values[0] << 24) | values[1];
            }
            case 3 -> {
                if (values[0] > 0xFF || values[1] > 0xFF) return null;
                value = (values[0] << 24) | (values[1] << 16) | values[2];
            }
            default -> {
                for (long v : values) if (v > 0xFF) return null;
                value = (values[0] << 24) | (values[1] << 16) | (values[2] << 8) | values[3];
            }
        }
        if (value < 0 || value > 0xFFFFFFFFL) return null;
        return new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    private static Long parseIpv4Part(String part) {
        if (part.isEmpty()) return null;
        try {
            if (part.startsWith("0x") || part.startsWith("0X")) {
                return Long.parseLong(part.substring(2), 16);
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                return Long.parseLong(part.substring(1), 8);
            }
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Textual IPv6 literal (with {@code ::} and embedded IPv4) to 16 bytes, else null. */
    static byte[] parseIpv6(String text) {
        int dbl = text.indexOf("::");
        if (dbl >= 0 && text.indexOf("::", dbl + 1) >= 0) return null;
        List<Integer> groups = new ArrayList<>();
        if (!appendIpv6Groups(dbl < 0 ? text : text.substring(0, dbl), groups)) return null;
        List<Integer> tail = new ArrayList<>();
        if (dbl >= 0 && !appendIpv6Groups(text.substring(dbl + 2), tail)) return null;
        if (dbl < 0) {
            if (groups.size() != 8) return null;
        } else {
            if (groups.size() + tail.size() > 7) return null;
            while (groups.size() + tail.size() < 8) groups.add(0);
        }
        groups.addAll(tail);
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i * 2] = (byte) (groups.get(i) >> 8);
            out[i * 2 + 1] = (byte) (groups.get(i) & 0xFF);
        }
        return out;
    }

    private static boolean appendIpv6Groups(String half, List<Integer> out) {
        if (half.isEmpty()) return true;
        String[] parts = half.split(":", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) return false;                        // "::" residue is handled by the caller
            if (part.indexOf('.') >= 0) {
                if (i != parts.length - 1) return false;             // embedded IPv4 must be last
                byte[] v4 = parseIpv4(part);
                if (v4 == null) return false;
                out.add(((v4[0] & 0xFF) << 8) | (v4[1] & 0xFF));
                out.add(((v4[2] & 0xFF) << 8) | (v4[3] & 0xFF));
                continue;
            }
            if (part.length() > 4) return false;
            try {
                out.add(Integer.parseInt(part, 16));
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    /** Denied range test over a parsed 4- or 16-byte address. */
    static boolean isBlockedAddress(byte[] addr) {
        if (addr == null) return true;
        if (addr.length == 4) {
            int a = addr[0] & 0xFF, b = addr[1] & 0xFF, c = addr[2] & 0xFF;
            if (a == 0 || a == 127 || a == 10) return true;              // this-network / loopback / private
            if (a == 172 && b >= 16 && b <= 31) return true;             // private
            if (a == 192 && b == 168) return true;                       // private
            if (a == 169 && b == 254) return true;                       // link-local
            if (a == 100 && b >= 64 && b <= 127) return true;            // CGNAT
            if (a == 192 && b == 0 && c == 0) return true;               // 192.0.0.0/24
            if (a == 198 && (b == 18 || b == 19)) return true;           // benchmarking
            if (a >= 224) return true;                                   // multicast / reserved / broadcast
            return false;
        }
        if (addr.length != 16) return true;
        boolean highTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (addr[i] != 0) { highTenZero = false; break; }
        }
        if (highTenZero && (addr[10] & 0xFF) == 0xFF && (addr[11] & 0xFF) == 0xFF) {
            return isBlockedAddress(new byte[] {addr[12], addr[13], addr[14], addr[15]});  // ::ffff:a.b.c.d
        }
        if (highTenZero && addr[10] == 0 && addr[11] == 0) {
            return isBlockedAddress(new byte[] {addr[12], addr[13], addr[14], addr[15]});  // ::a.b.c.d
        }
        int first = addr[0] & 0xFF, second = addr[1] & 0xFF;
        if ((first & 0xFE) == 0xFC) return true;                         // unique-local fc00::/7
        if (first == 0xFE && (second & 0xC0) == 0x80) return true;       // link-local fe80::/10
        if (first == 0xFE && (second & 0xC0) == 0xC0) return true;       // site-local fec0::/10
        if (first == 0xFF) return true;                                  // multicast ff00::/8
        return false;
    }
}
