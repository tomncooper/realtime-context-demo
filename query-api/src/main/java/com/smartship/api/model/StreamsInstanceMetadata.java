package com.smartship.api.model;

import java.util.Objects;
import java.util.Set;

/**
 * DTO representing metadata about a Kafka Streams instance.
 * Used for instance discovery and query routing.
 */
public class StreamsInstanceMetadata {

    private String host;
    private int port;
    private Set<String> stateStoreNames;

    public StreamsInstanceMetadata() {
    }

    public StreamsInstanceMetadata(String host, int port, Set<String> stateStoreNames) {
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

    public String getUrl() {
        return "http://" + host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamsInstanceMetadata that = (StreamsInstanceMetadata) o;
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
        return "StreamsInstanceMetadata{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", stateStoreNames=" + stateStoreNames +
                '}';
    }
}
