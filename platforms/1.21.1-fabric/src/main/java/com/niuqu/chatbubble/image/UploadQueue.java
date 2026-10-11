package com.niuqu.chatbubble.image;

import com.niuqu.chatbubble.ChatBubbleClientSetup;
import java.io.File;
import java.util.ArrayDeque;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;

/**
 * Serial upload pipeline for images: queue + worker thread + server-first /
 * third-party fallback. UI side effects (toasts, input box, sending) are
 * reported through {@link Callbacks} on the main thread.
 *
 * <p>Each job carries the group it was composed for (captured when it is
 * enqueued, never read from the active conversation when the async result
 * lands). The hosting choice lives in {@link MediaUploadRouting}: a group job
 * goes to the group channel and has NO third-party fallback, so a private group
 * image can never be published as a public link (2.5.0 M9 / §13 D6).
 */
public final class UploadQueue {
    /** One queued upload; when pendingText is set the send is completed
     * automatically after the upload (draft semantics: the user hits enter
     * once, the message goes out when the file is up). {@code groupId} is the
     * composed group (null for public / private-message uploads). */
    public record UploadJob(File file, byte[] bytes, String fileName,
                            boolean emote, String pendingText, UUID groupId) {}

    public interface Callbacks {
        /** Upload started: show the busy indicator. */
        void onBusyStart(UUID groupId);
        /** Upload finished without error: clear the busy indicator. */
        void onIdle(UUID groupId);
        /** Upload failed (or nothing to upload): toast + restore draft. */
        void onFailure(UUID groupId);
        /**
         * Rejected before upload: the animated source exceeds what the receiver
         * can render. Carries which limit was broken so the toast can name it.
         */
        void onRejected(UUID groupId, AnimatedImageLoader.OverBudget reason);
        /** Emote upload done: send the emote message immediately. */
        void onEmoteSent(UUID groupId, String url);
        /** Draft send done: send the text with the real URL substituted. */
        void onSendText(UUID groupId, String text);
        /** Plain upload done: insert/replace the CICode in the input box. */
        void onInputImage(UUID groupId, String code);
        /** Restore the draft text after a failed send. */
        void onRestoreInput(UUID groupId, String text);
    }

    /** The three hosting paths, backed by this platform's transport. */
    private static final MediaUploadRouting.Host HOST = new MediaUploadRouting.Host() {
        @Override public String uploadPublic(byte[] bytes, String contentType) {
            return MediaClient.serverEnabled() ? MediaClient.upload(bytes, contentType) : null;
        }

        @Override public String uploadGroup(UUID groupId, byte[] bytes, String contentType) {
            return MediaClient.uploadGroup(groupId, bytes, contentType);
        }

        @Override public String uploadThirdParty(byte[] bytes, String fileName) {
            var cfg = ChatBubbleClientSetup.config();
            return ImageUploader.upload(bytes, fileName,
                cfg.uploadUrl(), cfg.uploadField(), cfg.uploadExtra(), cfg.uploadResponse());
        }
    };

    private static final int MAX_JOBS = 8;
    private final ArrayDeque<UploadJob> jobs = new ArrayDeque<>();
    private boolean running;
    private final Callbacks cb;

    public UploadQueue(Callbacks cb) {
        this.cb = cb;
    }

    public boolean enqueue(UploadJob job) {
        if (jobs.size() >= MAX_JOBS) return false;
        jobs.addLast(job);
        drain();
        return true;
    }

    public int pending() { return jobs.size(); }

