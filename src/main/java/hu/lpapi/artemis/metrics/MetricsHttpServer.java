package hu.lpapi.artemis.metrics;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Önálló HTTP/HTTPS szerver a Prometheus /metrics endpointhoz.
 * Java beépített HttpServer-t használ — nincs Jetty függőség, nincs verziókonfliktus.
 *
 * Konfigurációs kulcsok (broker.xml <property>):
 *   port                    — figyelő port (alapértelmezett: 9404)
 *   host                    — bind cím (alapértelmezett: 0.0.0.0)
 *   threads                 — thread pool mérete (alapértelmezett: 2)
 *   tls.enabled             — HTTPS bekapcsolása (alapértelmezett: false)
 *   tls.keystore.path       — keystore fájl elérési útja
 *   tls.keystore.password   — keystore jelszó
 *   tls.keystore.type       — keystore típus (alapértelmezett: PKCS12)
 *   tls.truststore.path     — truststore fájl (opcionális, mTLS-hez)
 *   tls.truststore.password — truststore jelszó
 *   tls.truststore.type     — truststore típus (alapértelmezett: PKCS12)
 *   tls.client.auth         — NONE | WANT | REQUIRED (alapértelmezett: NONE)
 *   header.<név>            — egyedi response header, pl. header.X-Source=artemis
 */
public class MetricsHttpServer {

    private static final String CONTENT_TYPE_OPENMETRICS = "application/openmetrics-text";
    private static final String CONTENT_TYPE_TEXT        = "text/plain; version=0.0.4; charset=utf-8";
    private static final String CONTENT_TYPE_OM_FULL     = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private final HttpServer server;

    public MetricsHttpServer(Map<String, String> options, PrometheusMeterRegistry registry) throws IOException, GeneralSecurityException {
        int port    = Integer.parseInt(options.getOrDefault("port", "9404"));
        String host = options.getOrDefault("host", "0.0.0.0");
        int threads = Integer.parseInt(options.getOrDefault("threads", "2"));
        boolean tlsEnabled = Boolean.parseBoolean(options.getOrDefault("tls.enabled", "false"));

        Map<String, String> customHeaders = options.entrySet().stream()
            .filter(e -> e.getKey().startsWith("header."))
            .collect(Collectors.toMap(
                e -> e.getKey().substring("header.".length()),
                Map.Entry::getValue
            ));

        InetSocketAddress address = new InetSocketAddress(host, port);

        if (tlsEnabled) {
            SSLContext sslContext = buildSslContext(options);
            String clientAuth = options.getOrDefault("tls.client.auth", "NONE").toUpperCase();
            HttpsServer httpsServer = HttpsServer.create(address, 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters sslParams = getSSLContext().getDefaultSSLParameters();
                    sslParams.setNeedClientAuth("REQUIRED".equals(clientAuth));
                    sslParams.setWantClientAuth("WANT".equals(clientAuth));
                    params.setSSLParameters(sslParams);
                }
            });
            server = httpsServer;
        } else {
            server = HttpServer.create(address, 0);
        }

        server.createContext("/metrics", exchange -> {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String accept = exchange.getRequestHeaders().getFirst("Accept");
            boolean wantsOpenMetrics = accept != null && accept.contains(CONTENT_TYPE_OPENMETRICS);

            String responseBody = wantsOpenMetrics
                ? registry.scrape(CONTENT_TYPE_OM_FULL)
                : registry.scrape();

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);

            Headers responseHeaders = exchange.getResponseHeaders();
            responseHeaders.set("Content-Type", wantsOpenMetrics ? CONTENT_TYPE_OM_FULL : CONTENT_TYPE_TEXT);
            customHeaders.forEach(responseHeaders::set);

            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.setExecutor(Executors.newFixedThreadPool(threads));
    }

    private SSLContext buildSslContext(Map<String, String> options) throws IOException, GeneralSecurityException {
        String keystorePath     = options.get("tls.keystore.path");
        String keystorePassword = options.getOrDefault("tls.keystore.password", "");
        String keystoreType     = options.getOrDefault("tls.keystore.type", "PKCS12");

        if (keystorePath == null || keystorePath.isBlank()) {
            throw new IllegalArgumentException("tls.enabled=true esetén tls.keystore.path megadása kötelező");
        }

        KeyStore keyStore = KeyStore.getInstance(keystoreType);
        try (InputStream is = new FileInputStream(keystorePath)) {
            keyStore.load(is, keystorePassword.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, keystorePassword.toCharArray());

        TrustManagerFactory tmf = null;
        String truststorePath = options.get("tls.truststore.path");
        if (truststorePath != null && !truststorePath.isBlank()) {
            String truststorePassword = options.getOrDefault("tls.truststore.password", "");
            String truststoreType     = options.getOrDefault("tls.truststore.type", "PKCS12");
            KeyStore trustStore = KeyStore.getInstance(truststoreType);
            try (InputStream is = new FileInputStream(truststorePath)) {
                trustStore.load(is, truststorePassword.toCharArray());
            }
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(
            kmf.getKeyManagers(),
            tmf != null ? tmf.getTrustManagers() : null,
            null
        );
        return sslContext;
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }
}
