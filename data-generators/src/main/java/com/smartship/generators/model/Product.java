package com.smartship.generators.model;

/**
 * Product reference data loaded from PostgreSQL.
 */
public class Product {
    private final String productId;
    private final String sku;
    private final String name;
    private final String category;
    private final double weightKg;
    private final double lengthCm;
    private final double widthCm;
    private final double heightCm;
    private final double unitPrice;

    public Product(String productId, String sku, String name, String category,
                   double weightKg, double lengthCm, double widthCm, double heightCm,
                   double unitPrice) {
        this.productId = productId;
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.weightKg = weightKg;
        this.lengthCm = lengthCm;
        this.widthCm = widthCm;
        this.heightCm = heightCm;
        this.unitPrice = unitPrice;
    }

    public String getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getWeightKg() { return weightKg; }
    public double getLengthCm() { return lengthCm; }
    public double getWidthCm() { return widthCm; }
    public double getHeightCm() { return heightCm; }
    public double getUnitPrice() { return unitPrice; }

    @Override
    public String toString() {
        return "Product{" +
                "productId='" + productId + '\'' +
                ", sku='" + sku + '\'' +
                ", category='" + category + '\'' +
                '}';
    }
}
