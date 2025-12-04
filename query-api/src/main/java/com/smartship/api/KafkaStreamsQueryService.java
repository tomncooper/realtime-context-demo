package com.smartship.api;

import com.smartship.api.model.StreamsInstanceMetadata;
import com.smartship.api.services.StreamsInstanceDiscoveryService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Service for querying Kafka Streams state stores via Interactive Queries.
 * Supports distributed queries across multiple streams-processor instances.
 */
@ApplicationScoped
public class KafkaStreamsQueryService {

    private static final Logger LOG = Logger.getLogger(KafkaStreamsQueryService.class);
    private static final String STATE_STORE_NAME = "active-shipments-by-status";

    @Inject
    StreamsInstanceDiscoveryService discoveryService;

    private final Client client;
    private final ExecutorService executorService;

    public KafkaStreamsQueryService() {
        this.client = ClientBuilder.newClient();
        this.executorService = Executors.newFixedThreadPool(10);
    }

    /**
     * Get shipment count for a specific status.
     * Routes the query to the specific instance holding the data for this key.
     *
     * @param status Shipment status (CREATED, IN_TRANSIT, DELIVERED)
     * @return Count of shipments with that status
     */
    public Long getShipmentCountByStatus(String status) {
        LOG.debugf("Querying shipment count for status: %s", status);

        try {
            // Find the instance that hosts data for this key
            StreamsInstanceMetadata instance = discoveryService.findInstanceForKey(STATE_STORE_NAME, status);

            if (instance == null) {
                LOG.warnf("No instance found for status: %s", status);
                return 0L;
            }

            String url = instance.getUrl() + "/state/active-shipments-by-status/" + status;
            LOG.debugf("Querying instance: %s", url);

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
     * Queries all instances in parallel and aggregates the results.
     *
     * @return Map of status -> count
     */
    public Map<String, Object> getAllStatusCounts() {
        LOG.debug("Querying all status counts from all instances");

        try {
            // Discover all instances hosting the state store
            List<StreamsInstanceMetadata> instances = discoveryService.discoverInstances(STATE_STORE_NAME);

            if (instances.isEmpty()) {
                LOG.warn("No instances found for state store");
                return new HashMap<>();
            }

            LOG.infof("Querying %d instances in parallel", instances.size());

            // Query all instances in parallel
            List<CompletableFuture<Map<String, Long>>> futures = instances.stream()
                .map(instance -> CompletableFuture.supplyAsync(
                    () -> queryInstanceAllCounts(instance),
                    executorService
                ))
                .collect(Collectors.toList());

            // Wait for all queries to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Merge results by summing counts for each status
            Map<String, Long> mergedCounts = new HashMap<>();
            for (CompletableFuture<Map<String, Long>> future : futures) {
                Map<String, Long> instanceCounts = future.get();
                instanceCounts.forEach((status, count) ->
                    mergedCounts.merge(status, count, Long::sum)
                );
            }

            LOG.infof("Aggregated counts from %d instances: %s", instances.size(), mergedCounts);

            // Convert to Map<String, Object> for compatibility with existing API
            return new HashMap<>(mergedCounts);

        } catch (Exception e) {
            LOG.error("Error querying all status counts", e);
            throw new RuntimeException("Failed to query Kafka Streams state store", e);
        }
    }

    /**
     * Query a single instance for all status counts.
     * Handles failures gracefully by returning an empty map.
     *
     * @param instance Instance to query
     * @return Map of status -> count for this instance
     */
    private Map<String, Long> queryInstanceAllCounts(StreamsInstanceMetadata instance) {
        String url = instance.getUrl() + "/state/active-shipments-by-status";
        LOG.debugf("Querying instance: %s", url);

        try {
            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            Map<String, Long> counts = new HashMap<>();
            for (String key : json.keySet()) {
                JsonNumber value = json.getJsonNumber(key);
                counts.put(key, value != null ? value.longValue() : 0L);
            }

            LOG.debugf("Instance %s returned counts: %s", instance, counts);
            return counts;

        } catch (Exception e) {
            LOG.warnf("Failed to query instance %s: %s (continuing with other instances)", instance, e.getMessage());
            return new HashMap<>();  // Return empty map on failure
        }
    }
}
