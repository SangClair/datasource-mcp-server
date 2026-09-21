package com.dameng.mcp.config;

import com.dameng.mcp.adapter.kafka.KafkaConnection;
import com.dameng.mcp.adapter.redis.RedisConnection;
import com.dameng.mcp.model.DataSourceInfo;
import com.dameng.mcp.model.QueryResult;
import com.dameng.mcp.model.TableDefinition;
import com.dameng.mcp.model.TableInfo;
import com.dameng.mcp.model.mcp.ToolResponse;
import com.dameng.mcp.service.DatabaseMetadataService;
import com.dameng.mcp.service.DatabaseQueryService;
import com.dameng.mcp.service.DatabaseStatisticsService;
import com.dameng.mcp.service.DatabaseWriteService;
import com.dameng.mcp.service.ElasticsearchToolService;
import com.dameng.mcp.service.KafkaToolService;
import com.dameng.mcp.service.RedisToolService;
import com.dameng.mcp.util.CursorCodec;
import com.dameng.mcp.util.PageSlice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.dameng.mcp.config.McpToolSpecFactory.inputSchema;
import static com.dameng.mcp.config.McpToolSpecFactory.objectData;
import static com.dameng.mcp.config.McpToolSpecFactory.property;

@Configuration
@EnableConfigurationProperties(McpToolProperties.class)
public class McpToolConfig {

    private static final Map<String, Object> STRING_SCHEMA = Map.of("type", "string");
    private static final Map<String, Object> INTEGER_SCHEMA = Map.of("type", "integer");
    private static final Map<String, Object> NUMBER_SCHEMA = Map.of("type", "number");
    private static final Map<String, Object> BOOLEAN_SCHEMA = Map.of("type", "boolean");
    private static final Map<String, Object> OPEN_OBJECT_SCHEMA = objectData(Map.of());
    private static final Map<String, Object> DATASOURCE_INFO_SCHEMA = objectData(props(
            "name", STRING_SCHEMA, "description", nullable(STRING_SCHEMA), "type", STRING_SCHEMA,
            "readonly", BOOLEAN_SCHEMA, "dynamic", BOOLEAN_SCHEMA),
            "name", "type", "readonly", "dynamic");
    private static final Map<String, Object> TABLE_INFO_SCHEMA = objectData(props(
            "schemaName", STRING_SCHEMA, "tableName", STRING_SCHEMA, "comments", nullable(STRING_SCHEMA)),
            "schemaName", "tableName");
    private static final Map<String, Object> COLUMN_INFO_SCHEMA = objectData(props(
            "columnName", STRING_SCHEMA, "dataType", STRING_SCHEMA, "columnSize", nullable(INTEGER_SCHEMA),
            "decimalDigits", nullable(INTEGER_SCHEMA), "nullable", BOOLEAN_SCHEMA,
            "defaultValue", nullable(STRING_SCHEMA), "comments", nullable(STRING_SCHEMA)),
            "columnName", "dataType", "nullable");
    private static final Map<String, Object> ES_FIELD_SCHEMA = objectData(props(
            "field", STRING_SCHEMA, "type", STRING_SCHEMA), "field", "type");
    private static final Map<String, Object> ES_HIT_SCHEMA = objectData(props(
            "id", STRING_SCHEMA, "score", nullable(NUMBER_SCHEMA), "source", Map.of()), "id", "source");
    private static final Map<String, Object> KAFKA_GROUP_SCHEMA = objectData(props(
            "groupId", STRING_SCHEMA, "state", STRING_SCHEMA, "members", INTEGER_SCHEMA,
            "totalLag", INTEGER_SCHEMA), "groupId", "state", "members", "totalLag");
    private static final Map<String, Object> KAFKA_PARTITION_SCHEMA = objectData(props(
            "partition", INTEGER_SCHEMA, "leader", INTEGER_SCHEMA,
            "replicas", arrayOf(INTEGER_SCHEMA), "isr", arrayOf(INTEGER_SCHEMA)),
            "partition", "leader", "replicas", "isr");
    private static final Map<String, Object> KAFKA_MESSAGE_SCHEMA = objectData(props(
            "partition", INTEGER_SCHEMA, "offset", INTEGER_SCHEMA, "timestamp", INTEGER_SCHEMA,
            "key", nullable(STRING_SCHEMA), "value", nullable(STRING_SCHEMA)),
            "partition", "offset", "timestamp");

