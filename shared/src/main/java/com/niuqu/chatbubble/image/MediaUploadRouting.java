package com.niuqu.chatbubble.image;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * The one place an uploaded image is routed to a hosting channel (2.5.0 M9,
 * master-plan §13 D6).
 *
 * <p>An image composed while a group channel is active belongs to that group and
 * must be hosted on the groups channel: the server then stores the bytes under a
 * private {@code g_} id whose {@code MediaBinding} names the group UUID, so only
 * members whose history window covers the message can read it. Everywhere else
 * the ordinary public upload stands, with the pre-2.5.0 third-party fallback.
 *
 * <p>The platforms differ only in how they talk to the wire (Forge's channel,
 * NeoForge's {@code PacketDistributor}, Fabric's networking API) and in the config
 * accessor for the third-party fallback; the routing decision, the mapping from
 * an upload result to a media URL, and the rule <em>a group image never falls
 * back to the third-party host</em> live here once, so the three targets cannot
 * drift apart.
 */
public final class MediaUploadRouting {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * What to call the three hosting paths. Implemented once per platform with
     * its own transport; the decision that <em>chooses</em> among them is this
     * class's job.
     */
    public interface Host {
        /** Server media hosting as an ordinary public upload. Null on failure. */
        String uploadPublic(byte[] bytes, String contentType);

        /** Server media hosting scoped to a group. Null when the server refuses. */
        String uploadGroup(UUID groupId, byte[] bytes, String contentType);

        /** The configured third-party image host. Null on failure. */
        String uploadThirdParty(byte[] bytes, String fileName);
    }

    private MediaUploadRouting() {}

    /**
     * Resolve the hosted URL for one image, or {@code null} on any failure.
     *
     * <p>{@code groupId != null} means the image was composed in that group: it
     * goes to the group channel or nowhere. A group upload never touches the
     * public channel and never falls back to the third-party host — silently
     * rehosting a private group image as a public link is exactly the leak this
     * seam exists to prevent.
     */
    public static String resolve(UUID groupId, byte[] bytes, String fileName,
                                 String contentType, Host host) {
        if (bytes == null || bytes.length == 0) return null;
        if (groupId != null) return groupOrNull(groupId, bytes, contentType, host);
        String serverUrl = host.uploadPublic(bytes, contentType);
        if (serverUrl != null) return serverUrl;
        return host.uploadThirdParty(bytes, fileName);
    }

    /** A group image: the group channel or nothing, never the public fallback. */
    private static String groupOrNull(UUID groupId, byte[] bytes, String contentType, Host host) {
        try {
            String url = host.uploadGroup(groupId, bytes, contentType);
            if (url == null) {
                LOGGER.info("[e33chat] group media upload refused; not falling back to a public host");
            }
            return url;
        } catch (Throwable t) {
            LOGGER.info("[e33chat] group media upload failed: {}; not falling back to a public host",
                t.toString());
            return null;
        }
    }
}
