# Apache Iceberg Enhancement Appendix
## Historical Analytics for Real-Time Logistics Demo

### Overview

This appendix describes how to enhance the base real-time logistics demonstration with **Apache Iceberg** historical analytical capabilities. This enhancement enables long-term trend analysis, machine learning model training, and comprehensive performance tracking that complements the real-time operational system.

**Purpose**: Extend the SmartShip Logistics demo with historical data analytics
**Benefits**:
- Long-term trend analysis and forecasting
- Customer behavior analytics
- Machine learning model training for delivery time prediction
- Compliance and audit reporting
- Year-over-year performance comparisons

**When to Implement**: After the core real-time system (Kafka + Kafka Streams + PostgreSQL) is operational and stable.

---

## Current vs. Enhanced Architecture

### Current Architecture (Base System)

The base system provides real-time operational capabilities:

```
┌─────────────────┐
│  Data Producers │
│  (Generators)   │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│         Apache Kafka Topics             │
│  • shipment.events                      │
│  • vehicle.telemetry                    │
│  • warehouse.operations                 │
│  • order.status                         │
└────────┬────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│      Kafka Streams Application          │
│  • Materialized Views (State Stores)    │
│  • Real-time aggregations               │
│  • Windowed analytics (minutes/hours)   │
└────────┬────────────────────────────────┘
         │
         ▼
┌─────────────────┐      ┌────────────────┐
│  LLM Query API  │◄────►│  PostgreSQL    │
│                 │      │  (Reference    │
│                 │      │   Data)        │
└─────────────────┘      └────────────────┘
```

**Capabilities**:
- Real-time shipment tracking
- Current vehicle status and location
- Recent warehouse performance (last 15 minutes to few hours)
- Active order status

**Limitations**:
- No long-term historical data (>24 hours)
- Cannot perform trend analysis over weeks/months
- Limited ability to train ML models
- No year-over-year comparisons

### Enhanced Architecture (with Apache Iceberg)

```
┌─────────────────┐
│  Data Producers │
│  (Generators)   │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│         Apache Kafka Topics             │
│  • shipment.events                      │
│  • vehicle.telemetry                    │
│  • warehouse.operations                 │
│  • order.status                         │
└────────┬───────────────┬────────────────┘
         │               │
         │               │  ┌──────────────────────┐
         │               └─►│ Iceberg Ingestion    │
         │                  │ Pipeline (Connector  │
         │                  │ or Spark Streaming)  │
         │                  └──────────┬───────────┘
         ▼                             │
┌─────────────────────────────────────┐│
│      Kafka Streams Application      ││
│  • Materialized Views               ││
│  • Real-time aggregations           ││
└────────┬────────────────────────────┘│
         │                             │
         │                             ▼
         │                  ┌─────────────────────────┐
         │                  │  Apache Iceberg Tables  │
         │                  │  (on Object Storage)    │
         │                  │  • daily_shipment_      │
         │                  │    metrics              │
         │                  │  • vehicle_performance  │
         │                  │  • warehouse_efficiency │
         │                  │  • customer_order_      │
         │                  │    history              │
         │                  └──────────┬──────────────┘
         │                             │
         │                             ▼
         │                  ┌─────────────────────────┐
         │                  │   Query Engine          │
         │                  │   (Spark/Trino/Dremio)  │
         │                  └──────────┬──────────────┘
         │                             │
         ▼                             ▼
┌───────────────────────────────────────────────────┐
│           Enhanced LLM Query API                  │
│  • Real-time queries → Kafka Streams              │
│  • Historical queries → Iceberg via Query Engine  │
│  • Hybrid queries → Join both sources             │
└────────┬──────────────────────────────────────────┘
         │
         ▼
┌─────────────────┐
│  PostgreSQL     │
│  (Reference     │
│   Data)         │
└─────────────────┘
```

**Additional Capabilities**:
- Historical trend analysis (days, weeks, months, years)
- Customer behavior patterns and segmentation
- Predictive analytics and forecasting
- ML model training on historical delivery data
- Compliance reporting and data archival

---

## Apache Iceberg Table Schemas

### Table: `daily_shipment_metrics`

**Purpose**: Daily aggregated shipment performance by warehouse and customer
**Partitioning**: By `metric_date` (daily partitions)
**Retention**: 2 years
**Update Frequency**: Daily batch job at midnight (local warehouse time)

