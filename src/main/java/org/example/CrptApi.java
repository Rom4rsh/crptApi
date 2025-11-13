package org.example;// CrptApi.java
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CrptApi — минимальный thread-safe клиент для API "Честного знака".
 *
 * Основные требования (реализовано):
 * - public CrptApi(TimeUnit timeUnit, int requestLimit)
 * - Блокировка при превышении лимита, пока выполнение нельзя продолжить.
 * - Метод создания документа: createDocument(Object document, String signature)
 * - Все вспомогательные классы — внутренние.
 * - Java 11 HttpClient + Jackson ObjectMapper.
 *
 * Примечание: класс не выполняет получение токена по УКЭП — токен устанавливается методом setAuthToken().
 */
public class CrptApi {

    private static final String DEFAULT_BASE_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private static final String ACCEPT_HEADER = "*/*";
    private static final String CONTENT_TYPE = "application/json;charset=UTF-8";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final long windowMillis; // one unit in ms

    // sliding-window timestamps (milliseconds). oldest at head
    private final Deque<Long> timestamps = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition notFull = lock.newCondition();

    // runtime config
    private volatile String baseUrl = DEFAULT_BASE_URL;
    private volatile String authToken = null; // plain token (without "Bearer ")

    /**
     * Конструктор.
     *
     * @param timeUnit     unit of time (e.g. TimeUnit.SECONDS)
     * @param requestLimit positive number of allowed requests per timeUnit
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (timeUnit == null) throw new IllegalArgumentException("timeUnit cannot be null");
        if (requestLimit <= 0) throw new IllegalArgumentException("requestLimit must be > 0");

        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.windowMillis = timeUnit.toMillis(1);

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();

        this.objectMapper = new ObjectMapper();
        this.objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * Установить базовый URL (например демонстрационный стенд).
     *
     * @param baseUrl полный URL метода создания документа
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl cannot be null");
    }

    /**
     * Установить токен авторизации (Bearer).
     *
     * @param token токен (без префикса "Bearer ")
     */
    public void setAuthToken(String token) {
        this.authToken = token;
    }

    /**
     * Создать документ (метод из ТЗ).
     *
     * @param document  Java объект документа (будет сериализован в JSON)
     * @param signature подпись в виде строки (обычно base64)
     * @return ApiResponse (status + body)
     * @throws IOException          при ошибках ввода/вывода или сериализации
     * @throws InterruptedException если поток был прерван во время ожидания/запроса
     */
    public ApiResponse createDocument(Object document, String signature) throws IOException, InterruptedException {
        Objects.requireNonNull(document, "document cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");

        // Wait for permit according to rate limit (blocks if necessary)
        awaitPermit();

        // Build JSON body: { "document": <document>, "signature": "<signature>" }
        RequestBody body = new RequestBody(document, signature);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize document to JSON", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Accept", ACCEPT_HEADER)
                .header("Content-Type", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(json));

        String token = this.authToken;
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }

        HttpRequest request = builder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        return new ApiResponse(response.statusCode(), response.body());
    }

    // --- Rate limiter (sliding window). Blocks when limit reached. ---
    private void awaitPermit() throws InterruptedException {
        lock.lock();
        try {
            while (true) {
                long now = Instant.now().toEpochMilli();
                purgeOld(now);

                if (timestamps.size() < requestLimit) {
                    timestamps.addLast(now);
                    notFull.signalAll();
                    return;
                } else {
                    long oldest = timestamps.peekFirst();
                    long waitMillis = (oldest + windowMillis) - now;
                    if (waitMillis <= 0) {
                        // On next loop iteration purgeOld will remove expired
                        continue;
                    }
                    // Wait until oldest is out of window or a signal
                    notFull.awaitNanos(TimeUnit.MILLISECONDS.toNanos(waitMillis));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void purgeOld(long nowMillis) {
        while (!timestamps.isEmpty()) {
            long diff = nowMillis - timestamps.peekFirst();
            if (diff >= windowMillis) {
                timestamps.removeFirst();
            } else {
                break;
            }
        }
    }

    // --- Helper classes ---

    private static class RequestBody {
        public final Object document;
        public final String signature;

        RequestBody(Object document, String signature) {
            this.document = document;
            this.signature = signature;
        }
    }

    /**
     * Простой контейнер ответа.
     */
    public static class ApiResponse {
        private final int statusCode;
        private final String body;

        public ApiResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "ApiResponse{" +
                    "statusCode=" + statusCode +
                    ", body='" + body + '\'' +
                    '}';
        }
    }

    /**
     * Пример шаблона Document — для тестов. В реальности нужно соответствовать JSON-схеме API.
     */
    public static class Document {
        public String docType;
        public String ownerInn;
        public String producerInn;
        public String regNumber;
        // дополнительные поля по спецификации...

        public Document() {}

        public Document(String docType, String ownerInn, String producerInn, String regNumber) {
            this.docType = docType;
            this.ownerInn = ownerInn;
            this.producerInn = producerInn;
            this.regNumber = regNumber;
        }
    }

    /**
     * Доступ к ObjectMapper (удобно для тестов/десериализации ответов).
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    // --- Пример использования и простой многопоточный тест rate-limiter ---
    public static void main(String[] args) throws Exception {
        // Пример: 5 запросов в 1 секунду
        CrptApi client = new CrptApi(TimeUnit.SECONDS, 5);

        // Для теста используем демонстрационный URL, если нужно:
        // client.setBaseUrl("https://markirovka.demo.crpt.tech/api/v3/lk/documents/create");
        // клиент без токена — только демонстрация rate-limiter; не выполняет действительный API-вызов среды

        // Создадим простой документ
        Document doc = new Document("INTRODUCE_INTO_CIRCULATION", "1234567890", "1234567890", "reg-001");
        String fakeSignature = "BASE64_SIGNATURE_EXAMPLE";

        // Запускаем 10 потоков подряд — но только 5 запросов в секунду допустимы => остальные будут ждать
        int threads = 10;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            ts[i] = new Thread(() -> {
                try {
                    System.out.println("Thread " + idx + " sending...");
                    ApiResponse resp = client.createDocument(doc, fakeSignature);
                    System.out.println("Thread " + idx + " got: " + resp.getStatusCode());
                } catch (IOException e) {
                    System.err.println("Thread " + idx + " I/O error: " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("Thread " + idx + " interrupted");
                }
            });
            ts[i].start();
        }

        for (Thread t : ts) t.join();
        System.out.println("All done.");
    }
}

