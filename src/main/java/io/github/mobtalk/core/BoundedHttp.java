package io.github.mobtalk.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Finite body sizes and deadlines also cover a stalled response stream. */
final class BoundedHttp implements AutoCloseable {
    private final HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final int timeoutSeconds;

    BoundedHttp(MobTalkConfig config) {
        timeoutSeconds = Math.max(1, Math.min(90, config.requestTimeoutSeconds));
    }

    Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }

    byte[] send(HttpRequest request, int maxBytes, String service) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = transport.sendAsync(request, response -> new LimitedBody(response.statusCode(), maxBytes, service));
            return pending.get(timeoutSeconds, TimeUnit.SECONDS).body();
        } catch (TimeoutException timeout) {
            throw new IOException(service + ": превышено время ожидания.");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof SafeFailure safe) throw safe;
            throw new IOException(service + ": не удалось выполнить запрос.");
        } catch (RuntimeException failure) {
            throw new IOException(service + ": не удалось выполнить запрос.");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /** Returns once a 200 response starts; the body keeps flowing into the stream until it ends, fails, is closed or times out. */
    SpeechStream stream(HttpRequest request, int maxBytes, String service) throws IOException, InterruptedException {
        SpeechStream sink = new SpeechStream(maxBytes);
        CompletableFuture<Integer> headers = new CompletableFuture<>();
        CompletableFuture<HttpResponse<Void>> pending = transport.sendAsync(request, response -> {
            headers.complete(response.statusCode());
            return new StreamingBody(response.statusCode(), sink);
        });
        pending.whenComplete((ignored, failure) -> {
            sink.finish();
            headers.completeExceptionally(failure == null ? new IOException("No response") : failure);
        });
        int status;
        try {
            status = headers.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            pending.cancel(true); sink.close();
            throw new IOException(service + ": превышено время ожидания.");
        } catch (ExecutionException failure) {
            sink.close();
            throw new IOException(service + ": не удалось выполнить запрос.");
        }
        if (status != 200) { sink.close(); throw new SafeFailure(service + ": ошибка HTTP " + status + ".", status); }
        // A stalled body must not keep the connection open forever.
        CompletableFuture.delayedExecutor(timeoutSeconds, TimeUnit.SECONDS).execute(() -> { if (!sink.finished()) sink.close(); });
        return sink;
    }

    @Override public void close() { transport.shutdownNow(); }

    private static final class StreamingBody implements HttpResponse.BodySubscriber<Void> {
        private final CompletableFuture<Void> body = new CompletableFuture<>();
        private final int status;
        private final SpeechStream sink;
        private Flow.Subscription subscription;

        StreamingBody(int status, SpeechStream sink) { this.status = status; this.sink = sink; }
        @Override public CompletionStage<Void> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (status != 200) { subscription.cancel(); body.complete(null); return; }
            sink.onCancel(subscription::cancel);
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) if (!sink.append(buffer)) { subscription.cancel(); sink.finish(); body.complete(null); return; }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { sink.finish(); body.complete(null); }
        @Override public void onComplete() { sink.finish(); body.complete(null); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int status;
        private final int maxBytes;
        private final String service;
        private Flow.Subscription subscription;

        LimitedBody(int status, int maxBytes, String service) {
            this.status = status; this.maxBytes = maxBytes; this.service = service;
        }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (status != 200) fail(service + ": ошибка HTTP " + status + ".", status);
            else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maxBytes - output.size()) {
                    fail(service + ": ответ превышает допустимый размер.");
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) {
            body.completeExceptionally(new SafeFailure(service + ": соединение прервано."));
        }
        @Override public void onComplete() { body.complete(output.toByteArray()); }
        private void fail(String message) {
            fail(message, -1);
        }
        private void fail(String message, int status) {
            body.completeExceptionally(new SafeFailure(message, status));
            subscription.cancel();
        }
    }

    static final class SafeFailure extends IOException {
        final int status;
        SafeFailure(String message) { this(message, -1); }
        SafeFailure(String message, int status) { super(message); this.status = status; }
    }
}
