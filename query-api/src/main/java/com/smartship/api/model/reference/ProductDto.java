package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for product reference data from PostgreSQL.
 */
public record ProductDto(
    @JsonProperty("product_id") String productId,
    @JsonProperty("sku") String sku,
    @JsonProperty("name") String name,
    @JsonProperty("category") String category,
    @JsonProperty("weight_kg") Double weightKg,
    @JsonProperty("length_cm") Double lengthCm,
    @JsonProperty("width_cm") Double widthCm,
    @JsonProperty("height_cm") Double heightCm,
    @JsonProperty("unit_price") Double unitPrice
) {}
