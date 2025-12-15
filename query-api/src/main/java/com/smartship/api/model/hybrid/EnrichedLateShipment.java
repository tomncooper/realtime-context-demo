package com.smartship.api.model.hybrid;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enriched late shipment data combining real-time Kafka Streams state
 * with customer reference data from PostgreSQL.
 */
public record EnrichedLateShipment(
    // Real-time data from Kafka Streams (late-shipments store)
    @JsonProperty("shipment_id") String shipmentId,
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("warehouse_id") String warehouseId,
    @JsonProperty("destination_city") String destinationCity,
    @JsonProperty("destination_country") String destinationCountry,
    @JsonProperty("current_status") String currentStatus,
    @JsonProperty("delay_minutes") long delayMinutes,
    @JsonProperty("expected_delivery") long expectedDelivery,
    @JsonProperty("last_updated") long lastUpdated,

    // Reference data from PostgreSQL (customers table)
    @JsonProperty("company_name") String companyName,
    @JsonProperty("sla_tier") String slaTier,
    @JsonProperty("contact_email") String contactEmail,

    // Reference data from PostgreSQL (warehouses table)
    @JsonProperty("warehouse_name") String warehouseName,
    @JsonProperty("warehouse_city") String warehouseCity
) {
    /**
     * Builder for creating EnrichedLateShipment from separate data sources.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String shipmentId;
        private String customerId;
        private String warehouseId;
        private String destinationCity;
        private String destinationCountry;
        private String currentStatus;
        private long delayMinutes;
        private long expectedDelivery;
        private long lastUpdated;
        private String companyName;
        private String slaTier;
        private String contactEmail;
        private String warehouseName;
        private String warehouseCity;

        public Builder shipmentId(String shipmentId) {
            this.shipmentId = shipmentId;
            return this;
        }

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder warehouseId(String warehouseId) {
            this.warehouseId = warehouseId;
            return this;
        }

        public Builder destinationCity(String destinationCity) {
            this.destinationCity = destinationCity;
            return this;
        }

        public Builder destinationCountry(String destinationCountry) {
            this.destinationCountry = destinationCountry;
            return this;
        }

        public Builder currentStatus(String currentStatus) {
            this.currentStatus = currentStatus;
            return this;
        }

        public Builder delayMinutes(long delayMinutes) {
            this.delayMinutes = delayMinutes;
            return this;
        }

        public Builder expectedDelivery(long expectedDelivery) {
            this.expectedDelivery = expectedDelivery;
            return this;
        }

        public Builder lastUpdated(long lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder slaTier(String slaTier) {
            this.slaTier = slaTier;
            return this;
        }

        public Builder contactEmail(String contactEmail) {
            this.contactEmail = contactEmail;
            return this;
        }

        public Builder warehouseName(String warehouseName) {
            this.warehouseName = warehouseName;
            return this;
        }

        public Builder warehouseCity(String warehouseCity) {
            this.warehouseCity = warehouseCity;
            return this;
        }

        public EnrichedLateShipment build() {
            return new EnrichedLateShipment(
                shipmentId, customerId, warehouseId, destinationCity, destinationCountry,
                currentStatus, delayMinutes, expectedDelivery, lastUpdated,
                companyName, slaTier, contactEmail, warehouseName, warehouseCity
            );
        }
    }
}
