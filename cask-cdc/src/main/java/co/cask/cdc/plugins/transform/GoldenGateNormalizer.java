/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdc.plugins.transform;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.Transform;
import co.cask.cdap.format.StructuredRecordStringConverter;
import co.cask.cdc.plugins.common.AvroConverter;
import co.cask.cdc.plugins.common.SchemaUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Normalizer plugin to process the events from the Golden Gate Kafka topic.
 */
@Plugin(type = Transform.PLUGIN_TYPE)
@Name("GoldenGateNormalizer")
public class GoldenGateNormalizer extends Transform<StructuredRecord, StructuredRecord> {
  private static Logger LOG = LoggerFactory.getLogger(GoldenGateNormalizer.class);
  private static Gson GSON = new Gson();
  private static final Schema DDL_SCHEMA = Schema.recordOf("DDLRecord",
                                                           Schema.Field.of("table", Schema.of(Schema.Type.STRING)),
                                                           Schema.Field.of("schema", Schema.of(Schema.Type.STRING)));

  private final GoldenGateNormalizerConfig config;

  public GoldenGateNormalizer(GoldenGateNormalizerConfig config) {
    this.config = config;
  }

  @Override
  public void transform(StructuredRecord input, Emitter<StructuredRecord> emitter) throws Exception {
    byte[] message = input.get(config.getInputField());
    if (message == null) {
      throw new IllegalStateException(String.format("Input record does not contain the field '%s'.",
                                                    config.getInputField()));
    }

    if (input.getSchema().getRecordName().equals("GenericWrapperSchema")) {
      // Do nothing for the generic wrapper schema message
      return;
    }

    String messageBody = new String(message, StandardCharsets.UTF_8);
    if (input.getSchema().getRecordName().equals("DDLRecord")) {
      JsonObject schemaObj = GSON.fromJson(messageBody, JsonObject.class);
      String namespaceName = schemaObj.get("namespace").getAsString();
      String tableName = schemaObj.get("name").getAsString();
      String namespacedTableName = namespaceName + "_" + tableName;
      StructuredRecord.Builder builder = StructuredRecord.builder(DDL_SCHEMA);
      builder.set("table", namespacedTableName);
      builder.set("schema", getNormalizedDDLSchema(messageBody));
      emitter.emit(builder.build());
      return;
    }

    // Current message is the Wrapped Avro binary message
    // Get the state map

    StructuredRecord stateRecord = input.get("staterecord");
    Map<Long, String> schemaCacheMap = stateRecord.get("data");
    org.apache.avro.Schema avroGenericWrapperSchema = SchemaUtils.getGenericWrapperMessageSchema();

    GenericRecord genericRecord = getRecord(message, avroGenericWrapperSchema);
    String tableName = genericRecord.get("table_name").toString();
    long schameHashId = (Long) genericRecord.get("schema_fingerprint");

    byte[] payload = genericRecord.get("payload") instanceof ByteBuffer
      ? Bytes.toBytes((ByteBuffer) genericRecord.get("payload"))
      : (byte[]) genericRecord.get("payload");

    LOG.info("Got tableName {} and fingerPrint {} in wrapped schema.", tableName, schameHashId);
    org.apache.avro.Schema avroSchema = new org.apache.avro.Schema.Parser().parse(schemaCacheMap.get(schameHashId));
    LOG.info("Got avro schema {}", avroSchema);

    StructuredRecord structuredRecord = AvroConverter.fromAvroRecord(getRecord(payload, avroSchema),
                                                                     AvroConverter.fromAvroSchema(avroSchema));

    LOG.info("Emitting Structured Record {}", StructuredRecordStringConverter.toJsonString(structuredRecord));
    emitter.emit(getNormalizedDMLRecord(structuredRecord, getNormalizedDDLSchema(schemaCacheMap.get(schameHashId))));

  }

  public static class GoldenGateNormalizerConfig extends PluginConfig {
    @Name("inputField")
    @Description("Input field containing the payload of the message. Defaults to 'message'.")
    private String inputField;

