/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.types;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.iceberg.Schema;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableSet;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;

public class TypeUtil {

  private static final int HEADER_SIZE = 12;

  private TypeUtil() {}

  /**
   * Project extracts particular fields from a schema by ID.
   *
   * <p>Unlike {@link TypeUtil#select(Schema, Set)}, project will pick out only the fields
   * enumerated. Structs that are explicitly projected are empty unless sub-fields are explicitly
   * projected. Maps and lists cannot be explicitly selected in fieldIds.
   *
   * @param schema to project fields from
   * @param fieldIds list of explicit fields to extract
   * @return the schema with all fields fields not selected removed
   */
  public static Schema project(Schema schema, Set<Integer> fieldIds) {
    Preconditions.checkNotNull(schema, "Schema cannot be null");

    Types.StructType result = project(schema.asStruct(), fieldIds);
    if (schema.asStruct().equals(result)) {
      return schema;
    } else if (result != null) {
      if (schema.getAliases() != null) {
        return new Schema(result.fields(), schema.getAliases());
      } else {
        return new Schema(result.fields());
      }
    }
    return new Schema(Collections.emptyList(), schema.getAliases());
  }

  public static Types.StructType project(Types.StructType struct, Set<Integer> fieldIds) {
    Preconditions.checkNotNull(struct, "Struct cannot be null");
    Preconditions.checkNotNull(fieldIds, "Field ids cannot be null");

    Type result = visit(struct, new PruneColumns(fieldIds, false));
    if (struct.equals(result)) {
      return struct;
    } else if (result != null) {
      return result.asStructType();
    }

    return Types.StructType.of();
  }

  public static Schema select(Schema schema, Set<Integer> fieldIds) {
    Preconditions.checkNotNull(schema, "Schema cannot be null");

    Types.StructType result = select(schema.asStruct(), fieldIds);
    if (Objects.equals(schema.asStruct(), result)) {
      return schema;
    } else if (result != null) {
      if (schema.getAliases() != null) {
        return new Schema(result.fields(), schema.getAliases());
      } else {
        return new Schema(result.fields());
      }
    }

    return new Schema(ImmutableList.of(), schema.getAliases());
  }

  public static Types.StructType select(Types.StructType struct, Set<Integer> fieldIds) {
    Preconditions.checkNotNull(struct, "Struct cannot be null");
    Preconditions.checkNotNull(fieldIds, "Field ids cannot be null");

    Type result = visit(struct, new PruneColumns(fieldIds, true));
    if (struct.equals(result)) {
      return struct;
    } else if (result != null) {
      return result.asStructType();
    }

    return Types.StructType.of();
  }

  public static Set<Integer> getProjectedIds(Schema schema) {
    return ImmutableSet.copyOf(getIdsInternal(schema.asStruct(), true));
  }

  public static Set<Integer> getProjectedIds(Type type) {
    if (type.isPrimitiveType()) {
      return ImmutableSet.of();
    }
    return ImmutableSet.copyOf(getIdsInternal(type, true));
  }

  private static Set<Integer> getIdsInternal(Type type, boolean includeStructIds) {
    return visit(type, new GetProjectedIds(includeStructIds));
  }

  public static Types.StructType selectNot(Types.StructType struct, Set<Integer> fieldIds) {
    Set<Integer> projectedIds = getIdsInternal(struct, false);
    projectedIds.removeAll(fieldIds);
    return project(struct, projectedIds);
  }

  public static Schema selectNot(Schema schema, Set<Integer> fieldIds) {
    Set<Integer> projectedIds = getIdsInternal(schema.asStruct(), false);
    projectedIds.removeAll(fieldIds);
    return project(schema, projectedIds);
  }

