package com.smartship.streams;

import java.util.Objects;
import java.util.Set;

/**
 * DTO for serializing Kafka Streams metadata to JSON.
 * Represents information about a single Kafka Streams instance.
 */
public class StreamsMetadataResponse {

    private String host;
    private int port;
    private Set<String> stateStoreNames;

    public StreamsMetadataResponse() {
    }

    public StreamsMetadataResponse(String host, int port, Set<String> stateStoreNames) {
        this.host = host;
        this.port = port;
        this.stateStoreNames = stateStoreNames;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Set<String> getStateStoreNames() {
        return stateStoreNames;
    }

    public void setStateStoreNames(Set<String> stateStoreNames) {
        this.stateStoreNames = stateStoreNames;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamsMetadataResponse that = (StreamsMetadataResponse) o;
        return port == that.port &&
                Objects.equals(host, that.host) &&
                Objects.equals(stateStoreNames, that.stateStoreNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, stateStoreNames);
    }

    @Override
    public String toString() {
        return "StreamsMetadataResponse{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", stateStoreNames=" + stateStoreNames +
                '}';
    }
}
