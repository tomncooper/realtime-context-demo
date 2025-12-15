package com.smartship.generators.model;

/**
 * Customer reference data loaded from PostgreSQL.
 */
public class Customer {
    private final String customerId;
    private final String companyName;
    private final String contactEmail;
    private final String slaTier;
    private final String accountStatus;

    public Customer(String customerId, String companyName, String contactEmail,
                    String slaTier, String accountStatus) {
        this.customerId = customerId;
        this.companyName = companyName;
        this.contactEmail = contactEmail;
        this.slaTier = slaTier;
        this.accountStatus = accountStatus;
    }

    public String getCustomerId() { return customerId; }
    public String getCompanyName() { return companyName; }
    public String getContactEmail() { return contactEmail; }
    public String getSlaTier() { return slaTier; }
    public String getAccountStatus() { return accountStatus; }

    @Override
    public String toString() {
        return "Customer{" +
                "customerId='" + customerId + '\'' +
                ", companyName='" + companyName + '\'' +
                ", slaTier='" + slaTier + '\'' +
                '}';
    }
}
