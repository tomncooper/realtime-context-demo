package com.smartship.api.model.hybrid;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Enriched customer overview combining reference data from PostgreSQL
 * with real-time shipment and order statistics from Kafka Streams.
 */
public record EnrichedCustomerOverview(
    // Reference data from PostgreSQL (customers table)
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("company_name") String companyName,
    @JsonProperty("contact_email") String contactEmail,
    @JsonProperty("sla_tier") String slaTier,
    @JsonProperty("account_status") String accountStatus,

    // Real-time shipment stats from Kafka Streams (shipments-by-customer store)
    @JsonProperty("total_shipments") long totalShipments,
    @JsonProperty("delivered_shipments") long deliveredShipments,
    @JsonProperty("in_transit_shipments") long inTransitShipments,
    @JsonProperty("exception_shipments") long exceptionShipments,
    @JsonProperty("shipment_on_time_rate") double shipmentOnTimeRate,

    // Real-time order stats from Kafka Streams (orders-by-customer store)
    @JsonProperty("total_orders") long totalOrders,
    @JsonProperty("pending_orders") long pendingOrders,
    @JsonProperty("shipped_orders") long shippedOrders,
    @JsonProperty("delivered_orders") long deliveredOrders,
    @JsonProperty("at_risk_orders") long atRiskOrders,

    // Computed metrics
    @JsonProperty("sla_compliance_rate") double slaComplianceRate,
    @JsonProperty("last_updated") long lastUpdated
) {
    /**
     * Builder for creating EnrichedCustomerOverview from separate data sources.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String customerId;
        private String companyName;
        private String contactEmail;
        private String slaTier;
        private String accountStatus;
        private long totalShipments;
        private long deliveredShipments;
        private long inTransitShipments;
        private long exceptionShipments;
        private double shipmentOnTimeRate;
        private long totalOrders;
        private long pendingOrders;
        private long shippedOrders;
        private long deliveredOrders;
        private long atRiskOrders;
        private double slaComplianceRate;
        private long lastUpdated;

        public Builder customerId(String customerId) {
            this.customerId = customerId;
            return this;
        }

        public Builder companyName(String companyName) {
            this.companyName = companyName;
            return this;
        }

        public Builder contactEmail(String contactEmail) {
            this.contactEmail = contactEmail;
            return this;
        }

        public Builder slaTier(String slaTier) {
            this.slaTier = slaTier;
            return this;
        }

        public Builder accountStatus(String accountStatus) {
            this.accountStatus = accountStatus;
            return this;
        }

        public Builder totalShipments(long totalShipments) {
            this.totalShipments = totalShipments;
            return this;
        }

        public Builder deliveredShipments(long deliveredShipments) {
            this.deliveredShipments = deliveredShipments;
            return this;
        }

        public Builder inTransitShipments(long inTransitShipments) {
            this.inTransitShipments = inTransitShipments;
            return this;
        }

        public Builder exceptionShipments(long exceptionShipments) {
            this.exceptionShipments = exceptionShipments;
            return this;
        }

        public Builder shipmentOnTimeRate(double shipmentOnTimeRate) {
            this.shipmentOnTimeRate = shipmentOnTimeRate;
            return this;
        }

        public Builder totalOrders(long totalOrders) {
            this.totalOrders = totalOrders;
            return this;
        }

        public Builder pendingOrders(long pendingOrders) {
            this.pendingOrders = pendingOrders;
            return this;
        }

        public Builder shippedOrders(long shippedOrders) {
            this.shippedOrders = shippedOrders;
            return this;
        }

        public Builder deliveredOrders(long deliveredOrders) {
            this.deliveredOrders = deliveredOrders;
            return this;
        }

        public Builder atRiskOrders(long atRiskOrders) {
            this.atRiskOrders = atRiskOrders;
            return this;
        }

        public Builder slaComplianceRate(double slaComplianceRate) {
            this.slaComplianceRate = slaComplianceRate;
            return this;
        }

        public Builder lastUpdated(long lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public EnrichedCustomerOverview build() {
            return new EnrichedCustomerOverview(
                customerId, companyName, contactEmail, slaTier, accountStatus,
                totalShipments, deliveredShipments, inTransitShipments, exceptionShipments, shipmentOnTimeRate,
                totalOrders, pendingOrders, shippedOrders, deliveredOrders, atRiskOrders,
                slaComplianceRate, lastUpdated
            );
        }
    }
}