**Schema**:
```sql
CREATE TABLE daily_shipment_metrics (
  metric_date DATE,
  warehouse_id STRING,
  customer_id STRING,
  total_shipments INT,
  delivered_on_time INT,
  delivered_late INT,
  exceptions INT,
  cancelled INT,
  avg_delivery_time_hours DOUBLE,
  p50_delivery_time_hours DOUBLE,
  p95_delivery_time_hours DOUBLE,
  p99_delivery_time_hours DOUBLE,
  revenue_usd DOUBLE,
  sla_compliance_percent DOUBLE
) USING iceberg
PARTITIONED BY (days(metric_date))
TBLPROPERTIES (
  'write.format.default' = 'parquet',
  'write.parquet.compression-codec' = 'zstd'
);
```

**Data Characteristics**:
- 180 days of historical data initially
- ~1,000 rows per day (5 warehouses × 200 customers)
- Enables trend analysis and SLA tracking
- Supports customer churn prediction

### Table: `vehicle_performance_history`

**Purpose**: Daily vehicle utilization and performance metrics
**Partitioning**: By `metric_date` (daily partitions)
**Retention**: 1 year
**Update Frequency**: Daily batch job aggregating previous day's telemetry

**Schema**:
```sql
CREATE TABLE vehicle_performance_history (
  metric_date DATE,
  vehicle_id STRING,
  warehouse_id STRING,
  total_km_driven DOUBLE,
  total_shipments_delivered INT,
  utilization_percent DOUBLE,
  fuel_consumed_liters DOUBLE,
  fuel_efficiency_km_per_liter DOUBLE,
  maintenance_events INT,
  downtime_hours DOUBLE,
  avg_speed_kmh DOUBLE,
  idle_time_hours DOUBLE,
  active_time_hours DOUBLE
) USING iceberg
PARTITIONED BY (days(metric_date))
TBLPROPERTIES (
  'write.format.default' = 'parquet',
  'write.parquet.compression-codec' = 'zstd'
);
```

**Data Characteristics**:
- 365 days of historical data
- 50 rows per day (50 vehicles)
- Enables fleet optimization and predictive maintenance
- Supports electric vs. fuel vehicle efficiency analysis

### Table: `warehouse_efficiency_metrics`

**Purpose**: Daily warehouse operational KPIs
**Partitioning**: By `metric_date` (daily partitions)
**Retention**: 2 years
**Update Frequency**: Daily batch job at end of business day

**Schema**:
```sql
CREATE TABLE warehouse_efficiency_metrics (
  metric_date DATE,
  warehouse_id STRING,
  total_operations INT,
  receiving_operations INT,
  putaway_operations INT,
  pick_operations INT,
  pack_operations INT,
  load_operations INT,
  avg_pick_time_seconds DOUBLE,
  avg_pack_time_seconds DOUBLE,
  error_count INT,
  error_rate_percent DOUBLE,
  inventory_accuracy_percent DOUBLE,
  shipments_processed INT,
  volume_shipped_cubic_m DOUBLE,
  worker_productivity_ops_per_hour DOUBLE
) USING iceberg
PARTITIONED BY (days(metric_date))
TBLPROPERTIES (
  'write.format.default' = 'parquet',
  'write.parquet.compression-codec' = 'zstd'
);
```

**Data Characteristics**:
- 730 days of historical data (2 years)
- 5 rows per day (5 warehouses)
- Enables seasonal pattern analysis
- Supports capacity planning

### Table: `customer_order_history`

**Purpose**: Complete historical order records for analysis
**Partitioning**: By `order_date` (monthly partitions)
**Retention**: 5 years
**Update Frequency**: Streaming ingestion or daily batch

**Schema**:
```sql
CREATE TABLE customer_order_history (
  order_id STRING,
  customer_id STRING,
  order_date TIMESTAMP,
  delivery_date TIMESTAMP,
  total_items INT,
  total_weight_kg DOUBLE,
  total_value_usd DOUBLE,
  origin_warehouse_id STRING,
  destination_city STRING,
  destination_region STRING,
  destination_country STRING,
  priority STRING,
  sla_met BOOLEAN,
  delivery_time_hours DOUBLE,
  exception_count INT,
  exception_types ARRAY<STRING>,
  customer_rating INT
) USING iceberg
PARTITIONED BY (months(order_date))
TBLPROPERTIES (
  'write.format.default' = 'parquet',
  'write.parquet.compression-codec' = 'zstd',
  'write.metadata.delete-after-commit.enabled' = 'true',
  'write.metadata.previous-versions-max' = '10'
);
```

