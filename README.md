# Artemis Prometheus Metrics Plugin

An [Apache ActiveMQ Artemis](https://activemq.apache.org/components/artemis/) plugin that exposes broker and JVM metrics in Prometheus format via a dedicated HTTP/HTTPS endpoint.

## Features

- **Broker metrics** — queue depth, consumer count, message rates, address memory usage, connection count, and more — provided automatically by Artemis core
- **JVM metrics** — heap/non-heap memory, GC pauses, thread states, CPU usage
- **Dedicated HTTP server** — runs on a separate port (default `9404`), independent of the Artemis web console
- **HTTPS support** — configurable via keystore/truststore properties
- **Mutual TLS (mTLS)** — optional client certificate authentication
- **Custom response headers** — any `header.*` property becomes an HTTP response header
- **OpenMetrics content negotiation** — responds with OpenMetrics format if the scraper requests it
- **Zero Jetty dependency** — uses Java's built-in `HttpServer`/`HttpsServer` to avoid version conflicts with the Artemis-internal Jetty

## Compatibility

| Component | Version |
|---|---|
| Apache ActiveMQ Artemis | 2.53.0+ |
| Java | 17+ (tested on 21) |
| Micrometer | 1.13+ (tested on 1.16.3) |

## Installation

1. Download the latest JAR from the [Releases](../../releases/latest) page and copy it into the broker's `lib` directory:

```bash
curl -L -o /path/to/broker/lib/artemis-prometheus-plugin.jar \
  https://github.com/Lpapi/artemis-prometheus-plugin/releases/latest/download/artemis-prometheus-plugin-<version>.jar
```

2. Edit `broker/etc/broker.xml` — add or replace the `<metrics>` block inside `<core>`:

```xml
<metrics>
    <jvm-memory>true</jvm-memory>
    <jvm-gc>true</jvm-gc>
    <jvm-threads>true</jvm-threads>
    <plugin class-name="hu.lpapi.artemis.metrics.PrometheusMetricsPlugin">
        <property key="port" value="9404"/>
        <property key="host" value="0.0.0.0"/>
    </plugin>
</metrics>
```

3. Restart the broker:

```bash
./broker/bin/artemis run
```

4. Verify:

```bash
curl http://localhost:9404/metrics
```

### Building from source

If you prefer to build the JAR yourself (requires JDK 17+, Maven 3.6+):

```bash
git clone https://github.com/Lpapi/artemis-prometheus-plugin.git
cd artemis-prometheus-plugin
mvn clean package
cp target/artemis-prometheus-plugin-*.jar /path/to/broker/lib/
```

## Configuration Reference

All properties are set as `<property key="..." value="..."/>` elements inside the `<plugin>` block.

### General

| Property | Default | Description |
|---|---|---|
| `port` | `9404` | HTTP/HTTPS listening port |
| `host` | `0.0.0.0` | Bind address |
| `threads` | `2` | HTTP server thread pool size |

### TLS / HTTPS

| Property | Default | Description |
|---|---|---|
| `tls.enabled` | `false` | Enable HTTPS |
| `tls.keystore.path` | — | Path to keystore file (required when TLS is enabled) |
| `tls.keystore.password` | `""` | Keystore password |
| `tls.keystore.type` | `PKCS12` | Keystore type (`PKCS12` or `JKS`) |
| `tls.truststore.path` | — | Path to truststore file (optional, required for mTLS) |
| `tls.truststore.password` | `""` | Truststore password |
| `tls.truststore.type` | `PKCS12` | Truststore type (`PKCS12` or `JKS`) |
| `tls.client.auth` | `NONE` | Client certificate requirement: `NONE`, `WANT`, `REQUIRED` |

### Custom Response Headers

Any property whose key starts with `header.` is added as an HTTP response header. The prefix is stripped.

| Property | Example value | Resulting header |
|---|---|---|
| `header.X-Metrics-Source` | `artemis-broker` | `X-Metrics-Source: artemis-broker` |
| `header.Cache-Control` | `no-cache` | `Cache-Control: no-cache` |

## Configuration Examples

### Plain HTTP (minimal)

```xml
<metrics>
    <jvm-memory>true</jvm-memory>
    <jvm-gc>true</jvm-gc>
    <jvm-threads>true</jvm-threads>
    <plugin class-name="hu.lpapi.artemis.metrics.PrometheusMetricsPlugin">
        <property key="port" value="9404"/>
    </plugin>
</metrics>
```

### HTTPS

First, generate a keystore (self-signed, for testing):

```bash
keytool -genkeypair -alias artemis-metrics \
  -keyalg RSA -keysize 2048 -validity 730 \
  -storetype PKCS12 -keystore /etc/artemis/certs/keystore.p12 \
  -storepass changeit \
  -dname "CN=localhost, O=MyOrg, C=HU"
```

Then configure the plugin:

```xml
<plugin class-name="hu.lpapi.artemis.metrics.PrometheusMetricsPlugin">
    <property key="port" value="9404"/>
    <property key="tls.enabled" value="true"/>
    <property key="tls.keystore.path" value="/etc/artemis/certs/keystore.p12"/>
    <property key="tls.keystore.password" value="changeit"/>
    <property key="tls.keystore.type" value="PKCS12"/>
</plugin>
```

### Mutual TLS (mTLS)

```xml
<plugin class-name="hu.lpapi.artemis.metrics.PrometheusMetricsPlugin">
    <property key="port" value="9404"/>
    <property key="tls.enabled" value="true"/>
    <property key="tls.keystore.path" value="/etc/artemis/certs/keystore.p12"/>
    <property key="tls.keystore.password" value="changeit"/>
    <property key="tls.truststore.path" value="/etc/artemis/certs/truststore.p12"/>
    <property key="tls.truststore.password" value="changeit"/>
    <property key="tls.client.auth" value="REQUIRED"/>
</plugin>
```

### Custom headers

```xml
<plugin class-name="hu.lpapi.artemis.metrics.PrometheusMetricsPlugin">
    <property key="port" value="9404"/>
    <property key="header.X-Metrics-Source" value="artemis-broker"/>
    <property key="header.Cache-Control" value="no-cache"/>
</plugin>
```

## Prometheus Scrape Configuration

### Plain HTTP

```yaml
scrape_configs:
  - job_name: 'artemis'
    static_configs:
      - targets: ['localhost:9404']
```

### HTTPS with self-signed certificate

```yaml
scrape_configs:
  - job_name: 'artemis'
    scheme: https
    tls_config:
      ca_file: /etc/prometheus/certs/ca.pem
      # For testing only — do not use in production:
      # insecure_skip_verify: true
    static_configs:
      - targets: ['localhost:9404']
```

### HTTPS with mTLS

```yaml
scrape_configs:
  - job_name: 'artemis'
    scheme: https
    tls_config:
      ca_file: /etc/prometheus/certs/ca.pem
      cert_file: /etc/prometheus/certs/prometheus-client.pem
      key_file: /etc/prometheus/certs/prometheus-client-key.pem
    static_configs:
      - targets: ['localhost:9404']
```

## Available Metrics

### Artemis Broker Metrics (provided by Artemis core)

| Metric | Description |
|---|---|
| `artemis_active` | Whether the broker is active |
| `artemis_connection_count` | Number of connected clients |
| `artemis_address_memory_usage` | Memory used by all addresses |
| `artemis_address_size` | Estimated bytes used per address |
| `artemis_message_count` | Number of messages per queue |
| `artemis_consumer_count` | Number of consumers per queue |
| `artemis_delivering_message_count` | Messages currently being delivered |
| `artemis_messages_added` | Total messages added per queue |
| `artemis_messages_acknowledged` | Total messages acknowledged per queue |
| `artemis_messages_expired` | Total expired messages per queue |
| `artemis_authentication_count` | Authentication attempts (success/failure) |
| `artemis_authorization_count` | Authorization attempts (success/failure) |

### JVM Metrics (provided by Micrometer)

| Metric group | Description |
|---|---|
| `jvm_memory_*` | Heap and non-heap memory usage |
| `jvm_gc_*` | GC pause times, counts, memory promoted/allocated |
| `jvm_threads_*` | Thread count by state |
| `jvm_buffer_*` | Direct and mapped buffer pool usage |
| `process_cpu_*` | Process CPU usage |

## Architecture

```
Artemis broker
  └── PrometheusMetricsPlugin  (ActiveMQMetricsPlugin)
        ├── PrometheusMeterRegistry  (Micrometer → Prometheus format bridge)
        │     ├── Artemis core auto-registers broker metrics here
        │     └── JVM metrics bound on startup (memory, GC, threads, CPU)
        └── MetricsHttpServer  (Java built-in HttpServer / HttpsServer)
              └── GET /metrics  →  Prometheus text format (or OpenMetrics)
```

The plugin uses Java's built-in `com.sun.net.httpserver` package instead of Jetty, so there is no dependency conflict with the Artemis-internal Jetty 12 server.
