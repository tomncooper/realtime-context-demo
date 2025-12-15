package com.smartship.api.model.hybrid;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Generic wrapper for hybrid query results combining data from multiple sources.
 * Provides metadata about the sources queried, query execution time, and any warnings
 * about data availability issues.
 *
 * @param <T> The type of the result data
 */
public record HybridQueryResult<T>(
    @JsonProperty("result") T result,
    @JsonProperty("sources") List<String> sources,
    @JsonProperty("warnings") List<String> warnings,
    @JsonProperty("query_time_ms") long queryTimeMs,
    @JsonProperty("timestamp") long timestamp,
    @JsonProperty("summary") String summary
) {
    /**
     * Create a hybrid query result with all parameters.
     *
     * @param result The query result data
     * @param sources List of data sources queried (e.g., "kafka-streams", "postgresql")
     * @param warnings List of warnings about data availability issues
     * @param queryTimeMs Time taken to execute the query in milliseconds
     * @param summary Human-readable summary of the result
     */
    public static <T> HybridQueryResult<T> of(T result, List<String> sources,
            List<String> warnings, long queryTimeMs, String summary) {
        return new HybridQueryResult<>(result, sources,
            warnings != null ? warnings : List.of(),
            queryTimeMs, System.currentTimeMillis(), summary);
    }

    /**
     * Create a hybrid query result without warnings (backwards-compatible).
     */
    public static <T> HybridQueryResult<T> of(T result, List<String> sources,
            long queryTimeMs, String summary) {
        return of(result, sources, List.of(), queryTimeMs, summary);
    }

    /**
     * Create a hybrid query result from Kafka Streams only.
     */
    public static <T> HybridQueryResult<T> fromStreams(T result, long queryTimeMs, String summary) {
        return of(result, List.of("kafka-streams"), List.of(), queryTimeMs, summary);
    }

    /**
     * Create a hybrid query result from Kafka Streams only with warnings.
     */
    public static <T> HybridQueryResult<T> fromStreams(T result, List<String> warnings,
            long queryTimeMs, String summary) {
        return of(result, List.of("kafka-streams"), warnings, queryTimeMs, summary);
    }

    /**
     * Create a hybrid query result from PostgreSQL only.
     */
    public static <T> HybridQueryResult<T> fromPostgres(T result, long queryTimeMs, String summary) {
        return of(result, List.of("postgresql"), List.of(), queryTimeMs, summary);
    }

    /**
     * Create a hybrid query result combining Kafka Streams and PostgreSQL data.
     */
    public static <T> HybridQueryResult<T> hybrid(T result, long queryTimeMs, String summary) {
        return of(result, List.of("kafka-streams", "postgresql"), List.of(), queryTimeMs, summary);
    }

    /**
     * Create a hybrid query result combining Kafka Streams and PostgreSQL data with warnings.
     */
    public static <T> HybridQueryResult<T> hybrid(T result, List<String> warnings,
            long queryTimeMs, String summary) {
        return of(result, List.of("kafka-streams", "postgresql"), warnings, queryTimeMs, summary);
    }
}