**Data Characteristics**:
- Complete order history for 5 years
- ~500-2,000 orders per day
- Enables customer behavior analytics
- Supports ML model training for delivery time prediction
- Partitioned by month for efficient querying

---

## Data Ingestion Strategies

### Option 1: Kafka Connect Iceberg Sink Connector (Recommended for Streaming)

**Approach**: Use Apache Iceberg Kafka Connect sink connector to continuously stream data from Kafka topics to Iceberg tables.

**Pros**:
- Real-time data ingestion
- Automatic schema evolution
- Minimal custom code
- Built-in exactly-once semantics

**Cons**:
- Requires Kafka Connect cluster
- Limited transformation capabilities
- May create many small files (requires compaction strategy)

**Configuration Example**:
```json
{
  "name": "iceberg-sink-shipment-events",
  "config": {
    "connector.class": "io.tabular.iceberg.connect.IcebergSinkConnector",
    "tasks.max": "2",
    "topics": "shipment.events",
    "iceberg.tables": "customer_order_history",
    "iceberg.catalog.type": "rest",
    "iceberg.catalog.uri": "http://iceberg-rest-catalog:8181",
    "iceberg.catalog.warehouse": "s3://warehouse/smartship",
    "iceberg.tables.auto-create-enabled": "true",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "io.apicurio.registry.utils.converter.AvroConverter",
    "value.converter.apicurio.registry.url": "http://apicurio-registry:8080/apis/registry/v2"
  }
}
```

### Option 2: Batch Aggregation with Apache Spark (Recommended for Daily Metrics)

**Approach**: Run daily Spark jobs to aggregate Kafka topics into daily metric tables.

**Pros**:
- Powerful transformation capabilities
- Efficient for aggregations
- Better control over file sizes
- Ideal for daily/hourly batch windows

**Cons**:
- Requires Spark cluster
- Not real-time (scheduled batch)
- More complex setup

**Example Spark Job** (PySpark):
```python
from pyspark.sql import SparkSession
from pyspark.sql.functions import *

spark = SparkSession.builder \
    .appName("DailyShipmentMetrics") \
    .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions") \
    .config("spark.sql.catalog.iceberg", "org.apache.iceberg.spark.SparkCatalog") \
    .config("spark.sql.catalog.iceberg.type", "rest") \
    .config("spark.sql.catalog.iceberg.uri", "http://iceberg-rest-catalog:8181") \
    .getOrCreate()

# Read from Kafka
shipment_df = spark.readStream \
    .format("kafka") \
    .option("kafka.bootstrap.servers", "kafka-broker:9092") \
    .option("subscribe", "shipment.events") \
    .option("startingOffsets", "earliest") \
    .load()

# Aggregate daily metrics
daily_metrics = shipment_df \
    .select(from_json(col("value").cast("string"), shipment_schema).alias("data")) \
    .select("data.*") \
    .groupBy(
        to_date(col("timestamp")).alias("metric_date"),
        col("warehouse_id"),
        col("customer_id")
    ) \
    .agg(
        count("*").alias("total_shipments"),
        sum(when(col("status") == "DELIVERED_ON_TIME", 1).otherwise(0)).alias("delivered_on_time"),
        sum(when(col("status") == "DELIVERED_LATE", 1).otherwise(0)).alias("delivered_late"),
        avg("delivery_time_hours").alias("avg_delivery_time_hours"),
        expr("percentile(delivery_time_hours, 0.95)").alias("p95_delivery_time_hours")
    )

# Write to Iceberg
daily_metrics.writeTo("iceberg.daily_shipment_metrics") \
    .using("iceberg") \
    .partitionedBy("metric_date") \
    .createOrReplace()
```

### Option 3: Kafka Streams to Iceberg (Custom Integration)

**Approach**: Extend Kafka Streams application to write aggregated data to Iceberg using Iceberg Java API.

**Pros**:
- Reuses existing Kafka Streams topology
- Low latency
- Full control over logic

**Cons**:
- Custom code required
- More complex error handling
- Requires Iceberg Java library integration

**Recommendation**:
- Use **Option 2 (Spark Batch)** for daily aggregate tables (`daily_shipment_metrics`, `vehicle_performance_history`, `warehouse_efficiency_metrics`)
- Use **Option 1 (Kafka Connect)** for event-level tables (`customer_order_history`)

---

## Technology Stack Additions

### Java Dependencies

