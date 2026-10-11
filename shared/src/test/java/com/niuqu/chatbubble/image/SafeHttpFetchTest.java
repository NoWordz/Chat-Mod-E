package com.niuqu.chatbubble.image;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-1/B8: the shared HTTP client used to follow redirects on its own, so a
 * hostile chat image server could answer {@code 302 Location: http://127.0.0.1/}
 * and drive the victim's client into its own machine or LAN. These tests pin the
 * replacements: every hop must pass {@link MediaUrlPolicy} and a resolved-address
 * check, and the chain is bounded.
 */
class SafeHttpFetchTest {

    /** Every non-private host in these tests resolves to this public TEST-NET-3 address. */
    private static SafeHttpFetch.Resolver publicResolver() {
        return host -> List.of(InetAddress.getByName("203.0.113.9"));
    }

    private static SafeHttpFetch.Response ok(byte[] body) {
        return new SafeHttpFetch.Response(200, null, body);
    }

    private static SafeHttpFetch.Response redirect(String location) {
        return new SafeHttpFetch.Response(302, location, new byte[0]);
    }

    @Test
    void followsAnAllowedRedirectToAPublicHost() throws Exception {
        int[] calls = {0};
        SafeHttpFetch.Response resp = SafeHttpFetch.fetch(
            URI.create("http://evil.tld/a.png"),
            uri -> {
                calls[0]++;
                if (uri.getHost().equals("evil.tld")) {
                    return redirect("https://cdn.example.com/a.png");
                }
                return ok(new byte[]{1, 2, 3});
            },
            publicResolver());
        assertEquals(3, resp.body().length);
        assertEquals(2, calls[0], "initial request plus one followed hop");
    }

    @Test
    void refusesARedirectToLoopbackBeforeSendingIt() {
        int[] calls = {0};
        SafeHttpFetch.BlockedUrlException ex = assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://evil.tld/a.png"),
                uri -> {
                    calls[0]++;
                    return redirect("http://127.0.0.1:8080/steal");
                },
                publicResolver()));
        assertEquals(1, calls[0], "the loopback hop must never be sent");
        assertTrue(ex.getMessage().contains("127.0.0.1") || ex.getMessage().contains("not allowed"),
            "rejection must name the offending hop: " + ex.getMessage());
    }

    @Test
    void refusesARedirectToPrivateLanHost() {
        assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://evil.tld/a.png"),
                uri -> redirect("http://192.168.1.10/a.png"),
                publicResolver()));
    }

    @Test
    void boundsTheRedirectChain() {
        SafeHttpFetch.BlockedUrlException ex = assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://hop0.tld/a.png"),
                uri -> redirect("http://hop" + (Character.getNumericValue(uri.getHost().charAt(3)) + 1) + ".tld/a.png"),
                publicResolver()));
        assertTrue(ex.getMessage().contains("too many redirects"), ex.getMessage());
    }

    @Test
    void refusesAHostThatResolvesToAPrivateAddress() {
        SafeHttpFetch.Resolver rebinding = host -> List.of(InetAddress.getByName("10.0.0.5"));
        SafeHttpFetch.BlockedUrlException ex = assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://10-0-0-5.nip.io/a.png"),
                uri -> ok(new byte[]{1}),
                rebinding));
        assertTrue(ex.getMessage().contains("private"), ex.getMessage());
    }

    @Test
    void refusesAnUnknownHostBeforeSending() {
        SafeHttpFetch.Resolver none = host -> { throw new java.net.UnknownHostException(host); };
        assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://does-not-exist.invalid/a.png"),
                uri -> ok(new byte[]{1}),
                none));
    }

    @Test
    void refusesNonHttpScheme() {
        assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("file:///etc/passwd"),
                uri -> ok(new byte[]{1}),
                publicResolver()));
    }

    @Test
    void checksEveryHopNotJustTheFirst() {
        SafeHttpFetch.Resolver publicThenPrivate = host -> List.of(InetAddress.getByName(
            host.equals("mid.tld") ? "10.1.2.3" : "203.0.113.9"));
        assertThrows(SafeHttpFetch.BlockedUrlException.class,
            () -> SafeHttpFetch.fetch(
                URI.create("http://evil.tld/a.png"),
                uri -> redirect("http://mid.tld/a.png"),
                publicThenPrivate));
    }
}
