package com.niuqu.chatbubble.server;

import com.niuqu.chatbubble.group.GroupHistoryEntry;
import com.niuqu.chatbubble.network.MediaResponsePayload;
import com.niuqu.chatbubble.network.MediaUploadAckPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.util.UUID;

/**
 * Server-side media hosting business logic (rate limit, chunked download,
 * chunked upload session) plus the 2.5.0 M9 group-media authorization gates.
 * The payload classes keep only codecs and ChatBubbleMod routes into here.
 *
 * <p>The authorization model is the frozen UUID {@link MediaBinding} (§13 D6):
 * a group image is stored as a {@code g_}-prefixed private file whose binding
 * names the owning group <em>UUID</em> and the message sequence that references
 * it. A download is allowed only through the group rules
 * ({@link GroupManager#isMemberById}/{@link GroupManager#canReadGroupSequence}),
 * so a non-member fetch is refused with the same sentinel a missing file uses.
 */
public final class MediaService {
    private MediaService() {}

    /** Client requests a download: rate-limit check, media authorization, chunked reply. */
    public static void handleRequest(ServerPlayerEntity sender, DiskMediaStore store, String mediaId) {
        if (store == null) {
            sendNotFound(sender, mediaId);
            return;
        }
        if (!store.allowTransfer(sender.getName().getString())) {
            sendNotFound(sender, mediaId);
            return;
        }
        if (!authorizeDownload(sender.getUuid(), store, mediaId)) {
            // Same sentinel as a missing file: a non-member must not learn whether
            // the media exists.
            sendNotFound(sender, mediaId);
            return;
        }
        MediaStream stream = store.openDownload(mediaId);
        if (stream == null) {
            sendNotFound(sender, mediaId);
            return;
        }
        try (stream) {
            int total = stream.totalChunks();
            for (int i = 0; i < total; i++) {
                byte[] chunk = stream.nextChunk();
                if (chunk == null) {
                    sendNotFound(sender, mediaId);
                    return;
                }
                ServerPlayNetworking.send(sender, new MediaResponsePayload(mediaId, i, total, chunk));
            }
        }
    }

    /**
     * A public (32-hex) file with no binding keeps the old semantics: anyone who
     * saw the URL may fetch it. A private ({@code g_}-prefixed) file always
     * requires its binding, so a missing or broken binding is refused rather than
     * treated as public. A bound file is readable only through the group rules:
     * a committed file by members whose join window covers its sequence, a
     * pending one by its uploader or a current member. No op/owner bypass.
     */
    static boolean authorizeDownload(UUID playerId, DiskMediaStore store, String mediaId) {
        if (store == null || !DiskMediaStore.isStoredMediaId(mediaId)) return false;
        MediaBinding binding = store.binding(mediaId);
        if (binding == null) return DiskMediaStore.isPublicMediaId(mediaId);
        if (binding.pending()) {
            return binding.uploaderId().equals(playerId)
                || GroupManager.isMemberById(playerId, binding.groupId());
        }
        return GroupManager.canReadGroupSequence(playerId, binding.groupId(), binding.sequence());
    }

    private static void sendNotFound(ServerPlayerEntity sender, String mediaId) {
        // not-found sentinel: MediaResponsePayload(mediaId, 0, 1, empty)
        ServerPlayNetworking.send(sender, new MediaResponsePayload(mediaId, 0, 1, new byte[0]));
    }

    // ==== group upload / message seams (2.5.0 M9) ====

    /**
     * Register the group context for a real connection UUID + uploadId before
     * its first chunk. Refused contexts are remembered as denied so the later
     * chunks can never fall through to an ordinary public save.
     */
    public static void prepareGroupUploadContext(ServerPlayerEntity sender, UUID groupId, long uploadId,
                                                 DiskMediaStore store) {
        if (store == null) return;
        if (groupId == null || !GroupManager.isMemberById(sender.getUuid(), groupId)) {
            store.refuseUpload(uploadId);
            return;
        }
        store.noteGroupUpload(uploadId, sender.getName().getString(), groupId, sender.getUuid());
    }

    /**
     * Reject a group message whose hosted media belongs to a different group.
     * Public/legacy media ids and external links keep their ordinary semantics;
     * only a binding that names another group is refused, which is what stops a
     * member smuggling a foreign group's private file into this group's history.
     */
    static boolean validateGroupMessage(UUID senderId, UUID groupId, String content, DiskMediaStore store) {
        if (groupId == null || !GroupManager.isMemberById(senderId, groupId)) return false;
        if (content == null || content.isEmpty()) return true;
        if (store == null) return true;
        for (String mediaId : DiskMediaStore.hostedMediaIds(content)) {
            MediaBinding binding = store.binding(mediaId);
            if (binding != null && !groupId.equals(binding.groupId())) return false;
        }
        return true;
    }

    public static boolean validateGroupMessage(ServerPlayerEntity sender, UUID groupId, String content,
                                               DiskMediaStore store) {
        return validateGroupMessage(sender.getUuid(), groupId, content, store);
    }