```xml
<dependencies>
  <!-- Apache Iceberg Core -->
  <dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-core</artifactId>
    <version>1.5.2</version>
  </dependency>

  <!-- Iceberg Parquet Support -->
  <dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-parquet</artifactId>
    <version>1.5.2</version>
  </dependency>

  <!-- Iceberg Spark Runtime (for Spark jobs) -->
  <dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-spark-runtime-3.5</artifactId>
    <version>1.5.2</version>
  </dependency>

  <!-- Iceberg AWS Bundle (for S3/MinIO) -->
  <dependency>
    <groupId>org.apache.iceberg</groupId>
    <artifactId>iceberg-aws-bundle</artifactId>
    <version>1.5.2</version>
  </dependency>

  <!-- Iceberg Kafka Connect -->
  <dependency>
    <groupId>io.tabular</groupId>
    <artifactId>iceberg-kafka-connect</artifactId>
    <version>0.6.16</version>
  </dependency>
</dependencies>
```

### Infrastructure Components

1. **Object Storage** (choose one):
   - **MinIO** (S3-compatible, self-hosted): Recommended for development/demo
   - **AWS S3**: For cloud deployments
   - **Azure Blob Storage**: With Iceberg Azure integration
   - **Google Cloud Storage**: With Iceberg GCS integration

2. **Iceberg Catalog** (choose one):
   - **Iceberg REST Catalog** (Tabular, Polaris): Lightweight, modern, recommended
   - **Hive Metastore**: Traditional, widely supported
   - **Nessie**: Git-like catalog with versioning capabilities
   - **AWS Glue**: Managed service for AWS deployments

3. **Query Engine** (choose one):
   - **Apache Spark 3.5+**: Best for batch processing and ETL
   - **Trino**: Fast interactive queries, SQL interface
   - **Dremio**: User-friendly, semantic layer, good for BI tools
   - **StarRocks**: Fast analytics, good for real-time OLAP

---

## Enhanced LLM Agent Queries

These queries require historical data from Apache Iceberg tables and demonstrate the value of the enhancement:

| Query | Kafka Topics | State Stores | Relational Tables | Iceberg Tables |
|-------|--------------|--------------|-------------------|----------------|
| "Compare today's delivery performance to last week" | shipment.events | hourly-delivery-performance | warehouses | daily_shipment_metrics |
| "Which warehouse has highest exception rate this week?" | warehouse.operations | warehouse-realtime-metrics | warehouses | warehouse_efficiency_metrics |
| "Which customers consistently exceed SLA requirements?" | - | - | customers | customer_order_history, daily_shipment_metrics |
| "What's the average fuel efficiency of our electric vehicles?" | - | - | vehicles | vehicle_performance_history |
| "Show delivery time trends for Frankfurt warehouse over last 3 months" | - | - | warehouses | daily_shipment_metrics |
| "Identify customers with declining satisfaction ratings" | - | - | customers | customer_order_history |
| "Which products have the highest exception rates?" | - | - | products | customer_order_history |
| "Predict delivery time for route from Rotterdam to Paris" | shipment.events | - | routes | customer_order_history (for ML training) |

---

## Implementation Steps

### Prerequisites
1. Base real-time system is operational (Kafka, Kafka Streams, PostgreSQL)
2. Synthetic data generators are producing events
3. Kafka Streams materialized views are working
4. LLM query API can query Kafka Streams state stores

### Step 1: Set Up Object Storage

**Option A: MinIO (Development/Demo)**

```bash
# Deploy MinIO using Docker
docker run -d \
  --name minio \
  -p 9000:9000 \
  -p 9001:9001 \
  -e MINIO_ROOT_USER=admin \
  -e MINIO_ROOT_PASSWORD=password123 \
  -v /data/minio:/data \
  quay.io/minio/minio server /data --console-address ":9001"

# Create warehouse bucket
docker exec minio mc alias set local http://localhost:9000 admin password123
docker exec minio mc mb local/warehouse
```

**Option B: AWS S3**

```bash
# Create S3 bucket
aws s3 mb s3://smartship-warehouse --region eu-central-1

# Create IAM role with appropriate permissions
# Attach policy with s3:PutObject, s3:GetObject, s3:DeleteObject permissions
```

### Step 2: Deploy Iceberg Catalog

**Option A: Polaris Catalog (REST - Recommended)**

```bash
# Run Polaris using Docker
docker run -d \
  --name polaris-catalog \
  -p 8181:8181 \
  -e AWS_REGION=eu-central-1 \
  -e CATALOG_WAREHOUSE=s3://smartship-warehouse/ \
  apache/polaris:latest
```

