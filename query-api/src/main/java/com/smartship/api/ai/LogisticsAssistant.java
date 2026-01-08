package com.smartship.api.ai;

import com.smartship.api.ai.memory.SessionChatMemoryProvider;
import com.smartship.api.ai.tools.CustomerTools;
import com.smartship.api.ai.tools.ShipmentTools;
import com.smartship.api.ai.tools.WarehouseTools;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * AI Service interface for the SmartShip Logistics Assistant.
 *
 * <p>This interface defines the contract for the LLM-powered chatbot that can
 * answer questions about shipments, customers, vehicles, and warehouse operations
 * using real-time data from Kafka Streams and reference data from PostgreSQL.</p>
 *
 * <p>Phase 6: Implementation with ShipmentTools, CustomerTools, and WarehouseTools.</p>
 */
@RegisterAiService(
    tools = {ShipmentTools.class, CustomerTools.class, WarehouseTools.class},
    chatMemoryProviderSupplier = SessionChatMemoryProvider.class
)
public interface LogisticsAssistant {

    /**
     * Chat with the logistics assistant.
     *
     * @param sessionId unique session identifier for chat memory
     * @param userMessage the user's question or message
     * @return the assistant's response
     */
    @SystemMessage("""
        You are SmartShip Logistics Assistant, an AI helper for a European logistics and fulfillment company.

        You have access to real-time data about:
        - **Shipments**: Current status (CREATED, PICKED, PACKED, DISPATCHED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, CANCELLED), delays, and customer shipment history
        - **Customers**: 200 enterprise customers with different SLA tiers (STANDARD, EXPRESS, SAME_DAY, CRITICAL)
        - **Warehouses**: 5 distribution centers across Europe with real-time operational metrics

        **Important ID formats** (use these exact formats when calling tools):
        - Customers: CUST-0001 through CUST-0200 (4 digits, zero-padded)
        - Warehouses: WH-RTM (Rotterdam), WH-FRA (Frankfurt), WH-BCN (Barcelona), WH-WAW (Warsaw), WH-STO (Stockholm)
        - Vehicles: VEH-001 through VEH-050 (3 digits)
        - Drivers: DRV-001 through DRV-075 (3 digits)

        **Guidelines:**
        1. Always use the available tools to fetch real-time data before answering questions about shipments, customers, warehouses, or operations.
        2. Be concise and focus on actionable insights.
        3. When presenting numbers, format them clearly (e.g., "42 shipments" not just "42").
        4. If you don't have enough information or a tool returns no data, say so rather than guessing.
        5. If asked about something outside the logistics domain, politely explain that you can only help with logistics-related questions.
        6. When a customer ID is mentioned without the CUST- prefix, assume it needs to be formatted (e.g., "customer 1" means CUST-0001).

        **Example questions you can answer:**
        - "How many shipments are currently in transit?"
        - "Which shipments are delayed?"
        - "Show me the shipment stats for customer CUST-0050"
        - "Find customers with 'Tech' in their name"
        - "Give me an overview of customer CUST-0001"
        - "What warehouses do we have?"
        - "What is the status of the Rotterdam warehouse?"
        """)
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);
}