    public GoldenGateNormalizerConfig(String inputField) {
      this.inputField = inputField;
    }

    public String getInputField() {
      if (inputField == null || inputField.trim().length() == 0) {
        return "message";
      }
      return inputField;
    }
  }

  private GenericRecord getRecord(byte[] message, org.apache.avro.Schema schema) throws IOException {
    LOG.info("Schema while getting record {}", schema);
    GenericDatumReader<GenericRecord> datumReader = new GenericDatumReader<>(schema);
    return datumReader.read(null, DecoderFactory.get().binaryDecoder(message, null));
  }

  public static String getNormalizedDDLSchema(String jsonSchema) {
    org.apache.avro.Schema avroSchema = new org.apache.avro.Schema.Parser().parse(jsonSchema);
    Schema schema = AvroConverter.fromAvroSchema(avroSchema);
    Schema column = schema.getField("before").getSchema().getNonNullable();

    List<Schema.Field> columnFields = new ArrayList<>();
    for (Schema.Field field : column.getFields()) {
      if (!field.getName().endsWith("_isMissing")) {
        columnFields.add(field);
      }
    }

    LOG.info("Schema for DDL {}", Schema.recordOf("columns", columnFields).toString());
    return Schema.recordOf("columns", columnFields).toString();
  }

  public static StructuredRecord getNormalizedDMLRecord(StructuredRecord record, String schema) {
    String namespacedTableName = record.get("table");
    namespacedTableName = namespacedTableName.replace('.', '_');
    List<String> primaryKeys = record.get("primary_keys");
    String opType = record.get("op_type");
    Map<Schema.Field, Object> suppliedFieldValues = new HashMap<>();
    switch(opType) {
      case "I":
        StructuredRecord insertRecord = record.get("after");
        for (co.cask.cdap.api.data.schema.Schema.Field field : insertRecord.getSchema().getFields()) {
          if (!field.getName().endsWith("_isMissing")) {
            suppliedFieldValues.put(field, insertRecord.get(field.getName()));
          }
        }
        break;
      case "U":
        StructuredRecord updateRecord = record.get("after");
        for (co.cask.cdap.api.data.schema.Schema.Field field : updateRecord.getSchema().getFields()) {
          if (!field.getName().endsWith("_isMissing")) {
            String fieldName = field.getName();
            if (updateRecord.get(fieldName + "_isMissing") != true) {
              suppliedFieldValues.put(field, updateRecord.get(field.getName()));
            }
          }
        }
        break;
      case "D":
        StructuredRecord deleteRecord = record.get("before");
        for (co.cask.cdap.api.data.schema.Schema.Field field : deleteRecord.getSchema().getFields()) {
          if (!field.getName().endsWith("_isMissing")) {
            suppliedFieldValues.put(field, deleteRecord.get(field.getName()));
          }
        }
        break;
      default:
        break;
    }

    Schema changeSchema = Schema.recordOf("change", suppliedFieldValues.keySet());
    StructuredRecord.Builder changeBuilder = StructuredRecord.builder(changeSchema);
    for(Map.Entry<Schema.Field, Object> entry : suppliedFieldValues.entrySet()) {
      changeBuilder.set(entry.getKey().getName(), entry.getValue());
    }

    Schema dmlSchema = Schema.recordOf("DMLRecord", Schema.Field.of("table", Schema.of(Schema.Type.STRING)),
                                       Schema.Field.of("schema", Schema.of(Schema.Type.STRING)),
                                       Schema.Field.of("op_type", Schema.of(Schema.Type.STRING)),
                                       Schema.Field.of("primary_keys", Schema.arrayOf(Schema.of(Schema.Type.STRING))),
                                       Schema.Field.of("change", changeSchema));

    StructuredRecord.Builder builder = StructuredRecord.builder(dmlSchema);
    builder.set("schema", schema);
    builder.set("table", namespacedTableName);
    builder.set("op_type", opType);
    builder.set("primary_keys", primaryKeys);
    builder.set("change", changeBuilder.build());
    return builder.build();
  }
}