**Option B: Hive Metastore**

```bash
# Deploy Hive Metastore with PostgreSQL backend
# See: https://iceberg.apache.org/docs/latest/hive/
```

### Step 3: Create Iceberg Table Schemas

Using Spark SQL:

```sql
-- Connect to Spark with Iceberg extensions
spark-sql \
  --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
  --conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.iceberg.type=rest \
  --conf spark.sql.catalog.iceberg.uri=http://polaris-catalog:8181

-- Create tables (schemas from section above)
CREATE TABLE iceberg.daily_shipment_metrics ( ... );
CREATE TABLE iceberg.vehicle_performance_history ( ... );
CREATE TABLE iceberg.warehouse_efficiency_metrics ( ... );
CREATE TABLE iceberg.customer_order_history ( ... );
```

### Step 4: Implement Data Ingestion Pipeline

**For daily aggregation tables**: Create Spark batch job (scheduled via cron or Airflow)

```python
# daily_metrics_job.py
from pyspark.sql import SparkSession
from datetime import datetime, timedelta

def run_daily_metrics():
    spark = create_spark_session()
    yesterday = (datetime.now() - timedelta(days=1)).strftime('%Y-%m-%d')

    # Read from Kafka for yesterday's date
    # Aggregate and write to Iceberg
    # (Full code in Option 2 above)

    spark.stop()

if __name__ == "__main__":
    run_daily_metrics()
```

**For event-level tables**: Configure Kafka Connect

```bash
# Deploy connector
curl -X POST http://kafka-connect:8083/connectors \
  -H "Content-Type: application/json" \
  -d @iceberg-sink-connector.json
```

### Step 5: Set Up Query Engine

**Option A: Trino**

```yaml
# docker-compose.yml excerpt
  trino:
    image: trinodb/trino:latest
    ports:
      - "8080:8080"
    volumes:
      - ./trino/catalog/iceberg.properties:/etc/trino/catalog/iceberg.properties
```

**Iceberg catalog config** (`iceberg.properties`):
```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=http://polaris-catalog:8181
```

**Option B: Spark Thrift Server**

```bash
# Start Spark Thrift Server for JDBC/ODBC access
$SPARK_HOME/sbin/start-thriftserver.sh \
  --conf spark.sql.catalog.iceberg=org.apache.iceberg.spark.SparkCatalog \
  --conf spark.sql.catalog.iceberg.type=rest \
  --conf spark.sql.catalog.iceberg.uri=http://polaris-catalog:8181
```

### Step 6: Extend LLM Query Interface

**Add Iceberg query capability to existing LLM API**:

```java
public class EnhancedQueryService {
    private final KafkaStreamsService kafkaStreamsService;  // Existing
    private final PostgresService postgresService;          // Existing
    private final TrinoClient trinoClient;                  // New

    public QueryResult executeQuery(String naturalLanguageQuery) {
        // 1. LLM determines required data sources
        QueryPlan plan = llm.analyzeQuery(naturalLanguageQuery);

        // 2. Route to appropriate data source(s)
        if (plan.requiresHistoricalData()) {
            return queryIceberg(plan.getIcebergQuery());
        } else if (plan.requiresRealTimeData()) {
            return queryKafkaStreams(plan.getStateStore());
        } else if (plan.requiresBoth()) {
            return joinRealTimeAndHistorical(plan);
        }
    }

    private QueryResult queryIceberg(String sqlQuery) {
        return trinoClient.executeQuery(sqlQuery);
    }
}
```

### Step 7: Test and Validate

**Validation Checklist**:
- [ ] Object storage is accessible and writable
- [ ] Iceberg catalog is running and managing table metadata
- [ ] Tables are created and partitioned correctly
- [ ] Data ingestion pipeline is writing data to Iceberg
- [ ] Query engine can read from Iceberg tables
- [ ] Sample SQL queries return expected results
- [ ] LLM API can route historical queries to Iceberg
- [ ] All 4 enhanced LLM queries work end-to-end

---

## Query Engine Integration

### Comparison of Query Engines

