package com.niuqu.chatbubble.image;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HTTP(S) fetch that never lets the transport follow a redirect on its own.
 *
 * <p>A chat message names any URL, so a hostile server can answer with
 * {@code 302 Location: http://127.0.0.1:8080/} (or {@code 10.x}/{@code 192.168.x})
 * and make the client GET its owner's own machine or LAN. {@link MediaUrlPolicy}
 * only ever saw the first URL, so the implicit redirect was a full SSRF bypass
 * (audit SEC-1/B8, MediaBranchSecurity).
 *
 * <p>The fix: the shared client is built with {@link HttpClient.Redirect#NEVER},
 * and this loop follows redirects itself. Before every hop — the initial URL and
 * every {@code Location} — it re-runs the scheme/host policy <em>and</em> resolves
 * the host and rejects any address the policy calls private (SEC-2/M9: a public
 * name that resolves to a loopback/LAN address, e.g. {@code 10.0.0.5.nip.io}).
 * The hop count is capped so a redirect cycle cannot spin forever.
 *
 * <p>Redirect targets are resolved against the current URL, so a relative
 * {@code Location: /next.png} is handled too.
 *
 * <p>The transport and the resolver are injected, which keeps the loop testable
 * without a network: tests hand in a fake transport and a fake resolver.
 */
public final class SafeHttpFetch {

    /** Redirects followed after the initial request; a longer chain is refused. */
    public static final int MAX_REDIRECTS = 3;

    private SafeHttpFetch() {}

    /** A single HTTP exchange: status, optional Location header and body bytes. */
    public record Response(int status, String location, byte[] body) {}

    /** Performs one request. Supplied by the caller so the loop is testable. */
    @FunctionalInterface
    public interface Transport {
        Response send(URI uri) throws Exception;
    }

    /** Resolves a host name to its addresses; {@code InetAddress} by default. */
    @FunctionalInterface
    public interface Resolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    /** Thrown when a hop (or the URL itself) fails the fetch policy. */
    public static final class BlockedUrlException extends IOException {
        public BlockedUrlException(String message) { super(message); }
    }

    /** Resolver backed by the platform's DNS (the production path). */
    public static Resolver systemResolver() {
        return host -> Arrays.asList(InetAddress.getAllByName(host));
    }

    /** Follows redirects under the policy, using the platform resolver. */
    public static Response fetch(HttpClient client, URI start, Duration timeout, long maxBytes)
            throws Exception {
        return fetch(client, start, timeout, maxBytes, systemResolver());
    }

    /** Follows redirects under the policy with an explicit resolver. */
    public static Response fetch(HttpClient client, URI start, Duration timeout, long maxBytes,
                                 Resolver resolver) throws Exception {
        return fetch(start, uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).build();
            HttpResponse<byte[]> response = client.send(request, new BoundedBodyHandler(maxBytes));
            String location = response.headers().firstValue("location").orElse(null);
            return new Response(response.statusCode(), location, response.body());
        }, resolver);
    }

    /**
     * The redirect loop itself. Every hop is policy-checked <em>before</em> it is
     * sent; the first non-redirect response is returned. Throws
     * {@link BlockedUrlException} for a blocked scheme/host/address and for a
     * redirect chain longer than {@link #MAX_REDIRECTS}.
     */
    public static Response fetch(URI start, Transport transport, Resolver resolver) throws Exception {
        URI current = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkAllowed(current, resolver);
            Response response = transport.send(current);
            if (!isRedirect(response.status())) {
                return response;
            }
            String location = response.location();
            if (location == null || location.isBlank()) {
                throw new BlockedUrlException("redirect without Location from " + current);
            }
            current = current.resolve(location);
        }
        throw new BlockedUrlException("too many redirects (max " + MAX_REDIRECTS + ")");
    }

    /** Scheme + host + resolved-address policy for one hop. */
    static void checkAllowed(URI uri, Resolver resolver) throws BlockedUrlException {
        if (uri == null) throw new BlockedUrlException("null uri");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedUrlException("scheme not allowed: " + uri);
        }
        String host = uri.getHost();
        if (host == null || MediaUrlPolicy.isBlockedHost(host)) {
            throw new BlockedUrlException("host not allowed: " + uri);
        }
        List<InetAddress> addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new BlockedUrlException("host does not resolve: " + host);
        }
        if (addresses == null || addresses.isEmpty()) {
            throw new BlockedUrlException("host does not resolve: " + host);
        }
        for (InetAddress address : addresses) {
            byte[] raw = address == null ? null : address.getAddress();
            if (raw == null || MediaUrlPolicy.isBlockedAddress(raw)) {
                throw new BlockedUrlException("host resolves to a private address: " + host);
            }
        }
    }

    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
}
