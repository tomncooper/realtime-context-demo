package com.smartship.api;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for querying Kafka Streams state stores via Interactive Queries.
 */
@ApplicationScoped
public class KafkaStreamsQueryService {

    private static final Logger LOG = Logger.getLogger(KafkaStreamsQueryService.class);

    @ConfigProperty(name = "streams-processor.url")
    String streamsProcessorUrl;

    private final Client client;

    public KafkaStreamsQueryService() {
        this.client = ClientBuilder.newClient();
    }

    /**
     * Get shipment count for a specific status.
     *
     * @param status Shipment status (CREATED, IN_TRANSIT, DELIVERED)
     * @return Count of shipments with that status
     */
    public Long getShipmentCountByStatus(String status) {
        String url = streamsProcessorUrl + "/state/active-shipments-by-status/" + status;
        LOG.debugf("Querying: %s", url);

        try {
            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            return json.getJsonNumber("count").longValue();

        } catch (Exception e) {
            LOG.errorf(e, "Error querying shipment count for status: %s", status);
            throw new RuntimeException("Failed to query Kafka Streams state store", e);
        }
    }

    /**
     * Get counts for all shipment statuses.
     *
     * @return Map of status -> count
     */
    public Map<String, Object> getAllStatusCounts() {
        String url = streamsProcessorUrl + "/state/active-shipments-by-status";
        LOG.debugf("Querying: %s", url);

        try {
            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            Map<String, Object> counts = new HashMap<>();
            for (String key : json.keySet()) {
                JsonNumber value = json.getJsonNumber(key);
                counts.put(key, value != null ? value.longValue() : 0L);
            }

            return counts;

        } catch (Exception e) {
            LOG.error("Error querying all status counts", e);
            throw new RuntimeException("Failed to query Kafka Streams state store", e);
        }
    }
}