| Feature | Apache Spark | Trino | Dremio |
|---------|-------------|-------|--------|
| **Best For** | Batch ETL, ML | Interactive SQL | BI tools, data virtualization |
| **Latency** | Medium-High | Low | Low-Medium |
| **Complexity** | High | Medium | Low |
| **Iceberg Support** | Excellent | Excellent | Excellent |
| **Language** | Scala/Java | Java | Java |
| **SQL Compatibility** | Good | Excellent | Excellent |
| **Resource Usage** | High | Medium | Medium |
| **Streaming** | Yes (Structured Streaming) | No | Limited |

**Recommendation for Demo**: **Trino** - Good balance of simplicity, performance, and SQL compatibility.

### Trino Configuration Example

**Complete Trino setup**:

```yaml
# docker-compose.yml
version: '3.8'

services:
  trino-coordinator:
    image: trinodb/trino:432
    ports:
      - "8080:8080"
    environment:
      - TRINO_ENVIRONMENT=production
    volumes:
      - ./trino/etc:/etc/trino
    command: /usr/lib/trino/bin/run-trino

  trino-worker:
    image: trinodb/trino:432
    environment:
      - TRINO_ENVIRONMENT=production
    volumes:
      - ./trino/etc:/etc/trino
    command: /usr/lib/trino/bin/run-trino
    depends_on:
      - trino-coordinator
```

**Catalog configuration** (`trino/etc/catalog/iceberg.properties`):
```properties
connector.name=iceberg
iceberg.catalog.type=rest
iceberg.rest-catalog.uri=http://polaris-catalog:8181
iceberg.rest-catalog.warehouse=s3://smartship-warehouse/
fs.native-s3.enabled=true
s3.endpoint=http://minio:9000
s3.path-style-access=true
s3.aws-access-key=admin
s3.aws-secret-key=password123
```

### JDBC Connection from Java

```java
import java.sql.*;

public class TrinoQueryExecutor {
    private static final String TRINO_URL = "jdbc:trino://localhost:8080/iceberg/default";

    public ResultSet executeQuery(String sql) throws SQLException {
        Connection conn = DriverManager.getConnection(TRINO_URL, "admin", "");
        Statement stmt = conn.createStatement();
        return stmt.executeQuery(sql);
    }

    public List<Map<String, Object>> queryToList(String sql) throws SQLException {
        ResultSet rs = executeQuery(sql);
        return convertResultSetToList(rs);
    }
}
```

---

## Sample Queries

### Query 1: Compare Delivery Performance (Week-over-Week)

**Natural Language**: "Compare today's delivery performance to last week"

**Generated SQL**:
```sql
WITH today_metrics AS (
  SELECT
    warehouse_id,
    SUM(delivered_on_time) as on_time,
    SUM(total_shipments) as total,
    AVG(avg_delivery_time_hours) as avg_time
  FROM iceberg.daily_shipment_metrics
  WHERE metric_date = CURRENT_DATE
  GROUP BY warehouse_id
),
last_week_metrics AS (
  SELECT
    warehouse_id,
    SUM(delivered_on_time) as on_time,
    SUM(total_shipments) as total,
    AVG(avg_delivery_time_hours) as avg_time
  FROM iceberg.daily_shipment_metrics
  WHERE metric_date = CURRENT_DATE - INTERVAL '7' DAY
  GROUP BY warehouse_id
)
SELECT
  w.name as warehouse_name,
  t.total as today_shipments,
  l.total as last_week_shipments,
  ROUND(100.0 * t.on_time / t.total, 2) as today_on_time_pct,
  ROUND(100.0 * l.on_time / l.total, 2) as last_week_on_time_pct,
  ROUND(t.avg_time - l.avg_time, 2) as avg_time_diff_hours
FROM today_metrics t
JOIN last_week_metrics l ON t.warehouse_id = l.warehouse_id
JOIN postgresql.public.warehouses w ON t.warehouse_id = w.warehouse_id
ORDER BY today_on_time_pct DESC;
```

### Query 2: Identify Underperforming Customers

**Natural Language**: "Which customers consistently exceed SLA requirements?"

Actually this query should find customers who DON'T meet SLA. Let me correct:

**Natural Language**: "Which customers have the worst SLA compliance over the last 30 days?"

**Generated SQL**:
```sql
SELECT
  c.company_name,
  c.sla_tier,
  COUNT(DISTINCT o.order_id) as total_orders,
  SUM(CASE WHEN o.sla_met = false THEN 1 ELSE 0 END) as sla_violations,
  ROUND(100.0 * SUM(CASE WHEN o.sla_met = true THEN 1 ELSE 0 END) / COUNT(*), 2) as sla_compliance_pct,
  AVG(o.delivery_time_hours) as avg_delivery_hours
FROM iceberg.customer_order_history o
JOIN postgresql.public.customers c ON o.customer_id = c.customer_id
WHERE o.order_date >= CURRENT_DATE - INTERVAL '30' DAY
GROUP BY c.company_name, c.sla_tier
HAVING sla_compliance_pct < 90
ORDER BY sla_compliance_pct ASC
LIMIT 10;
```