  public static Schema join(Schema left, Schema right) {
    List<Types.NestedField> joinedColumns = Lists.newArrayList(left.columns());
    for (Types.NestedField rightColumn : right.columns()) {
      Types.NestedField leftColumn = left.findField(rightColumn.fieldId());

      if (leftColumn == null) {
        joinedColumns.add(rightColumn);
      } else {
        Preconditions.checkArgument(
            leftColumn.equals(rightColumn),
            "Schemas have different columns with same id: %s, %s",
            leftColumn,
            rightColumn);
      }
    }

    return new Schema(joinedColumns);
  }

  public static Map<String, Integer> indexByName(Types.StructType struct) {
    IndexByName indexer = new IndexByName();
    visit(struct, indexer);
    return indexer.byName();
  }

  public static Map<Integer, String> indexNameById(Types.StructType struct) {
    IndexByName indexer = new IndexByName();
    visit(struct, indexer);
    return indexer.byId();
  }

  public static Map<Integer, String> indexQuotedNameById(
      Types.StructType struct, Function<String, String> quotingFunc) {
    IndexByName indexer = new IndexByName(quotingFunc);
    visit(struct, indexer);
    return indexer.byId();
  }

  /**
   * Creates a mapping from lower-case field names to their corresponding field IDs.
   *
   * <p>This method iterates over the fields of the provided struct and maps each field's name
   * (converted to lower-case) to its ID. If two fields have the same lower-case name, an
   * `IllegalArgumentException` is thrown.
   *
   * @param struct the struct type whose fields are to be indexed
   * @return a map where the keys are lower-case field names and the values are field IDs
   * @throws IllegalArgumentException if two fields have the same lower-case name
   */
  public static Map<String, Integer> indexByLowerCaseName(Types.StructType struct) {
    Map<String, Integer> indexByLowerCaseName = Maps.newHashMap();

    IndexByName indexer = new IndexByName();
    visit(struct, indexer);
    Map<Integer, String> byId = indexer.byId();

    indexByName(struct)
        .forEach(
            (name, fieldId) -> {
              String key = name.toLowerCase(Locale.ROOT);
              Integer existingId = indexByLowerCaseName.put(key, fieldId);
              Preconditions.checkArgument(
                  existingId == null || existingId.equals(fieldId),
                  "Cannot build lower case index: %s and %s collide",
                  byId.get(existingId),
                  byId.get(fieldId));
              indexByLowerCaseName.put(key, fieldId);
            });
    return indexByLowerCaseName;
  }

  public static Map<Integer, Types.NestedField> indexById(Types.StructType struct) {
    return visit(struct, new IndexById());
  }

  public static Map<Integer, Integer> indexParents(Types.StructType struct) {
    return ImmutableMap.copyOf(visit(struct, new IndexParents()));
  }

  /**
   * Assigns fresh ids from the {@link NextID nextId function} for all fields in a type.
   *
   * @param type a type
   * @param nextId an id assignment function
   * @return an structurally identical type with new ids assigned by the nextId function
   */
  public static Type assignFreshIds(Type type, NextID nextId) {
    return TypeUtil.visit(type, new AssignFreshIds(nextId));
  }

  /**
   * Assigns fresh ids from the {@link NextID nextId function} for all fields in a schema.
   *
   * @param schema a schema
   * @param nextId an id assignment function
   * @return a structurally identical schema with new ids assigned by the nextId function
   */
  public static Schema assignFreshIds(Schema schema, NextID nextId) {
    Types.StructType struct =
        TypeUtil.visit(schema.asStruct(), new AssignFreshIds(nextId)).asStructType();
    return new Schema(struct.fields(), refreshIdentifierFields(struct, schema));
  }

  /**
   * Assigns fresh ids from the {@link NextID nextId function} for all fields in a schema.
   *
   * @param schemaId an ID assigned to this schema
   * @param schema a schema
   * @param nextId an id assignment function
   * @return a structurally identical schema with new ids assigned by the nextId function
   */
  public static Schema assignFreshIds(int schemaId, Schema schema, NextID nextId) {
    Types.StructType struct =
        TypeUtil.visit(schema.asStruct(), new AssignFreshIds(nextId)).asStructType();
    return new Schema(schemaId, struct.fields(), refreshIdentifierFields(struct, schema));
  }