    /** Runs queued uploads one at a time; the completion callback in
     * finish() calls this again for the next job. */
    private void drain() {
        if (running) return;
        UploadJob job = jobs.pollFirst();
        if (job == null) return;
        running = true;
        cb.onBusyStart(job.groupId());
        ImageLoader.executor().execute(() -> {
            try {
                com.mojang.logging.LogUtils.getLogger().info(
                    "[e33chat] upload start | file={} | emote={} | group={} | serverEnabled={}",
                    job.file() != null ? job.file().getName() : job.fileName(), job.emote(),
                    job.groupId(), MediaClient.serverEnabled());
                LocalImageSource.PreparedImage prep;
                if (job.file() != null) {
                    LocalImageSource.Prep rep = LocalImageSource.prepare(job.file());
                    if (rep instanceof LocalImageSource.Prep.Rejected rejected) {
                        // Refuse rather than downgrade: a silently re-encoded GIF
                        // arrives as a still frame and reads as "the animation broke".
                        com.mojang.logging.LogUtils.getLogger().info(
                            "[e33chat] upload rejected (animated over budget: {}) | file={}",
                            rejected.reason(), job.file().getName());
                        AnimatedImageLoader.OverBudget reason = rejected.reason();
                        MinecraftClient.getInstance().execute(() -> {
                            running = false;
                            cb.onRejected(job.groupId(), reason);
                            if (job.pendingText() != null) cb.onRestoreInput(job.groupId(), job.pendingText());
                            drain();
                        });
                        return;
                    }
                    prep = ((LocalImageSource.Prep.Ok) rep).image();
                } else {
                    prep = new LocalImageSource.PreparedImage(job.bytes(), job.fileName());
                }
                if (prep == null) {
                    MinecraftClient.getInstance().execute(() -> {
                        running = false;
                        cb.onFailure(job.groupId());
                        if (job.pendingText() != null) cb.onRestoreInput(job.groupId(), job.pendingText());
                        drain();
                    });
                    return;
                }
                finish(job, prep);
            } catch (Throwable t) {
                // Never let a worker crash leak into the queue: reset the latch so
                // queued jobs keep draining and the failure is visible.
                com.mojang.logging.LogUtils.getLogger().error("[e33chat] upload worker crashed", t);
                MinecraftClient.getInstance().execute(() -> {
                    running = false;
                    cb.onFailure(job.groupId());
                    if (job.pendingText() != null) cb.onRestoreInput(job.groupId(), job.pendingText());
                    drain();
                });
            }
        });
    }

    private void finish(UploadJob job, LocalImageSource.PreparedImage prep) {
        // The routing decision (group channel vs public, and the no-public-fallback
        // rule for group jobs) lives in MediaUploadRouting so the three targets
        // cannot drift; the Host above supplies this platform's transport and the
        // real content type keeps an untouched GIF from being announced as PNG.
        final String url = MediaUploadRouting.resolve(job.groupId(), prep.bytes(), prep.fileName(),
            prep.contentType(), HOST);
        com.mojang.logging.LogUtils.getLogger().info("[e33chat] upload {} -> {}", prep.fileName(), url == null ? "FAILED" : url);
        MinecraftClient.getInstance().execute(() -> {
            running = false;
            if (url == null) {
                cb.onFailure(job.groupId());
                // Failed send: restore the draft so the user sees what did not
                // go out.
                if (job.pendingText() != null) cb.onRestoreInput(job.groupId(), job.pendingText());
                drain();
                return;
            }
            cb.onIdle(job.groupId());
            // Carry the original file name: image hosts hand back extension-less
            // URLs, and the download side can only recognise an animation by
            // extension or content probe. "name=" is part of the CICode format,
            // so ChatImage and our own parser both read it.
            String nameAttr = "name=" + prep.fileName();
            if (job.emote()) {
                // Emote click = send immediately as a bubble-less emote message.
                cb.onEmoteSent(job.groupId(), "[[E33Emote,url=" + url + "," + nameAttr + "]]");
            } else if (job.pendingText() != null) {
                // Enter was pressed on a file:// CICode: finish the send now —
                // one enter, no second press, no lost message.
                String finalText = job.pendingText().replaceFirst(
                    "\\[\\[CICode,url=file://[^]]*]]", "[[CICode,url=" + url + "," + nameAttr + "]]");
                cb.onSendText(job.groupId(), finalText);
            } else {
                String code = "[[CICode,url=" + url + "," + nameAttr + "]]";
                cb.onInputImage(job.groupId(), code);
            }
            drain();
        });
    }
}