    @Bean
    public McpToolSpecFactory mcpToolSpecFactory(ObjectMapper objectMapper, McpToolProperties properties) {
        return new McpToolSpecFactory(objectMapper, properties);
    }

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> databaseTools(
            McpToolSpecFactory factory, McpToolProperties properties, ObjectMapper objectMapper,
            DatabaseMetadataService metadata, DatabaseQueryService query,
            DatabaseStatisticsService statistics, DatabaseWriteService write,
            ElasticsearchToolService elasticsearch, RedisToolService redis, KafkaToolService kafka) {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        addDatabaseReadTools(tools, factory, properties, objectMapper, metadata, query, statistics);
        addDatabaseWriteTools(tools, factory, write);
        addElasticsearchTools(tools, factory, properties, objectMapper, elasticsearch);
        addRedisTools(tools, factory, properties, redis);
        addKafkaTools(tools, factory, properties, objectMapper, kafka);
        return List.copyOf(tools);
    }

    private void addDatabaseReadTools(List<McpServerFeatures.SyncToolSpecification> tools,
                                      McpToolSpecFactory factory, McpToolProperties properties,
                                      ObjectMapper mapper, DatabaseMetadataService metadata,
                                      DatabaseQueryService query, DatabaseStatisticsService statistics) {
        tools.add(factory.create("datasource_list", "List data sources",
                "List configured data sources with type, read-only state and description. Results are paginated.",
                pagedInput(), arrayData("items", DATASOURCE_INFO_SCHEMA), true, args -> {
                    PageSlice<DataSourceInfo> page = page(metadata.listDatasourceRecords(), args, properties);
                    return ok(map("items", page.items()), pageMeta(null, page));
                }));
        tools.add(factory.create("db_list_schemas", "List database schemas",
                "List schemas available in a relational database data source. Results are paginated.",
                pagedDatasourceInput("Relational"), arrayData("items", STRING_SCHEMA), true, args -> {
                    String datasource = stringArg(args, "datasource");
                    PageSlice<String> page = page(metadata.listSchemaRecords(datasource), args, properties);
                    return ok(map("items", page.items()), pageMeta(metadata.resolvedDatasource(datasource), page));
                }));
        tools.add(factory.create("db_list_tables", "List database tables",
                "List tables and comments in a schema. Results are paginated.",
                inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                        "schema", stringProperty("Schema name"), "cursor", cursorProperty(),
                        "limit", limitProperty()), "schema"), arrayData("items", TABLE_INFO_SCHEMA), true, args -> {
                    String datasource = stringArg(args, "datasource");
                    List<TableInfo> records = metadata.listTableRecords(datasource, requiredString(args, "schema"));
                    PageSlice<TableInfo> page = page(records, args, properties);
                    return ok(map("items", page.items()), pageMeta(metadata.resolvedDatasource(datasource), page));
                }));
        tools.add(factory.create("db_describe_table", "Describe database table",
                "Return table metadata and a paginated list of columns.",
                inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                        "schema", stringProperty("Schema name"), "table", stringProperty("Table name"),
                        "cursor", cursorProperty(), "limit", limitProperty()), "schema", "table"),
                objectData(props("schema", nullable(STRING_SCHEMA), "table", nullable(STRING_SCHEMA),
                        "comments", nullable(STRING_SCHEMA), "columns", arrayOf(COLUMN_INFO_SCHEMA)), "columns"),
                true, args -> {
                    String datasource = stringArg(args, "datasource");
                    TableDefinition definition = metadata.describeTableRecord(datasource,
                            requiredString(args, "schema"), requiredString(args, "table"));
                    List<?> columns = definition == null || definition.getColumns() == null
                            ? List.of() : definition.getColumns();
                    PageSlice<?> page = page(columns, args, properties);
                    return ok(map("schema", definition == null ? null : definition.getSchemaName(),
                                    "table", definition == null ? null : definition.getTableName(),
                                    "comments", definition == null ? null : definition.getComments(),
                                    "columns", page.items()),
                            pageMeta(metadata.resolvedDatasource(datasource), page));
                }));
        tools.add(factory.create("db_query", "Query database",
                "Execute one read-only SQL query. Rows and cell values are bounded; use SQL predicates for subsequent pages.",
                inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                        "sql", stringProperty("Read-only SELECT, WITH, EXPLAIN or SHOW SQL"),
                        "limit", limitProperty()), "sql"),
                objectData(props("columns", arrayOf(STRING_SCHEMA), "rows", arrayOf(OPEN_OBJECT_SCHEMA)),
                        "columns", "rows"), true, args -> {
                    String datasource = stringArg(args, "datasource");
                    int limit = properties.normalizeLimit(intArg(args, "limit", null));
                    QueryResult result = query.executeQueryRecord(datasource, requiredString(args, "sql"), limit + 1);
                    List<Map<String, Object>> rows = result == null || result.getRows() == null
                            ? List.of() : result.getRows();
                    boolean hasMore = rows.size() > limit;
                    BoundedRows bounded = boundRows(rows.subList(0, Math.min(limit, rows.size())),
                            properties.getMaxItemChars());
                    return ok(map("columns", result == null ? List.of() : result.getColumns(), "rows", bounded.rows()),
                            map("datasource", query.resolvedDatasource(datasource),
                                    "returned_count", bounded.rows().size(), "limit", limit,
                                    "has_more", hasMore, "truncated_cells", bounded.truncatedCells()));
                }));
        tools.add(factory.create("db_sample_rows", "Sample table rows",
                "Return a bounded sample of rows from one table.",
                inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                        "schema", stringProperty("Schema name"), "table", stringProperty("Table name"),
                        "limit", limitProperty()), "schema", "table"),
                objectData(props("rows", arrayOf(OPEN_OBJECT_SCHEMA)), "rows"), true, args -> {
                    String datasource = stringArg(args, "datasource");
                    int limit = properties.normalizeLimit(intArg(args, "limit", null));
                    List<Map<String, Object>> rows = query.sampleRows(datasource, requiredString(args, "schema"),
                            requiredString(args, "table"), limit + 1);
                    boolean hasMore = rows.size() > limit;
                    BoundedRows bounded = boundRows(rows.subList(0, Math.min(limit, rows.size())),
                            properties.getMaxItemChars());
                    return ok(map("rows", bounded.rows()), map("datasource", query.resolvedDatasource(datasource),
                            "returned_count", bounded.rows().size(), "limit", limit,
                            "has_more", hasMore, "truncated_cells", bounded.truncatedCells()));
                }));
        tools.add(factory.create("db_table_statistics", "Get table statistics",
                "Return row count and available size statistics for a table.", tableInput(),
                objectData(props("schemaName", STRING_SCHEMA, "tableName", STRING_SCHEMA,
                        "rowCount", INTEGER_SCHEMA, "tableSize", nullable(STRING_SCHEMA)),
                        "schemaName", "tableName", "rowCount"), true, args -> ok(mapper.convertValue(
                        statistics.tableStatisticsRecord(stringArg(args, "datasource"),
                                requiredString(args, "schema"), requiredString(args, "table")), Map.class), Map.of())));
        tools.add(factory.create("db_column_statistics", "Get column statistics",
                "Return distinct count, null count, minimum and maximum for a column.",
                inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                        "schema", stringProperty("Schema name"), "table", stringProperty("Table name"),
                        "column", stringProperty("Column name")), "schema", "table", "column"),
                objectData(props("schemaName", STRING_SCHEMA, "tableName", STRING_SCHEMA,
                        "columnName", STRING_SCHEMA, "distinctCount", INTEGER_SCHEMA,
                        "nullCount", INTEGER_SCHEMA, "minValue", nullable(STRING_SCHEMA),
                        "maxValue", nullable(STRING_SCHEMA)),
                        "schemaName", "tableName", "columnName", "distinctCount", "nullCount"),
                true, args -> ok(mapper.convertValue(
                        statistics.columnStatisticsRecord(stringArg(args, "datasource"),
                                requiredString(args, "schema"), requiredString(args, "table"),
                                requiredString(args, "column")), Map.class), Map.of())));
    }

    private void addDatabaseWriteTools(List<McpServerFeatures.SyncToolSpecification> tools,
                                       McpToolSpecFactory factory, DatabaseWriteService write) {
        tools.add(writeTool(factory, "db_insert", "Insert database rows", "INSERT", write));
        tools.add(writeTool(factory, "db_update", "Update database rows", "UPDATE", write));
        tools.add(writeTool(factory, "db_delete", "Delete database rows", "DELETE", write));
        tools.add(factory.create("db_execute_sql", "Execute arbitrary SQL",
                "Execute one arbitrary SQL statement on a writable relational data source. This is destructive and server-side user confirmation is not enforced.",
                sqlInput(), objectData(props("datasource", STRING_SCHEMA, "statement_type", STRING_SCHEMA,
                        "unsafe_scope", BOOLEAN_SCHEMA), "datasource", "statement_type", "unsafe_scope"),
                false, args -> {
                    DatabaseWriteService.SqlOperationResult result = write.executeSqlRecord(
                            stringArg(args, "datasource"), requiredString(args, "sql"));
                    return ToolResponse.ok(map("datasource", result.datasource(),
                                    "statement_type", result.statementType(), "unsafe_scope", result.unsafeScope()),
                            result.warnings(), map("duration_ms", result.durationMs(),
                                    "unsafe_scope", result.unsafeScope()));
                }));
    }

    private McpServerFeatures.SyncToolSpecification writeTool(
            McpToolSpecFactory factory, String name, String title, String operation, DatabaseWriteService write) {
        return factory.create(name, title,
                "Execute one " + operation + " statement on a writable relational data source. Statements without WHERE are warned but not blocked.",
                sqlInput(), objectData(props("datasource", STRING_SCHEMA, "operation", STRING_SCHEMA,
                        "affected_rows", INTEGER_SCHEMA, "unsafe_scope", BOOLEAN_SCHEMA),
                        "datasource", "operation", "affected_rows", "unsafe_scope"), false, args -> {
                    DatabaseWriteService.WriteOperationResult result = write.executeWriteRecord(
                            stringArg(args, "datasource"), requiredString(args, "sql"), operation);
                    return ToolResponse.ok(map("datasource", result.datasource(), "operation", result.operation(),
                                    "affected_rows", result.affectedRows(), "unsafe_scope", result.unsafeScope()),
                            result.warnings(), map("duration_ms", result.durationMs(),
                                    "unsafe_scope", result.unsafeScope()));
                });
    }

    private void addElasticsearchTools(List<McpServerFeatures.SyncToolSpecification> tools,
                                       McpToolSpecFactory factory, McpToolProperties properties,
                                       ObjectMapper mapper, ElasticsearchToolService service) {
        tools.add(factory.create("es_list_indices", "List Elasticsearch indices",
                "List indices with health, status, document count and storage size. Results are paginated.",
                pagedDatasourceInput("Elasticsearch"), arrayData("items", OPEN_OBJECT_SCHEMA), true, args -> {
                    JsonNode node = service.listIndicesRecord(stringArg(args, "datasource"));
                    List<Map<String, Object>> indices = new ArrayList<>();
                    if (node != null && node.isArray()) {
                        node.forEach(item -> indices.add(mapper.convertValue(item, Map.class)));
                    }
                    PageSlice<Map<String, Object>> page = page(indices, args, properties);
                    return ok(map("items", page.items()), pageMeta(stringArg(args, "datasource"), page));
                }));
        tools.add(factory.create("es_get_mapping", "Get Elasticsearch mapping",
                "Return a paginated flattened field mapping for an index.",
                inputSchema(props("datasource", stringProperty("Elasticsearch data source name; omit for default"),
                        "index", stringProperty("Index name"), "cursor", cursorProperty(),
                        "limit", limitProperty()), "index"),
                objectData(props("index", STRING_SCHEMA, "fields", arrayOf(ES_FIELD_SCHEMA)),
                        "index", "fields"), true, args -> {
                    String index = requiredString(args, "index");
                    List<Map<String, Object>> fields = flattenMapping(
                            service.mappingRecord(stringArg(args, "datasource"), index), index);
                    PageSlice<Map<String, Object>> page = page(fields, args, properties);
                    return ok(map("index", index, "fields", page.items()),
                            pageMeta(stringArg(args, "datasource"), page));
                }));
        tools.add(factory.create("es_count_documents", "Count Elasticsearch documents",
                "Count documents in an index, optionally using a Query DSL JSON body.",
                inputSchema(props("datasource", stringProperty("Elasticsearch data source name; omit for default"),
                        "index", stringProperty("Index name"), "query", stringProperty("Optional Query DSL JSON")),
                        "index"), objectData(props("index", STRING_SCHEMA, "count", INTEGER_SCHEMA),
                        "index", "count"), true, args -> ok(map("index", requiredString(args, "index"),
                        "count", service.countRecord(stringArg(args, "datasource"), requiredString(args, "index"),
                                stringArg(args, "query"))), Map.of())));
        tools.add(factory.create("es_search", "Search Elasticsearch",
                "Search an index with Query DSL. Results use an opaque offset cursor and bounded document sources.",
                inputSchema(props("datasource", stringProperty("Elasticsearch data source name; omit for default"),
                        "index", stringProperty("Index name"), "dsl", stringProperty("Optional Query DSL JSON"),
                        "cursor", cursorProperty(), "limit", limitProperty()), "index"),
                objectData(props("total", INTEGER_SCHEMA, "hits", arrayOf(ES_HIT_SCHEMA)),
                        "total", "hits"), true, args -> {
                    int offset = CursorCodec.decodeOffset(stringArg(args, "cursor"));
                    int limit = properties.normalizeLimit(intArg(args, "limit", null));
                    JsonNode result = service.searchRecord(stringArg(args, "datasource"),
                            requiredString(args, "index"), stringArg(args, "dsl"), offset, limit);
                    long total = result.path("hits").path("total").isObject()
                            ? result.path("hits").path("total").path("value").asLong()
                            : result.path("hits").path("total").asLong();
                    List<Map<String, Object>> hits = new ArrayList<>();
                    result.path("hits").path("hits").forEach(hit -> hits.add(map(
                            "id", hit.path("_id").asText(),
                            "score", hit.path("_score").isNumber() ? hit.path("_score").numberValue() : null,
                            "source", boundedJson(hit.path("_source"), mapper, properties.getMaxItemChars()))));
                    boolean hasMore = offset + hits.size() < total;
                    return ok(map("total", total, "hits", hits), map("returned_count", hits.size(), "limit", limit,
                            "has_more", hasMore, "next_cursor",
                            hasMore ? CursorCodec.encode(Integer.toString(offset + hits.size())) : null));
                }));
        tools.add(factory.create("es_index_document", "Index Elasticsearch document",
                "Create or replace an Elasticsearch document on a writable data source.",
                inputSchema(props("datasource", stringProperty("Elasticsearch data source name; omit for default"),
                        "index", stringProperty("Index name"), "id", stringProperty("Optional document ID"),
                        "document", stringProperty("Document JSON")), "index", "document"),
                esMutationDataSchema(), false, args -> ok(mapper.convertValue(service.indexDocumentRecord(
                        stringArg(args, "datasource"), requiredString(args, "index"), stringArg(args, "id"),
                        requiredString(args, "document")), Map.class), Map.of())));
        tools.add(factory.create("es_delete_document", "Delete Elasticsearch document",
                "Delete one Elasticsearch document from a writable data source.",
                inputSchema(props("datasource", stringProperty("Elasticsearch data source name; omit for default"),
                        "index", stringProperty("Index name"), "id", stringProperty("Document ID")), "index", "id"),
                esMutationDataSchema(), false, args -> ok(mapper.convertValue(service.deleteDocumentRecord(
                        stringArg(args, "datasource"), requiredString(args, "index"),
                        requiredString(args, "id")), Map.class), Map.of())));
    }

    private void addRedisTools(List<McpServerFeatures.SyncToolSpecification> tools,
                               McpToolSpecFactory factory, McpToolProperties properties, RedisToolService service) {
        tools.add(factory.create("redis_scan_keys", "Scan Redis keys",
                "Scan keys without blocking Redis. Continue with next_cursor until has_more is false.",
                inputSchema(props("datasource", stringProperty("Redis data source name; omit for default"),
                        "pattern", stringProperty("Glob pattern; defaults to *"), "cursor", cursorProperty(),
                        "limit", limitProperty())), arrayData("keys", STRING_SCHEMA), true, args -> {
                    int limit = properties.normalizeLimit(intArg(args, "limit", null));
                    RedisConnection.ScanPage<String> page = service.scanKeyRecords(stringArg(args, "datasource"),
                            stringArg(args, "pattern"), CursorCodec.decode(stringArg(args, "cursor"), "0"), limit);
                    return ok(map("keys", page.items()), map("returned_count", page.items().size(), "limit", limit,
                            "has_more", page.hasMore(), "next_cursor",
                            page.hasMore() ? CursorCodec.encode(page.nextCursor()) : null));
                }));
        tools.add(factory.create("redis_get_key_info", "Get Redis key information",
                "Return the Redis type and TTL for one key.", keyInput(),
                objectData(props("key", STRING_SCHEMA, "type", STRING_SCHEMA, "ttl_seconds", INTEGER_SCHEMA),
                        "key", "type", "ttl_seconds"), true,
                args -> ok(service.keyInfoRecord(stringArg(args, "datasource"), requiredString(args, "key")), Map.of())));
        tools.add(factory.create("redis_get_value", "Get Redis value",
                "Read one bounded page of a Redis string, list, set, hash or sorted set value.",
                inputSchema(props("datasource", stringProperty("Redis data source name; omit for default"),
                        "key", stringProperty("Redis key"), "cursor", cursorProperty(),
                        "limit", limitProperty()), "key"),
                objectData(props("key", STRING_SCHEMA, "type", STRING_SCHEMA, "value", Map.of()),
                        "key", "type", "value"), true, args -> {
                    int limit = properties.normalizeLimit(intArg(args, "limit", null));
                    RedisConnection.ValuePage page = service.valueRecord(stringArg(args, "datasource"),
                            requiredString(args, "key"), CursorCodec.decode(stringArg(args, "cursor"), "0"),
                            limit, properties.getMaxItemChars());
                    Object value = boundValue(page.value(), properties.getMaxItemChars());
                    return ok(map("key", requiredString(args, "key"), "type", page.type(), "value", value),
                            map("returned_count", collectionSize(value), "limit", limit,
                                    "has_more", page.hasMore(), "next_cursor",
                                    page.hasMore() ? CursorCodec.encode(page.nextCursor()) : null,
                                    "truncated", page.truncated()));
                }));
        tools.add(factory.create("redis_set_value", "Set Redis string value",
                "Set or overwrite one Redis string key on a writable data source.",
                inputSchema(props("datasource", stringProperty("Redis data source name; omit for default"),
                        "key", stringProperty("Redis key"), "value", stringProperty("String value")), "key", "value"),
                objectData(props("result", STRING_SCHEMA), "result"), false,
                args -> ok(map("result", service.setRecord(
                        stringArg(args, "datasource"), requiredString(args, "key"),
                        requiredString(args, "value"))), Map.of())));
        tools.add(factory.create("redis_delete_key", "Delete Redis key",
                "Delete one Redis key from a writable data source.", keyInput(),
                objectData(props("deleted_count", INTEGER_SCHEMA), "deleted_count"), false,
                args -> ok(map("deleted_count", service.deleteRecord(stringArg(args, "datasource"),
                        requiredString(args, "key"))), Map.of())));
        tools.add(factory.create("redis_set_expiry", "Set Redis key expiry",
                "Set a positive expiry in seconds on one Redis key.",
                inputSchema(props("datasource", stringProperty("Redis data source name; omit for default"),
                        "key", stringProperty("Redis key"), "seconds", integerProperty("Positive TTL in seconds")),
                        "key", "seconds"), objectData(props("updated", BOOLEAN_SCHEMA), "updated"), false,
                args -> ok(map("updated", service.expireRecord(stringArg(args, "datasource"),
                        requiredString(args, "key"), intArg(args, "seconds", 0)) == 1), Map.of())));
    }

    private void addKafkaTools(List<McpServerFeatures.SyncToolSpecification> tools,
                               McpToolSpecFactory factory, McpToolProperties properties,
                               ObjectMapper mapper, KafkaToolService service) {
        tools.add(factory.create("kafka_list_topics", "List Kafka topics",
                "List topic names. Results are sorted and paginated.",
                inputSchema(props("datasource", stringProperty("Kafka data source name; omit for default"),
                        "include_internal", booleanProperty("Include internal topics"),
                        "cursor", cursorProperty(), "limit", limitProperty())),
                arrayData("topics", STRING_SCHEMA), true, args -> {
                    List<String> topics = new ArrayList<>(service.listTopicRecords(stringArg(args, "datasource"),
                            booleanArg(args, "include_internal", false)));
                    topics.sort(String::compareTo);
                    PageSlice<String> page = page(topics, args, properties);
                    return ok(map("topics", page.items()), pageMeta(stringArg(args, "datasource"), page));
                }));
        tools.add(factory.create("kafka_describe_topic", "Describe Kafka topic",
                "Return partitions, replicas, ISR and selected configuration for one topic.",
                inputSchema(props("datasource", stringProperty("Kafka data source name; omit for default"),
                        "topic", stringProperty("Topic name")), "topic"),
                objectData(props("name", STRING_SCHEMA, "internal", BOOLEAN_SCHEMA,
                        "partitions", arrayOf(KAFKA_PARTITION_SCHEMA), "configs", stringMapSchema()),
                        "name", "internal", "partitions", "configs"), true,
                args -> ok(mapper.convertValue(service.topicDetailRecord(stringArg(args, "datasource"),
                        requiredString(args, "topic")), Map.class), Map.of())));
        tools.add(factory.create("kafka_list_consumer_groups", "List Kafka consumer groups",
                "List consumer groups with state, member count and total lag. Results are paginated.",
                pagedDatasourceInput("Kafka"), arrayData("groups", KAFKA_GROUP_SCHEMA), true, args -> {
                    List<KafkaConnection.ConsumerGroupSummary> groups = new ArrayList<>(
                            service.consumerGroupRecords(stringArg(args, "datasource")));
                    groups.sort((a, b) -> a.groupId.compareTo(b.groupId));
                    PageSlice<KafkaConnection.ConsumerGroupSummary> page = page(groups, args, properties);
                    return ok(map("groups", page.items()), pageMeta(stringArg(args, "datasource"), page));
                }));
        tools.add(factory.create("kafka_peek_messages", "Peek Kafka messages",
                "Read recent messages without committing offsets. Keys and values are bounded.",
                inputSchema(props("datasource", stringProperty("Kafka data source name; omit for default"),
                        "topic", stringProperty("Topic name"), "partition", integerProperty("Partition; negative means all"),
                        "limit", limitProperty(), "poll_timeout_ms", integerProperty("Poll timeout; maximum 5000 ms")),
                        "topic"), arrayData("messages", KAFKA_MESSAGE_SCHEMA), true, args -> {
                    int limit = properties.normalizeLimit(intArg(args, "limit", 20));
                    List<KafkaConnection.KafkaRecordView> records = service.peekMessageRecords(
                            stringArg(args, "datasource"), requiredString(args, "topic"),
                            intArg(args, "partition", -1), limit, intArg(args, "poll_timeout_ms", 2000));
                    List<Map<String, Object>> items = new ArrayList<>();
                    int truncated = 0;
                    for (KafkaConnection.KafkaRecordView record : records) {
                        String key = boundedText(record.key, properties.getMaxItemChars());
                        String value = boundedText(record.value, properties.getMaxItemChars());
                        if (!same(record.key, key) || !same(record.value, value)) {
                            truncated++;
                        }
                        items.add(map("partition", record.partition, "offset", record.offset,
                                "timestamp", record.timestamp, "key", key, "value", value));
                    }
                    return ok(map("messages", items), map("returned_count", items.size(),
                            "limit", limit, "truncated_messages", truncated));
                }));
    }

    private static ToolResponse ok(Map<String, Object> data, Map<String, Object> meta) {
        return ToolResponse.ok(data, List.of(), meta);
    }

    private static McpSchema.JsonSchema pagedInput() {
        return inputSchema(props("cursor", cursorProperty(), "limit", limitProperty()));
    }

    private static McpSchema.JsonSchema pagedDatasourceInput(String type) {
        return inputSchema(props("datasource", stringProperty(type + " data source name; omit for default"),
                "cursor", cursorProperty(), "limit", limitProperty()));
    }

    private static McpSchema.JsonSchema tableInput() {
        return inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                "schema", stringProperty("Schema name"), "table", stringProperty("Table name")),
                "schema", "table");
    }

    private static McpSchema.JsonSchema sqlInput() {
        return inputSchema(props("datasource", stringProperty("Relational data source name; omit for default"),
                "sql", stringProperty("One SQL statement")), "sql");
    }

    private static McpSchema.JsonSchema keyInput() {
        return inputSchema(props("datasource", stringProperty("Redis data source name; omit for default"),
                "key", stringProperty("Redis key")), "key");
    }

    private static Map<String, Object> stringProperty(String description) {
        return property("string", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        return property("integer", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return property("boolean", description);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")));
    }

    private static Map<String, Object> arrayOf(Map<String, Object> itemSchema) {
        return Map.of("type", "array", "items", itemSchema);
    }

    private static Map<String, Object> arrayData(String field, Map<String, Object> itemSchema) {
        return objectData(props(field, arrayOf(itemSchema)), field);
    }

    private static Map<String, Object> stringMapSchema() {
        return Map.of("type", "object", "additionalProperties", STRING_SCHEMA);
    }

    private static Map<String, Object> esMutationDataSchema() {
        return objectData(props("_index", STRING_SCHEMA, "_id", STRING_SCHEMA,
                "_version", INTEGER_SCHEMA, "result", STRING_SCHEMA,
                "_seq_no", INTEGER_SCHEMA, "_primary_term", INTEGER_SCHEMA,
                "_shards", OPEN_OBJECT_SCHEMA));
    }

    private static Map<String, Object> cursorProperty() {
        return stringProperty("Opaque cursor returned by the previous call; omit for the first page");
    }

    private static Map<String, Object> limitProperty() {
        Map<String, Object> schema = new LinkedHashMap<>(integerProperty("Maximum results for this page"));
        schema.put("minimum", 1);
        schema.put("maximum", 500);
        return schema;
    }

    private static Map<String, Object> props(Object... values) {
        return map(values);
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) {
                result.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return result;
    }

    private static String stringArg(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value == null ? null : String.valueOf(value);
    }

    private static String requiredString(Map<String, Object> args, String name) {
        String value = stringArg(args, name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }

    private static Integer intArg(Map<String, Object> args, String name, Integer defaultValue) {
        Object value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 必须是整数");
        }
    }

    private static boolean booleanArg(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value);
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        throw new IllegalArgumentException(name + " 必须是布尔值");
    }

    private static <T> PageSlice<T> page(List<T> values, Map<String, Object> args, McpToolProperties properties) {
        return PageSlice.of(values, CursorCodec.decodeOffset(stringArg(args, "cursor")),
                properties.normalizeLimit(intArg(args, "limit", null)));
    }

    private static Map<String, Object> pageMeta(String datasource, PageSlice<?> page) {
        return map("datasource", datasource, "returned_count", page.returnedCount(),
                "has_more", page.hasMore(), "next_cursor", page.nextCursor());
    }

    private static BoundedRows boundRows(List<Map<String, Object>> rows, int maxChars) {
        List<Map<String, Object>> bounded = new ArrayList<>();
        int truncated = 0;
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                Object value = boundValue(entry.getValue(), maxChars);
                if (!String.valueOf(entry.getValue()).equals(String.valueOf(value))) {
                    truncated++;
                }
                copy.put(entry.getKey(), value);
            }
            bounded.add(copy);
        }
        return new BoundedRows(bounded, truncated);
    }

    private static Object boundValue(Object value, int maxChars) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return boundedText(Base64.getEncoder().encodeToString(bytes), maxChars);
        }
        if (value instanceof CharSequence chars) {
            return boundedText(chars.toString(), maxChars);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> boundValue(item, maxChars)).toList();
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> bounded = new LinkedHashMap<>();
            source.forEach((key, item) -> bounded.put(String.valueOf(key), boundValue(item, maxChars)));
            return bounded;
        }
        return value;
    }

    private static String boundedText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...[truncated]";
    }

    private static Object boundedJson(JsonNode node, ObjectMapper mapper, int maxChars) {
        if (node == null || node.isMissingNode()) {
            return Map.of();
        }
        String json = node.toString();
        return json.length() <= maxChars ? mapper.convertValue(node, Object.class) : boundedText(json, maxChars);
    }

    private static int collectionSize(Object value) {
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return value == null ? 0 : 1;
    }

    private static boolean same(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static List<Map<String, Object>> flattenMapping(JsonNode root, String index) {
        JsonNode indexNode = root == null ? null : root.path(index);
        if (indexNode == null || indexNode.isMissingNode()) {
            indexNode = root != null && root.fields().hasNext() ? root.fields().next().getValue() : null;
        }
        JsonNode properties = indexNode == null ? null : indexNode.path("mappings").path("properties");
        List<Map<String, Object>> fields = new ArrayList<>();
        flattenProperties(properties, "", fields);
        return fields;
    }

    private static void flattenProperties(JsonNode properties, String prefix, List<Map<String, Object>> result) {
        if (properties == null || !properties.isObject()) {
            return;
        }
        properties.fields().forEachRemaining(entry -> {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            JsonNode definition = entry.getValue();
            result.add(map("field", path, "type", definition.path("type").asText(
                    definition.has("properties") ? "object" : "unknown")));
            flattenProperties(definition.path("properties"), path, result);
        });
    }

    private record BoundedRows(List<Map<String, Object>> rows, int truncatedCells) {
    }
}