  /**
   * Assigns ids to match a given schema, and fresh ids from the {@link NextID nextId function} for
   * all other fields.
   *
   * @param schema a schema
   * @param baseSchema a schema with existing IDs to copy by name
   * @param nextId an id assignment function
   * @return a structurally identical schema with new ids assigned by the nextId function
   */
  public static Schema assignFreshIds(Schema schema, Schema baseSchema, NextID nextId) {
    Types.StructType struct =
        TypeUtil.visit(schema.asStruct(), new AssignFreshIds(schema, baseSchema, nextId))
            .asStructType();
    return new Schema(struct.fields(), refreshIdentifierFields(struct, schema));
  }

  /**
   * Get the identifier fields in the fresh schema based on the identifier fields in the base
   * schema.
   *
   * @param freshSchema fresh schema
   * @param baseSchema base schema
   * @return identifier fields in the fresh schema
   */
  public static Set<Integer> refreshIdentifierFields(
      Types.StructType freshSchema, Schema baseSchema) {
    Map<String, Integer> nameToId = TypeUtil.indexByName(freshSchema);
    Set<String> identifierFieldNames = baseSchema.identifierFieldNames();
    identifierFieldNames.forEach(
        name ->
            Preconditions.checkArgument(
                nameToId.containsKey(name),
                "Cannot find ID for identifier field %s in schema %s",
                name,
                freshSchema));
    return identifierFieldNames.stream().map(nameToId::get).collect(Collectors.toSet());
  }

  /**
   * Assigns strictly increasing fresh ids for all fields in a schema, starting from 1.
   *
   * @param schema a schema
   * @return a structurally identical schema with new ids assigned strictly increasing from 1
   */
  public static Schema assignIncreasingFreshIds(Schema schema) {
    AtomicInteger lastColumnId = new AtomicInteger(0);
    return TypeUtil.assignFreshIds(schema, lastColumnId::incrementAndGet);
  }

  /**
   * Reassigns ids in a schema from another schema.
   *
   * <p>Ids are determined by field names. If a field in the schema cannot be found in the source
   * schema, this will throw IllegalArgumentException.
   *
   * <p>This will not alter a schema's structure, nullability, or types.
   *
   * @param schema the schema to have ids reassigned
   * @param idSourceSchema the schema from which field ids will be used
   * @return an structurally identical schema with field ids matching the source schema
   * @throws IllegalArgumentException if a field cannot be found (by name) in the source schema
   */
  public static Schema reassignIds(Schema schema, Schema idSourceSchema) {
    return reassignIds(schema, idSourceSchema, true);
  }

  /**
   * Reassigns doc in a schema from another schema.
   *
   * <p>Doc are determined by field id. If a field in the schema cannot be found in the source
   * schema, this will throw IllegalArgumentException.
   *
   * <p>This will not alter a schema's structure, nullability, or types.
   *
   * @param schema the schema to have doc reassigned
   * @param docSourceSchema the schema from which field doc will be used
   * @return an structurally identical schema with field ids matching the source schema
   * @throws IllegalArgumentException if a field cannot be found (by id) in the source schema
   */
  public static Schema reassignDoc(Schema schema, Schema docSourceSchema) {
    TypeUtil.CustomOrderSchemaVisitor<Type> visitor = new ReassignDoc(docSourceSchema);
    return new Schema(
        visitor
            .schema(schema, new VisitFuture<>(schema.asStruct(), visitor))
            .asStructType()
            .fields());
  }