    /**
     * After a durable group row exists: bind each referenced pending file to that
     * row's sequence. Called by the group say path; when the binding cannot be
     * persisted the exception surfaces instead of a silent success.
     */
    public static void onGroupMessageCommitted(GroupHistoryEntry entry, DiskMediaStore store)
            throws IOException {
        if (store == null || entry == null) return;
        for (String mediaId : DiskMediaStore.hostedMediaIds(entry.content())) {
            MediaBinding binding = store.binding(mediaId);
            if (binding != null && binding.pending() && entry.groupId().equals(binding.groupId())) {
                store.commitBinding(mediaId, entry.sequence());
            }
        }
    }

    /** Group dissolved: delete its hosted files so they can never be read again. */
    public static void onGroupDeleted(UUID groupId, DiskMediaStore store) {
        if (groupId == null || store == null) return;
        store.deleteGroupMedia(groupId);
    }

    /**
     * One chunk of an ordinary PUBLIC upload (the pre-2.5.0 main channel). A chunk
     * whose uploadId carries a group context — or a refusal — is not a public
     * upload at all: it is rejected, so the main channel only ever stores
     * genuinely public files.
     */
    public static void handleUpload(ServerPlayerEntity sender, DiskMediaStore store, boolean mediaEnabled,
                                    boolean autoClean, long uploadId, int index, int totalChunks,
                                    int totalBytes, String contentType, byte[] chunk) {
        if (!mediaEnabled) {
            ack(sender, uploadId, null, "disabled");
            return;
        }
        if (store == null) {
            ack(sender, uploadId, null, "io");
            return;
        }
        String result;
        if (index == 0) {
            if (store.groupUploadScope(uploadId) != null || store.isUploadDenied(uploadId)) {
                store.refuseUpload(uploadId);
                ack(sender, uploadId, null, "group denied");
                return;
            }
            if (!store.allowTransfer(sender.getName().getString())) {
                ack(sender, uploadId, null, "rate limited");
                return;
            }
            result = store.beginUpload(uploadId, sender.getName().getString(),
                totalChunks, totalBytes, contentType);
            if (DiskMediaStore.IN_PROGRESS.equals(result)) return;
            if (result == null) {
                // Chunk 0 also carries data — feed it through acceptChunk so a
                // single-chunk upload completes (and acks) instead of hanging.
                result = store.acceptChunk(uploadId, index, chunk);
            }
        } else {
            result = store.acceptChunk(uploadId, index, chunk);
        }
        if (result == null) return; // upload still in progress
        store.discardUpload(uploadId);
        if (DiskMediaStore.isPublicMediaId(result) && autoClean)
            store.cleanupExpiredThrottled();
        ack(sender, uploadId,
            DiskMediaStore.isPublicMediaId(result) ? result : null,
            DiskMediaStore.isPublicMediaId(result) ? null : result);
    }

    /**
     * One chunk of a GROUP upload (the dedicated group payload). Group mode is
     * forced: without a live, authorized upload scope for this connection UUID +
     * uploadId the chunk is refused and the uploadId is marked denied, so a
     * missing, expired or refused context can never become a public file.
     */
    public static void handleGroupUpload(ServerPlayerEntity sender, DiskMediaStore store,
                                         boolean mediaEnabled, boolean autoClean, long uploadId,
                                         int index, int totalChunks, int totalBytes,
                                         String contentType, byte[] chunk) {
        if (!mediaEnabled) {
            ack(sender, uploadId, null, "disabled");
            return;
        }
        if (store == null) {
            ack(sender, uploadId, null, "group denied");
            return;
        }
        String result;
        if (index == 0) {
            DiskMediaStore.UploadScopeInfo scope = store.groupUploadScope(uploadId);
            if (scope == null || !scope.uploaderId().equals(sender.getUuid())
                    || !GroupManager.isMemberById(sender.getUuid(), scope.groupId())) {
                // No context, wrong connection, or the member left/expired between
                // the context and the first chunk: refuse, never store.
                store.refuseUpload(uploadId);
                ack(sender, uploadId, null, "group denied");
                return;
            }
            if (!store.allowTransfer(sender.getName().getString())) {
                // The scope is dropped with the refusal: a retry cannot re-open it.
                store.refuseUpload(uploadId);
                ack(sender, uploadId, null, "rate limited");
                return;
            }
            result = store.beginUpload(uploadId, sender.getName().getString(),
                totalChunks, totalBytes, contentType);
            if (DiskMediaStore.IN_PROGRESS.equals(result)) return;
            if (result == null) {
                result = store.acceptChunk(uploadId, index, chunk);
            } else if (!DiskMediaStore.isPrivateMediaId(result)) {
                // beginUpload failed after the scope was consumed (quota, cap,
                // bad plan, io): make sure this uploadId can never later become a
                // public file instead.
                store.refuseUpload(uploadId);
            }
        } else {
            result = store.acceptChunk(uploadId, index, chunk);
        }
        if (result == null) return; // upload still in progress
        store.discardUpload(uploadId);
        if (DiskMediaStore.isPrivateMediaId(result) && autoClean)
            store.cleanupExpiredThrottled();
        ack(sender, uploadId,
            DiskMediaStore.isPrivateMediaId(result) ? result : null,
            DiskMediaStore.isPrivateMediaId(result) ? null : result);
    }

    private static void ack(ServerPlayerEntity sender, long uploadId, String mediaId, String error) {
        ServerPlayNetworking.send(sender, new MediaUploadAckPayload(uploadId, mediaId, error));
    }
}