### Query 3: Electric Vehicle Fuel Efficiency

**Natural Language**: "What's the average fuel efficiency of our electric vehicles?"

**Generated SQL**:
```sql
SELECT
  v.make,
  v.model,
  COUNT(DISTINCT vph.vehicle_id) as vehicle_count,
  AVG(vph.total_km_driven) as avg_km_per_day,
  AVG(vph.fuel_efficiency_km_per_liter) as avg_efficiency,
  AVG(vph.utilization_percent) as avg_utilization
FROM iceberg.vehicle_performance_history vph
JOIN postgresql.public.vehicles v ON vph.vehicle_id = v.vehicle_id
WHERE v.fuel_type = 'ELECTRIC'
  AND vph.metric_date >= CURRENT_DATE - INTERVAL '30' DAY
GROUP BY v.make, v.model
ORDER BY avg_efficiency DESC;
```

### Query 4: Seasonal Warehouse Performance

**Natural Language**: "Show delivery time trends for Frankfurt warehouse over last 3 months"

**Generated SQL**:
```sql
SELECT
  metric_date,
  warehouse_id,
  total_shipments,
  avg_delivery_time_hours,
  p95_delivery_time_hours,
  ROUND(100.0 * delivered_on_time / total_shipments, 2) as on_time_pct
FROM iceberg.daily_shipment_metrics
WHERE warehouse_id = 'WAREHOUSE_FRA'
  AND metric_date >= CURRENT_DATE - INTERVAL '90' DAY
ORDER BY metric_date ASC;
```

### Query 5: Hybrid Query (Real-time + Historical)

**Natural Language**: "How does today's current delivery rate compare to our historical average?"

**Approach**: Query Kafka Streams for today's current rate, Iceberg for historical average, join in application code.

**Kafka Streams Query** (via Interactive Queries API):
```java
ReadOnlyKeyValueStore<String, DeliveryStats> store =
    streams.store(StoreQueryParameters.fromNameAndType(
        "hourly-delivery-performance",
        QueryableStoreTypes.keyValueStore()
    ));

DeliveryStats currentHour = store.get("WAREHOUSE_FRA_" + currentHour);
```

**Iceberg Query** (via Trino):
```sql
SELECT
  AVG(CAST(delivered_on_time AS DOUBLE) / total_shipments) as historical_avg_rate
FROM iceberg.daily_shipment_metrics
WHERE warehouse_id = 'WAREHOUSE_FRA'
  AND metric_date >= CURRENT_DATE - INTERVAL '90' DAY;
```

**Application Code**:
```java
double currentRate = currentHour.getOnTimeCount() / currentHour.getTotalCount();
double historicalAvg = icebergQuery.getHistoricalAverage();
double comparison = (currentRate - historicalAvg) / historicalAvg * 100;

return String.format("Current on-time rate: %.1f%% (%.1f%% vs. 90-day average)",
    currentRate * 100, comparison);
```

---

## Performance Considerations

### Partitioning Best Practices

1. **Daily Partitions** for high-volume tables:
   - `daily_shipment_metrics`: Partitioned by day
   - `vehicle_performance_history`: Partitioned by day
   - Typical query pattern: last N days

2. **Monthly Partitions** for long-term storage:
   - `customer_order_history`: Partitioned by month
   - Reduces metadata overhead
   - Typical query pattern: last N months

3. **Avoid Over-Partitioning**:
   - Don't partition by multiple dimensions (e.g., date + warehouse)
   - Use Z-ordering or sorting within partitions instead

### Compaction Strategies

**Problem**: Small file accumulation from streaming ingestion

**Solution**: Regular compaction jobs

```sql
-- Spark SQL compaction
CALL iceberg.system.rewrite_data_files(
  table => 'customer_order_history',
  strategy => 'binpack',
  options => map('target-file-size-bytes', '536870912') -- 512 MB
);

-- Schedule daily via cron:
0 2 * * * spark-submit --class CompactionJob compaction.jar
```

### Query Optimization Tips