  /**
   * Reassigns ids in a schema from another schema.
   *
   * <p>Ids are determined by field names. If a field in the schema cannot be found in the source
   * schema, this will throw IllegalArgumentException.
   *
   * <p>This will not alter a schema's structure, nullability, or types.
   *
   * @param schema the schema to have ids reassigned
   * @param idSourceSchema the schema from which field ids will be used
   * @return an structurally identical schema with field ids matching the source schema
   * @throws IllegalArgumentException if a field cannot be found (by name) in the source schema
   */
  public static Schema reassignIds(Schema schema, Schema idSourceSchema, boolean caseSensitive) {
    Types.StructType struct =
        visit(schema, new ReassignIds(idSourceSchema, null, caseSensitive)).asStructType();
    return new Schema(struct.fields(), refreshIdentifierFields(struct, schema));
  }

  public static Schema reassignOrRefreshIds(Schema schema, Schema idSourceSchema) {
    return reassignOrRefreshIds(schema, idSourceSchema, true);
  }

  public static Schema reassignOrRefreshIds(
      Schema schema, Schema idSourceSchema, boolean caseSensitive) {
    AtomicInteger highest = new AtomicInteger(idSourceSchema.highestFieldId());
    Types.StructType struct =
        visit(schema, new ReassignIds(idSourceSchema, highest::incrementAndGet, caseSensitive))
            .asStructType();
    return new Schema(struct.fields(), refreshIdentifierFields(struct, schema));
  }

  /**
   * Assigns fresh ids from the {@link GetID getId function} for all fields in a type.
   *
   * @param type a type
   * @param getId an id assignment function
   * @return an structurally identical type with new ids assigned by the getId function
   */
  public static Type assignIds(Type type, GetID getId) {
    return TypeUtil.visit(type, new AssignIds(getId));
  }

  public static Type find(Schema schema, Predicate<Type> predicate) {
    return visit(schema, new FindTypeVisitor(predicate));
  }

  public static Type find(Type type, Predicate<Type> predicate) {
    return visit(type, new FindTypeVisitor(predicate));
  }

  public static boolean isPromotionAllowed(Type from, Type.PrimitiveType to) {
    // Warning! Before changing this function, make sure that the type change doesn't introduce
    // compatibility problems in partitioning.
    if (from.equals(to)) {
      return true;
    }

    switch (from.typeId()) {
      case INTEGER:
        return to.typeId() == Type.TypeID.LONG;

      case FLOAT:
        return to.typeId() == Type.TypeID.DOUBLE;

      case DECIMAL:
        Types.DecimalType fromDecimal = (Types.DecimalType) from;
        if (to.typeId() != Type.TypeID.DECIMAL) {
          return false;
        }

        Types.DecimalType toDecimal = (Types.DecimalType) to;
        return fromDecimal.scale() == toDecimal.scale()
            && fromDecimal.precision() <= toDecimal.precision();
    }

    return false;
  }

  /**
   * Check whether we could write the iceberg table with the user-provided write schema.
   *
   * @param tableSchema the table schema written in iceberg meta data.
   * @param writeSchema the user-provided write schema.
   * @param checkNullability If true, not allow to write optional values to a required field.
   * @param checkOrdering If true, not allow input schema to have different ordering than table
   *     schema.
   */
  public static void validateWriteSchema(
      Schema tableSchema, Schema writeSchema, Boolean checkNullability, Boolean checkOrdering) {
    String errMsg = "Cannot write incompatible dataset to table with schema:";
    checkSchemaCompatibility(errMsg, tableSchema, writeSchema, checkNullability, checkOrdering);
  }

  /**
   * Validates whether the provided schema is compatible with the expected schema.
   *
   * @param context the schema context (e.g. row ID)
   * @param expectedSchema the expected schema
   * @param providedSchema the provided schema
   * @param checkNullability whether to check field nullability
   * @param checkOrdering whether to check field ordering
   */
  public static void validateSchema(
      String context,
      Schema expectedSchema,
      Schema providedSchema,
      boolean checkNullability,
      boolean checkOrdering) {
    String errMsg =
        String.format("Provided %s schema is incompatible with expected schema:", context);
    checkSchemaCompatibility(
        errMsg, expectedSchema, providedSchema, checkNullability, checkOrdering);
  }

