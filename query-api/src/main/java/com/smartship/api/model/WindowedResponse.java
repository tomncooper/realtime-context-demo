package com.smartship.api.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Generic wrapper for windowed query results.
 *
 * @param <T> The type of data contained in the window
 */
public record WindowedResponse<T>(
    @JsonProperty("window_start") long windowStart,
    @JsonProperty("window_start_iso") String windowStartIso,
    @JsonProperty("window_end") long windowEnd,
    @JsonProperty("window_end_iso") String windowEndIso,
    T data
) {}
