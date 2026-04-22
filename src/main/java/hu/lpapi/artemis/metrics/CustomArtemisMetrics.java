package hu.lpapi.artemis.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.postoffice.QueueBinding;
import org.apache.activemq.artemis.core.security.SecurityAuth;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.Queue;
import org.apache.activemq.artemis.core.server.plugin.ActiveMQServerPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Stream;

public class CustomArtemisMetrics implements ActiveMQServerPlugin {

    private static final Logger log = LoggerFactory.getLogger(CustomArtemisMetrics.class);

    private ActiveMQServer server;
    private MeterRegistry registry;

    @Override
    public void registered(ActiveMQServer server) {
        this.server = server;
        this.registry = RegistryHolder.get();

        if (registry == null) {
            log.warn("[CustomArtemisMetrics] Registry not available — PrometheusMetricsPlugin must be initialized first");
            return;
        }

        allQueues().forEach(this::registerScheduledGauge);

        // Slow consumer: consumers present, messages waiting, but nothing in-flight
        Gauge.builder("artemis_slow_consumer_count", server, s ->
            allQueues()
                .filter(q -> q.getConsumerCount() > 0)
                .filter(q -> q.getMessageCount() > 0)
                .filter(q -> q.getDeliveringCount() == 0)
                .count()
        )
        .description("Queues with consumers present but delivery stalled")
        .register(registry);

        log.info("[CustomArtemisMetrics] Custom metrics registered");
    }

    @Override
    public void afterCreateQueue(Queue queue) throws ActiveMQException {
        if (registry != null) registerScheduledGauge(queue);
    }

    @Override
    public void afterDestroyQueue(Queue queue, SimpleString address, SecurityAuth session,
                                  boolean checkConsumerCount, boolean removeConsumers,
                                  boolean autoDeleteAddress) throws ActiveMQException {
        if (registry == null) return;
        String queueName = queue.getName().toString();
        registry.find("artemis_scheduled_message_count")
            .tag("queue", queueName)
            .gauges()
            .forEach(registry::remove);
    }

    private void registerScheduledGauge(Queue queue) {
        String queueName = queue.getName().toString();
        String addressName = queue.getAddress().toString();
        // Use server.locateQueue() so a deleted queue safely returns null instead of stale data
        Gauge.builder("artemis_scheduled_message_count", server,
                s -> {
                    Queue q = s.locateQueue(queueName);
                    return q != null ? (double) q.getScheduledCount() : 0.0;
                })
            .description("Number of scheduled (delayed) messages per queue")
            .tag("queue", queueName)
            .tag("address", addressName)
            .register(registry);
    }

    private Stream<Queue> allQueues() {
        return server.getPostOffice().getAddresses().stream()
            .flatMap(addr -> {
                try {
                    return server.getPostOffice().getBindingsForAddress(addr).getBindings().stream();
                } catch (Exception e) {
                    return Stream.empty();
                }
            })
            .filter(QueueBinding.class::isInstance)
            .map(QueueBinding.class::cast)
            .map(QueueBinding::getQueue);
    }
}