  private static void checkSchemaCompatibility(
      String errMsg,
      Schema schema,
      Schema providedSchema,
      boolean checkNullability,
      boolean checkOrdering) {
    List<String> errors;
    if (checkNullability) {
      errors = CheckCompatibility.writeCompatibilityErrors(schema, providedSchema, checkOrdering);
    } else {
      errors = CheckCompatibility.typeCompatibilityErrors(schema, providedSchema, checkOrdering);
    }

    if (!errors.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append(errMsg)
          .append("\n")
          .append(schema)
          .append("\n")
          .append("Provided schema:")
          .append("\n")
          .append(providedSchema)
          .append("\n")
          .append("Problems:");
      for (String error : errors) {
        sb.append("\n* ").append(error);
      }
      throw new IllegalArgumentException(sb.toString());
    }
  }

  /**
   * Estimates the number of bytes a value for a given field may occupy in memory.
   *
   * <p>This method approximates the memory size based on heuristics and the internal Java
   * representation defined by {@link Type.TypeID}. It is important to note that the actual size
   * might differ from this estimation. The method is designed to handle a variety of data types,
   * including primitive types, strings, and nested types such as structs, maps, and lists.
   *
   * @param field a field for which to estimate the size
   * @return the estimated size in bytes of the field's value in memory
   */
  public static int estimateSize(Types.NestedField field) {
    return estimateSize(field.type());
  }

  private static int estimateSize(Type type) {
    switch (type.typeId()) {
      case BOOLEAN:
        // the size of a boolean variable is virtual machine dependent
        // it is common to believe booleans occupy 1 byte in most JVMs
        return 1;
      case INTEGER:
      case FLOAT:
      case DATE:
        // ints and floats occupy 4 bytes
        // dates are internally represented as ints
        return 4;
      case LONG:
      case DOUBLE:
      case TIME:
      case TIMESTAMP:
      case TIMESTAMP_NANO:
        // longs and doubles occupy 8 bytes
        // times and timestamps are internally represented as longs
        return 8;
      case STRING:
        // 12 (header) + 6 (fields) + 16 (array overhead) + 20 (10 chars, 2 bytes each) = 54 bytes
        return 54;
      case UUID:
        // 12 (header) + 16 (two long variables) = 28 bytes
        return 28;
      case FIXED:
        return ((Types.FixedType) type).length();
      case BINARY:
      case VARIANT:
        return 80;
      case UNKNOWN:
        // Consider Unknown as null
        return 0;
      case DECIMAL:
        // 12 (header) + (12 + 12 + 4) (BigInteger) + 4 (scale) = 44 bytes
        return 44;
      case STRUCT:
        Types.StructType struct = (Types.StructType) type;
        return HEADER_SIZE + struct.fields().stream().mapToInt(TypeUtil::estimateSize).sum();
      case LIST:
        Types.ListType list = (Types.ListType) type;
        return HEADER_SIZE + 5 * estimateSize(list.elementType());
      case MAP:
        Types.MapType map = (Types.MapType) type;
        int entrySize = HEADER_SIZE + estimateSize(map.keyType()) + estimateSize(map.valueType());
        return HEADER_SIZE + 5 * entrySize;
      default:
        return 16;
    }
  }

  /** Interface for passing a function that assigns column IDs. */
  public interface NextID {
    int get();
  }

  /** Interface for passing a function that assigns column IDs from the previous Id. */
  public interface GetID {
    int get(int oldId);
  }

  public static class SchemaVisitor<T> {
    public void beforeField(Types.NestedField field) {}

    public void afterField(Types.NestedField field) {}

    public void beforeListElement(Types.NestedField elementField) {
      beforeField(elementField);
    }

    public void afterListElement(Types.NestedField elementField) {
      afterField(elementField);
    }

    public void beforeMapKey(Types.NestedField keyField) {
      beforeField(keyField);
    }

