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

    @Override public void close() { transport.shutdownNow(); }

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
            if (status != 200) fail(service + ": ошибка HTTP " + status + ".");
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
            body.completeExceptionally(new SafeFailure(message));
            subscription.cancel();
        }
    }

    private static final class SafeFailure extends IOException {
        SafeFailure(String message) { super(message); }
    }
}
