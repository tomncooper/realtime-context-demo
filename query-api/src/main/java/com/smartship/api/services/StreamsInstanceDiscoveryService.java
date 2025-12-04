package com.smartship.api.services;

import com.smartship.api.model.StreamsInstanceMetadata;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Service for discovering Kafka Streams instances and querying StreamsMetadata.
 * Implements caching to reduce overhead on the streams-processor instances.
 */
@ApplicationScoped
public class StreamsInstanceDiscoveryService {

    private static final Logger LOG = Logger.getLogger(StreamsInstanceDiscoveryService.class);

    @ConfigProperty(name = "streams-processor.headless-service")
    String headlessService;

    @ConfigProperty(name = "streams-processor.port")
    int port;

    private final Client client;
    private final Random random;

    public StreamsInstanceDiscoveryService() {
        this.client = ClientBuilder.newClient();
        this.random = new Random();
    }

    /**
     * Discover all instances hosting the specified state store.
     * Results are cached for 30 seconds.
     *
     * @param storeName Name of the state store
     * @return List of instances hosting the store
     */
    @CacheResult(cacheName = "streams-instances")
    public List<StreamsInstanceMetadata> discoverInstances(String storeName) {
        LOG.debugf("Discovering instances for store: %s", storeName);

        try {
            // Query any healthy instance for metadata about all instances
            String anyInstance = getAnyHealthyInstance();
            if (anyInstance == null) {
                LOG.error("No healthy streams-processor instances found");
                return new ArrayList<>();
            }

            String url = anyInstance + "/metadata/instances/" + storeName;
            LOG.debugf("Querying metadata from: %s", url);

            JsonArray jsonArray = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonArray.class);

            List<StreamsInstanceMetadata> instances = new ArrayList<>();
            for (JsonValue value : jsonArray) {
                JsonObject obj = value.asJsonObject();
                Set<String> storeNames = new HashSet<>();

                if (obj.containsKey("stateStoreNames")) {
                    JsonArray stores = obj.getJsonArray("stateStoreNames");
                    for (JsonValue storeName1 : stores) {
                        storeNames.add(storeName1.toString().replace("\"", ""));
                    }
                }

                StreamsInstanceMetadata metadata = new StreamsInstanceMetadata(
                    obj.getString("host"),
                    obj.getInt("port"),
                    storeNames
                );
                instances.add(metadata);
            }

            LOG.infof("Discovered %d instances for store: %s", instances.size(), storeName);
            return instances;

        } catch (Exception e) {
            LOG.errorf(e, "Error discovering instances for store: %s", storeName);
            return new ArrayList<>();
        }
    }

    /**
     * Find the specific instance hosting data for a given key.
     *
     * @param storeName Name of the state store
     * @param key The key to locate
     * @return Instance metadata for the instance hosting the key, or null if not found
     */
    public StreamsInstanceMetadata findInstanceForKey(String storeName, String key) {
        LOG.debugf("Finding instance for key: %s in store: %s", key, storeName);

        try {
            // Query any healthy instance for metadata about the key
            String anyInstance = getAnyHealthyInstance();
            if (anyInstance == null) {
                LOG.error("No healthy streams-processor instances found");
                return null;
            }

            String url = anyInstance + "/metadata/instance-for-key/" + storeName + "/" + key;
            LOG.debugf("Querying instance-for-key from: %s", url);

            JsonObject json = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            StreamsInstanceMetadata metadata = new StreamsInstanceMetadata(
                json.getString("host"),
                json.getInt("port"),
                Set.of(storeName)
            );

            LOG.debugf("Found instance for key %s: %s", key, metadata);
            return metadata;

        } catch (Exception e) {
            LOG.errorf(e, "Error finding instance for key: %s in store: %s", key, storeName);
            return null;
        }
    }

    /**
     * Get any healthy streams-processor instance.
     * Resolves all instances via DNS, checks health, and randomly selects one.
     *
     * @return URL of a healthy instance
     */
    public String getAnyHealthyInstance() {
        List<String> healthyInstances = discoverHealthyInstances();

        if (healthyInstances.isEmpty()) {
            LOG.error("No healthy streams-processor instances found");
            return null;
        }

        // Randomly select one healthy instance
        String selected = healthyInstances.get(random.nextInt(healthyInstances.size()));
        LOG.debugf("Randomly selected instance: %s (from %d healthy instances)", selected, healthyInstances.size());
        return selected;
    }

    /**
     * Discover all healthy streams-processor instances via DNS resolution.
     *
     * @return List of URLs for healthy instances
     */
    private List<String> discoverHealthyInstances() {
        List<String> healthyInstances = new ArrayList<>();

        try {
            // Resolve all IPs for the headless service
            InetAddress[] addresses = InetAddress.getAllByName(headlessService);
            LOG.debugf("DNS resolved %d addresses for %s", addresses.length, headlessService);

            for (InetAddress address : addresses) {
                String instanceUrl = "http://" + address.getHostAddress() + ":" + port;

                if (isInstanceHealthy(instanceUrl)) {
                    healthyInstances.add(instanceUrl);
                }
            }

            LOG.debugf("Found %d healthy instances out of %d resolved", healthyInstances.size(), addresses.length);

        } catch (Exception e) {
            LOG.warnf("DNS resolution failed for %s, falling back to pod-0", headlessService);

            // Fallback: try streams-processor-0 directly
            String fallbackInstance = "http://streams-processor-0." + headlessService + ":" + port;
            if (isInstanceHealthy(fallbackInstance)) {
                healthyInstances.add(fallbackInstance);
            }
        }

        return healthyInstances;
    }

    /**
     * Check if a streams-processor instance is healthy.
     *
     * @param instanceUrl Base URL of the instance
     * @return true if healthy, false otherwise
     */
    private boolean isInstanceHealthy(String instanceUrl) {
        try {
            String healthUrl = instanceUrl + "/health";
            JsonObject health = client.target(healthUrl)
                .request(MediaType.APPLICATION_JSON)
                .get(JsonObject.class);

            boolean isUp = "UP".equals(health.getString("status"));
            if (isUp) {
                LOG.debugf("Instance %s is healthy", instanceUrl);
            } else {
                LOG.debugf("Instance %s is not healthy: status=%s", instanceUrl, health.getString("status"));
            }
            return isUp;

        } catch (Exception e) {
            LOG.debugf("Health check failed for instance %s: %s", instanceUrl, e.getMessage());
            return false;
        }
    }
}