    public void afterMapKey(Types.NestedField keyField) {
      afterField(keyField);
    }

    public void beforeMapValue(Types.NestedField valueField) {
      beforeField(valueField);
    }

    public void afterMapValue(Types.NestedField valueField) {
      afterField(valueField);
    }

    public T schema(Schema schema, T structResult) {
      return null;
    }

    public T struct(Types.StructType struct, List<T> fieldResults) {
      return null;
    }

    public T field(Types.NestedField field, T fieldResult) {
      return null;
    }

    public T list(Types.ListType list, T elementResult) {
      return null;
    }

    public T map(Types.MapType map, T keyResult, T valueResult) {
      return null;
    }

    /**
     * @deprecated will be removed in 2.0.0; use {@link #variant(Types.VariantType)} instead.
     */
    @Deprecated
    public T variant() {
      return variant(Types.VariantType.get());
    }

    public T variant(Types.VariantType variant) {
      throw new UnsupportedOperationException("Unsupported type: variant");
    }

    public T primitive(Type.PrimitiveType primitive) {
      return null;
    }
  }

  public static <T> T visit(Schema schema, SchemaVisitor<T> visitor) {
    return visitor.schema(schema, visit(schema.asStruct(), visitor));
  }

  public static <T> T visit(Type type, SchemaVisitor<T> visitor) {
    switch (type.typeId()) {
      case STRUCT:
        Types.StructType struct = type.asNestedType().asStructType();
        List<T> results = Lists.newArrayListWithExpectedSize(struct.fields().size());
        for (Types.NestedField field : struct.fields()) {
          visitor.beforeField(field);
          T result;
          try {
            result = visit(field.type(), visitor);
          } finally {
            visitor.afterField(field);
          }
          results.add(visitor.field(field, result));
        }
        return visitor.struct(struct, results);

      case LIST:
        Types.ListType list = type.asNestedType().asListType();
        T elementResult;

        Types.NestedField elementField = list.field(list.elementId());
        visitor.beforeListElement(elementField);
        try {
          elementResult = visit(list.elementType(), visitor);
        } finally {
          visitor.afterListElement(elementField);
        }

        return visitor.list(list, elementResult);

      case MAP:
        Types.MapType map = type.asNestedType().asMapType();
        T keyResult;
        T valueResult;

        Types.NestedField keyField = map.field(map.keyId());
        visitor.beforeMapKey(keyField);
        try {
          keyResult = visit(map.keyType(), visitor);
        } finally {
          visitor.afterMapKey(keyField);
        }

        Types.NestedField valueField = map.field(map.valueId());
        visitor.beforeMapValue(valueField);
        try {
          valueResult = visit(map.valueType(), visitor);
        } finally {
          visitor.afterMapValue(valueField);
        }

        return visitor.map(map, keyResult, valueResult);

      case VARIANT:
        return visitor.variant(type.asVariantType());

      default:
        return visitor.primitive(type.asPrimitiveType());
    }
  }

  public static class CustomOrderSchemaVisitor<T> {
    public T schema(Schema schema, Supplier<T> structResult) {
      return null;
    }

    public T struct(Types.StructType struct, Iterable<T> fieldResults) {
      return null;
    }

    public T field(Types.NestedField field, Supplier<T> fieldResult) {
      return null;
    }

    public T list(Types.ListType list, Supplier<T> elementResult) {
      return null;
    }

    public T map(Types.MapType map, Supplier<T> keyResult, Supplier<T> valueResult) {
      return null;
    }

    public T variant(Types.VariantType variant) {
      throw new UnsupportedOperationException("Unsupported type: variant");
    }

    public T primitive(Type.PrimitiveType primitive) {
      return null;
    }
  }

  private static class VisitFuture<T> implements Supplier<T> {
    private final Type type;
    private final CustomOrderSchemaVisitor<T> visitor;

