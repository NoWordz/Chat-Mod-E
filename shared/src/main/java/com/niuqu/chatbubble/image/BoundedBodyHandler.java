package com.niuqu.chatbubble.image;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Collects successful HTTP response bodies only while they fit a byte budget. */
public final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private final long maxBytes;

    public BoundedBodyHandler(long maxBytes) {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be non-negative");
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
        int status = responseInfo.statusCode();
        if (status < 200 || status >= 300) {
            return new EmptyResponseSubscriber();
        }
        return new BoundedByteArraySubscriber(maxBytes);
    }

    /** Cancels an error response body while leaving its already-received status available. */
    private static final class EmptyResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
            body.complete(new byte[0]);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    private static final class BoundedByteArraySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long maxBytes;
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long receivedBytes;
        private boolean terminal;

        private BoundedByteArraySubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null || terminal) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (terminal) return;

            long batchBytes = 0;
            for (ByteBuffer buffer : item) {
                long remaining = buffer.remaining();
                if (remaining > maxBytes - receivedBytes - batchBytes) {
                    fail(new IOException("response body exceeds " + maxBytes + " bytes"));
                    return;
                }
                batchBytes += remaining;
            }

            receivedBytes += batchBytes;
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            if (terminal) return;
            terminal = true;
            delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (terminal) return;
            terminal = true;
            delegate.onComplete();
        }

        private void fail(Throwable throwable) {
            terminal = true;
            if (subscription != null) subscription.cancel();
            delegate.onError(throwable);
        }
    }
}
