package hu.lpapi.artemis.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.activemq.artemis.core.server.metrics.ActiveMQMetricsPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

public class PrometheusMetricsPlugin implements ActiveMQMetricsPlugin {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsPlugin.class);

    private transient PrometheusMeterRegistry registry;
    private transient MetricsHttpServer httpServer;
    // S2095: JvmGcMetrics is AutoCloseable — field-ben tartjuk, stop()-ban zárjuk
    private transient JvmGcMetrics jvmGcMetrics;

    @Override
    public ActiveMQMetricsPlugin init(Map<String, String> options) {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

        new JvmMemoryMetrics().bindTo(registry);
        jvmGcMetrics = new JvmGcMetrics();
        jvmGcMetrics.bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        try {
            httpServer = new MetricsHttpServer(options, registry);
            httpServer.start();
            int port = Integer.parseInt(options.getOrDefault("port", "9404"));
            boolean tls = Boolean.parseBoolean(options.getOrDefault("tls.enabled", "false"));
            log.info("[PrometheusMetricsPlugin] /metrics elérhető: {}://0.0.0.0:{}/metrics",
                tls ? "https" : "http", port);
        } catch (IOException | GeneralSecurityException e) {
            jvmGcMetrics.close();
            throw new IllegalStateException("Prometheus metrics HTTP szerver indítása sikertelen", e);
        }

        return this;
    }

    @Override
    public MeterRegistry getRegistry() {
        return registry;
    }

    public void stop() {
        if (jvmGcMetrics != null) jvmGcMetrics.close();
        if (httpServer != null) httpServer.stop();
        if (registry != null) registry.close();
    }
}