    private VisitFuture(Type type, CustomOrderSchemaVisitor<T> visitor) {
      this.type = type;
      this.visitor = visitor;
    }

    @Override
    public T get() {
      return visit(type, visitor);
    }
  }

  private static class VisitFieldFuture<T> implements Supplier<T> {
    private final Types.NestedField field;
    private final CustomOrderSchemaVisitor<T> visitor;

    private VisitFieldFuture(Types.NestedField field, CustomOrderSchemaVisitor<T> visitor) {
      this.field = field;
      this.visitor = visitor;
    }

    @Override
    public T get() {
      return visitor.field(field, new VisitFuture<>(field.type(), visitor));
    }
  }

  public static <T> T visit(Schema schema, CustomOrderSchemaVisitor<T> visitor) {
    return visitor.schema(schema, new VisitFuture<>(schema.asStruct(), visitor));
  }

  /**
   * Used to traverse types with traversals other than post-order.
   *
   * <p>This passes a {@link Supplier} to each {@link CustomOrderSchemaVisitor visitor} method that
   * returns the result of traversing child types. Structs are passed an {@link Iterable} that
   * traverses child fields during iteration.
   *
   * <p>An example use is assigning column IDs, which should be done with a pre-order traversal.
   *
   * @param type a type to traverse with a visitor
   * @param visitor a custom order visitor
   * @param <T> the type returned by the visitor
   * @return the result of traversing the given type with the visitor
   */
  public static <T> T visit(Type type, CustomOrderSchemaVisitor<T> visitor) {
    switch (type.typeId()) {
      case STRUCT:
        Types.StructType struct = type.asNestedType().asStructType();
        List<VisitFieldFuture<T>> results =
            Lists.newArrayListWithExpectedSize(struct.fields().size());
        for (Types.NestedField field : struct.fields()) {
          results.add(new VisitFieldFuture<>(field, visitor));
        }

        return visitor.struct(struct, Iterables.transform(results, VisitFieldFuture::get));

      case LIST:
        Types.ListType list = type.asNestedType().asListType();
        return visitor.list(list, new VisitFuture<>(list.elementType(), visitor));

      case MAP:
        Types.MapType map = type.asNestedType().asMapType();
        return visitor.map(
            map,
            new VisitFuture<>(map.keyType(), visitor),
            new VisitFuture<>(map.valueType(), visitor));

      case VARIANT:
        return visitor.variant(type.asVariantType());

      default:
        return visitor.primitive(type.asPrimitiveType());
    }
  }

  static int decimalMaxPrecision(int numBytes) {
    Preconditions.checkArgument(
        numBytes >= 0 && numBytes < 24, "Unsupported decimal length: %s", numBytes);
    return MAX_PRECISION[numBytes];
  }

  public static int decimalRequiredBytes(int precision) {
    Preconditions.checkArgument(
        precision >= 0 && precision < 40, "Unsupported decimal precision: %s", precision);
    return REQUIRED_LENGTH[precision];
  }

  private static final int[] MAX_PRECISION = new int[24];
  private static final int[] REQUIRED_LENGTH = new int[40];

  static {
    // for each length, calculate the max precision
    for (int len = 0; len < MAX_PRECISION.length; len += 1) {
      MAX_PRECISION[len] = (int) Math.floor(Math.log10(Math.pow(2, 8 * len - 1) - 1));
    }

    // for each precision, find the first length that can hold it
    for (int precision = 0; precision < REQUIRED_LENGTH.length; precision += 1) {
      REQUIRED_LENGTH[precision] = -1;
      for (int len = 0; len < MAX_PRECISION.length; len += 1) {
        // find the first length that can hold the precision
        if (precision <= MAX_PRECISION[len]) {
          REQUIRED_LENGTH[precision] = len;
          break;
        }
      }
      if (REQUIRED_LENGTH[precision] < 0) {
        throw new IllegalStateException(
            "Could not find required length for precision " + precision);
      }
    }
  }
}