1. **Partition Pruning**: Always filter on partition columns
   ```sql
   -- Good: Prunes partitions
   WHERE metric_date >= '2024-01-01'

   -- Bad: Full table scan
   WHERE YEAR(metric_date) = 2024
   ```

2. **Predicate Pushdown**: Filter early in query
   ```sql
   -- Good: Filters pushed to storage
   SELECT * FROM table WHERE warehouse_id = 'X' AND metric_date = '2024-01-01'

   -- Bad: Filters after read
   SELECT * FROM (SELECT * FROM table) WHERE warehouse_id = 'X'
   ```

3. **Column Pruning**: Select only needed columns
   ```sql
   -- Good: Reads only 3 columns
   SELECT warehouse_id, total_shipments, on_time_rate FROM table

   -- Bad: Reads all columns
   SELECT * FROM table
   ```

4. **Use Metadata Tables**:
   ```sql
   -- Check partition stats before querying
   SELECT * FROM iceberg.daily_shipment_metrics.partitions;

   -- View file-level stats
   SELECT * FROM iceberg.daily_shipment_metrics.files;
   ```

### Cost Considerations

**Storage Costs** (Example: AWS S3):
- 1 GB/month: $0.023
- 100 GB/month: $2.30
- Estimate: ~50 GB for 2 years of demo data

**Compute Costs**:
- Trino: Minimal (can run on small instances)
- Spark: Moderate (batch jobs, auto-scaling recommended)

**Optimization for Demo**:
- Use MinIO (free, self-hosted)
- Single-node Trino
- Scheduled Spark jobs on small cluster

---

## Future Enhancements

Once the Iceberg integration is stable, consider these extensions:

### 1. Additional Analytical Tables

- **hourly_shipment_metrics**: Higher granularity than daily
- **customer_segments**: Derived table for customer clustering
- **route_performance**: Historical route-level metrics
- **exception_analysis**: Detailed exception cause tracking

### 2. Machine Learning Integration

**Delivery Time Prediction Model**:
```python
# Train model on historical data
from pyspark.ml.regression import RandomForestRegressor

training_data = spark.sql("""
  SELECT
    origin_warehouse_id,
    destination_city,
    total_weight_kg,
    total_items,
    priority,
    delivery_time_hours as label
  FROM iceberg.customer_order_history
  WHERE order_date >= '2023-01-01'
""")

model = RandomForestRegressor().fit(training_data)
model.save("models/delivery_time_predictor")
```

### 3. Real-time OLAP with Materialized Views

**Incremental materialized views** (Iceberg 1.4+):
```sql
CREATE MATERIALIZED VIEW customer_monthly_summary
PARTITION BY month(order_date)
AS
SELECT
  DATE_TRUNC('month', order_date) as month,
  customer_id,
  COUNT(*) as order_count,
  AVG(delivery_time_hours) as avg_delivery_time
FROM customer_order_history
GROUP BY 1, 2;

-- Refresh incrementally
REFRESH MATERIALIZED VIEW customer_monthly_summary;
```

### 4. Data Lake Evolution

**Branch and Tag Management** for experimentation:
```sql
-- Create branch for what-if analysis
ALTER TABLE daily_shipment_metrics CREATE BRANCH experiment_branch;

-- Run experiments on branch
INSERT INTO daily_shipment_metrics.branch_experiment_branch ...;

-- Merge successful experiments
ALTER TABLE daily_shipment_metrics
  MERGE BRANCH experiment_branch INTO main;
```

### 5. Advanced Analytics

- **Anomaly detection**: Statistical analysis on historical patterns
- **Forecasting**: Time-series predictions for capacity planning
- **Customer churn prediction**: ML model on order history
- **Route optimization**: Historical performance-based routing

---

## Conclusion

This appendix provides a complete roadmap for enhancing the SmartShip Logistics demo with Apache Iceberg historical analytics. The enhancement is designed to be added incrementally after the base real-time system is stable, and provides significant value for:

- Long-term trend analysis and reporting
- Customer behavior analytics
- Machine learning and predictive capabilities
- Compliance and audit requirements

**Recommended Implementation Order**:
1. Set up MinIO and Polaris catalog (1 day)
2. Create Iceberg table schemas (1 day)
3. Implement daily batch aggregation jobs (2-3 days)
4. Deploy Trino for SQL queries (1 day)
5. Extend LLM query API to support historical queries (2-3 days)
6. Test and validate end-to-end (1-2 days)

**Total Estimated Effort**: 1-2 weeks after base system is complete.
