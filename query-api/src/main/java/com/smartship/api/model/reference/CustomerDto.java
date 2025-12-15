package com.smartship.api.model.reference;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for customer reference data from PostgreSQL.
 */
public record CustomerDto(
    @JsonProperty("customer_id") String customerId,
    @JsonProperty("company_name") String companyName,
    @JsonProperty("contact_email") String contactEmail,
    @JsonProperty("sla_tier") String slaTier,
    @JsonProperty("account_status") String accountStatus
) {}
