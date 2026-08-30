package com.alianga.jkit.json;

import com.alianga.jkit.json.internal.compiler.JavaSourceObject;
import com.alianga.jkit.reflect.GetterInfo;
import com.alianga.jkit.reflect.ReflectConsts;

import java.io.IOException;
import java.util.Collection;

/**
 * @time 2024/3/15 8:59
 */
public class JSONPojoSerializer<T> extends JSONTypeSerializer {
    /**
     * The parsed structure (field serializers and metadata) of the pojo class
     */
    protected final JSONPojoStructure pojoStructure;
    /**
     * The pojo class handled by this serializer
     */
    protected final Class<?> pojoClass;

    /**
     * Create a serializer for the given pojo class, the structure is looked up from the global store.
     *
     * @param pojoClass the pojo class to serialize
     */
    protected JSONPojoSerializer(Class<T> pojoClass) {
        this.pojoClass = pojoClass;
        pojoStructure = JSONStore.INSTANCE.getPojoStruc(pojoClass);
    }

    /**
     * Create a serializer with an already parsed pojo structure.
     *
     * @param pojoStructure the parsed pojo structure, its source class is used as the target pojo class
     */
    protected JSONPojoSerializer(JSONPojoStructure pojoStructure) {
        this.pojoStructure = pojoStructure;
        this.pojoClass = pojoStructure.getSourceClass();
    }

    @Override
    final JSONTypeSerializer ensureInitialized() {
        pojoStructure.ensureInitializedFieldSerializers();
        return this;
    }

    /**
     * Write all serializable fields of the entity as compact (no indentation) name/value pairs,
     * the enclosing braces are written by the caller. Null values are skipped unless
     * {@code jsonConfig.isFullProperty()} is enabled.
     *
     * @param entity      the pojo instance to serialize
     * @param writer      the target writer
     * @param jsonConfig  the serialization config
     * @param indentLevel the current indent level, used when the class name is written
     * @throws Exception if reading a field value or writing to the writer fails
     */
    public void serializePojoCompact(T entity, JSONWriter writer, JSONConfig jsonConfig, int indentLevel)
            throws Exception {
        boolean writeFullProperty = jsonConfig.isFullProperty();
        boolean writeClassName = jsonConfig.isWriteClassName();

        boolean isEmptyFlag = !checkWriteClassName(writeClassName, writer, pojoClass, false, indentLevel, jsonConfig);
        JSONPojoFieldSerializer[] fieldSerializers = pojoStructure.getFieldSerializers(jsonConfig.isUseFields());

        boolean skipGetterOfNoExistField = jsonConfig.isSkipGetterOfNoneField();
        boolean unCamelCaseToUnderline = !jsonConfig.isCamelCaseToUnderline();
        for (JSONPojoFieldSerializer fieldSerializer : fieldSerializers) {
            GetterInfo getterInfo = fieldSerializer.getterInfo;
            if (!getterInfo.existField() && skipGetterOfNoExistField) {
                continue;
            }
            Object value = JSON_SECURE_TRUSTED_ACCESS.get(getterInfo, entity); // getterInfo.invoke(entity);
            if (value == null && !writeFullProperty) {
                continue;
            }
            if (isEmptyFlag) {
                isEmptyFlag = false;
            } else {
                writer.writeJSONToken(',');
            }
            if (value != null) {
                if (unCamelCaseToUnderline) {
                    fieldSerializer.writeFieldNameAndColonTo(writer);
                } else {
                    writer.append('"').append(getterInfo.getUnderlineName()).append("\":");
                }
                fieldSerializer.serializer.serialize(value, writer, jsonConfig, -1);
            } else {
                if (unCamelCaseToUnderline) {
                    fieldSerializer.writeJSONFieldNameWithNull(writer);
                } else {
                    writer.append('"').append(getterInfo.getUnderlineName()).append("\":null");
                }
            }
        }
    }

    /**
     * Write all serializable fields of the entity with line breaks and indentation,
     * the enclosing braces are written by the caller. Null values are skipped unless
     * {@code jsonConfig.isFullProperty()} is enabled.
     *
     * @param entity      the pojo instance to serialize
     * @param writer      the target writer
     * @param jsonConfig  the serialization config
     * @param indentLevel the current indent level, fields are written at {@code indentLevel + 1}
     * @throws Exception if reading a field value or writing to the writer fails
     */
    public void serializePojoFormatOut(T entity, JSONWriter writer, JSONConfig jsonConfig, int indentLevel)
            throws Exception {
        boolean writeFullProperty = jsonConfig.isFullProperty();
        boolean writeClassName = jsonConfig.isWriteClassName();
        boolean formatOutColonSpace = jsonConfig.isFormatOutColonSpace();
        boolean isEmptyFlag = !checkWriteClassName(writeClassName, writer, pojoClass, true, indentLevel, jsonConfig);
        JSONPojoFieldSerializer[] fieldSerializers = pojoStructure.getFieldSerializers(jsonConfig.isUseFields());

        boolean skipGetterOfNoExistField = jsonConfig.isSkipGetterOfNoneField();
        boolean unCamelCaseToUnderline = !jsonConfig.isCamelCaseToUnderline();
        int indentPlus = indentLevel + 1;
        for (JSONPojoFieldSerializer fieldSerializer : fieldSerializers) {
            GetterInfo getterInfo = fieldSerializer.getterInfo;
            if (!getterInfo.existField() && skipGetterOfNoExistField) {
                continue;
            }
            Object value = JSON_SECURE_TRUSTED_ACCESS.get(getterInfo, entity); // getterInfo.invoke(entity);
            if (value == null && !writeFullProperty) {
                continue;
            }
            if (isEmptyFlag) {
                isEmptyFlag = false;
            } else {
                writer.writeJSONToken(',');
            }
            writeFormatOutSymbols(writer, indentPlus, true, jsonConfig);
            if (value != null) {
                if (unCamelCaseToUnderline) {
                    fieldSerializer.writeFieldNameAndColonTo(writer);
                } else {
                    writer.append('"').append(getterInfo.getUnderlineName()).append("\":");
                }
                if (formatOutColonSpace) {
                    writer.writeJSONToken(' ');
                }
                fieldSerializer.serializer.serialize(value, writer, jsonConfig, indentPlus);
            } else {
                if (unCamelCaseToUnderline) {
                    if (formatOutColonSpace) {
                        fieldSerializer.writeFieldNameAndColonTo(writer);
                        writer.write(" null");
                    } else {
                        fieldSerializer.writeJSONFieldNameWithNull(writer);
                    }
                } else {
                    writer.writeJSONToken('"');
                    writer.write(getterInfo.getUnderlineName());
                    writer.writeJSONToken('"');
                    if (formatOutColonSpace) {
                        writer.write(": null");
                    } else {
                        writer.write(":null");
                    }
                }
            }
        }
        if (!isEmptyFlag) {
            writeEndFormatOutSymbols(writer, indentLevel, true, jsonConfig);
        }
    }

    @Override
    protected final void serialize(Object obj, JSONWriter writer, JSONConfig jsonConfig, int indentLevel)
            throws Exception {
        Class<?> entityClass = obj.getClass();
        if (entityClass == pojoClass) {
            int hashcode = -1;
            if (jsonConfig.skipCircularReference) {
                if (jsonConfig.getStatus(hashcode = System.identityHashCode(obj)) == 0) {
                    writer.writeNull();
                    return;
                }
                jsonConfig.setStatus(hashcode, 0);
            }
            writer.writeJSONToken('{');
            boolean formatOut = jsonConfig.formatOut;
            T entity = (T) obj;
            if (formatOut) {
                serializePojoFormatOut(entity, writer, jsonConfig, indentLevel);
            } else {
                serializePojoCompact(entity, writer, jsonConfig, indentLevel);
            }
            writer.write('}');
            if (jsonConfig.skipCircularReference) {
                jsonConfig.setStatus(hashcode, -1);
            }
        } else {
            JSONTypeSerializer serializer = pojoStructure.store.getTypeSerializer(entityClass);
            serializer.serialize(obj, writer, jsonConfig, indentLevel);
        }
    }

    /**
     * Delegate the field value to the given serializer, provided for the generated code.
     *
     * @param serializer  the serializer of the field value
     * @param fieldValue  the field value to write
     * @param writer      the target writer
     * @param jsonConfig  the serialization config
     * @param indentLevel the current indent level, -1 means compact output
     * @throws Exception if the delegated serialization fails
     */
    protected static final void doSerialize(JSONTypeSerializer serializer, Object fieldValue, JSONWriter writer,
                                            JSONConfig jsonConfig,
                                            int indentLevel) throws Exception {
        serializer.serialize(fieldValue, writer, jsonConfig, indentLevel);
    }

    /**
     * Read the field value of the pojo through the getter of the given field serializer.
     *
     * @param fieldSerializer the field serializer holding the getter info
     * @param pojo            the pojo instance to read from
     * @return the field value, may be {@code null}
     * @throws Exception if the getter invocation fails
     */
    protected static final Object invokeValue(JSONPojoFieldSerializer fieldSerializer, Object pojo) throws Exception {
        return JSON_SECURE_TRUSTED_ACCESS.get(fieldSerializer.getterInfo, pojo);
    }

    /**
     * Read the field value of the pojo through the getter and cast it to the expected type.
     *
     * @param fieldSerializer the field serializer holding the getter info
     * @param pojo            the pojo instance to read from
     * @param tClass          the expected value type, only used for the compile time cast
     * @param <T>             the expected value type
     * @return the field value cast to {@code T}, may be {@code null}
     * @throws Exception if the getter invocation fails
     */
    protected static final <T> T invokeValue(JSONPojoFieldSerializer fieldSerializer, Object pojo, Class<T> tClass)
            throws Exception {
        return (T) JSON_SECURE_TRUSTED_ACCESS.get(fieldSerializer.getterInfo, pojo);
    }

    /**
     * Write 6 to 8 chars (or bytes) to the writer in one shot, the chars are packed in the given longs.
     * Provided for the generated code to write constant field names.
     *
     * @param jsonWriter the target writer
     * @param fourChars1 the first 4 chars packed as a long
     * @param fourChars2 the last 4 chars packed as a long
     * @param fourBytes  the same content packed as bytes, used by the byte oriented writers
     * @param len        the count of chars (bytes) to write
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeMemory(JSONWriter jsonWriter, long fourChars1, long fourChars2, long fourBytes,
                                            int len) throws IOException {
        jsonWriter.writeMemory(fourChars1, fourChars2, fourBytes, len);
    }

    /**
     * Write up to 4 chars (or bytes) to the writer in one shot, the chars are packed in the given long.
     * Provided for the generated code to write constant field names.
     *
     * @param jsonWriter the target writer
     * @param fourChars  the 4 chars packed as a long
     * @param fourBytes  the same content packed as bytes, used by the byte oriented writers
     * @param len        the count of chars (bytes) to write
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeMemory(JSONWriter jsonWriter, long fourChars, int fourBytes, int len)
            throws IOException {
        jsonWriter.writeMemory(fourChars, fourBytes, len);
    }

    /**
     * Write a string array as a formatted (indented) JSON array, null elements are written as {@code null}.
     *
     * @param jsonWriter the target writer
     * @param values     the string array to write, an empty array is written as {@code []}
     * @param jsonConfig the serialization config
     * @param level      the indent level of the array itself, elements are written at {@code level + 1}
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeStringArrayFormatOut(JSONWriter jsonWriter, String[] values, JSONConfig jsonConfig,
                                                          int level) throws IOException {
        final int levelPlus = level + 1;
        int len = values.length;
        if (len > 0) {
            jsonWriter.writeJSONToken('[');
            writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
            int i = 1;
            jsonWriter.writeStringCompatibleNull(values[0]);
            if ((len & 1) == 0) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeStringCompatibleNull(values[1]);
                ++i;
            }
            for (; i < len; i = i + 2) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeStringCompatibleNull(values[i]);
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeStringCompatibleNull(values[i + 1]);
            }
            writeEndFormatOutSymbols(jsonWriter, level, true, jsonConfig);
            jsonWriter.writeJSONToken(']');
        } else {
            jsonWriter.writeEmptyArray();
        }
    }

    /**
     * Write a collection of strings as a formatted (indented) JSON array, null elements are written
     * as {@code null}.
     *
     * @param jsonWriter the target writer
     * @param values     the collection to write, every element must be a String, an empty collection
     *                   is written as {@code []}
     * @param jsonConfig the serialization config
     * @param level      the indent level of the array itself, elements are written at {@code level + 1}
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeStringCollectionFormatOut(JSONWriter jsonWriter, Collection values,
                                                               JSONConfig jsonConfig, int level) throws IOException {
        int size = values.size();
        final int levelPlus = level + 1;
        if (size > 0) {
            jsonWriter.writeJSONToken('[');
            boolean hasAddFlag = false;
            for (Object value : values) {
                if (hasAddFlag) {
                    jsonWriter.writeJSONToken(',');
                } else {
                    hasAddFlag = true;
                }
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeStringCompatibleNull((String) value);
            }
            writeEndFormatOutSymbols(jsonWriter, level, true, jsonConfig);
            jsonWriter.writeJSONToken(']');
        } else {
            jsonWriter.writeEmptyArray();
        }
    }

    /**
     * Write a long array as a formatted (indented) JSON array.
     *
     * @param jsonWriter the target writer
     * @param values     the long array to write, an empty array is written as {@code []}
     * @param jsonConfig the serialization config
     * @param level      the indent level of the array itself, elements are written at {@code level + 1}
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeLongArrayFormatOut(JSONWriter jsonWriter, long[] values, JSONConfig jsonConfig,
                                                        int level) throws IOException {
        final int levelPlus = level + 1;
        int len = values.length;
        if (len > 0) {
            jsonWriter.writeJSONToken('[');
            int i = 1;
            writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
            jsonWriter.writeLong(values[0]);
            if ((len & 1) == 0) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeLong(values[1], jsonConfig);
                ++i;
            }
            for (; i < len; i = i + 2) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeLong(values[i], jsonConfig);
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeLong(values[i + 1], jsonConfig);
            }
            writeEndFormatOutSymbols(jsonWriter, level, true, jsonConfig);
            jsonWriter.writeJSONToken(']');
        } else {
            jsonWriter.writeEmptyArray();
        }
    }

    /**
     * Write a double array as a formatted (indented) JSON array.
     *
     * @param jsonWriter the target writer
     * @param values     the double array to write, an empty array is written as {@code []}
     * @param jsonConfig the serialization config
     * @param level      the indent level of the array itself, elements are written at {@code level + 1}
     * @throws IOException if writing to the writer fails
     */
    protected static final void writeDoubleArrayFormatOut(JSONWriter jsonWriter, double[] values, JSONConfig jsonConfig,
                                                          int level) throws IOException {
        final int levelPlus = level + 1;
        int len = values.length;
        if (len > 0) {
            jsonWriter.writeJSONToken('[');
            int i = 1;
            writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
            jsonWriter.writeDouble(values[0]);
            if ((len & 1) == 0) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeDouble(values[1]);
                ++i;
            }
            for (; i < len; i = i + 2) {
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeDouble(values[i]);
                jsonWriter.writeJSONToken(',');
                writeFormatOutSymbols(jsonWriter, levelPlus, jsonConfig);
                jsonWriter.writeDouble(values[i + 1]);
            }
            writeEndFormatOutSymbols(jsonWriter, level, true, jsonConfig);
            jsonWriter.writeJSONToken(']');
        } else {
            jsonWriter.writeEmptyArray();
        }
    }

    static JavaSourceObject generateRuntimeJavaCodeSource(JSONPojoStructure jsonPojoStructure) {
        return JSONPojoSerializerCodeGen.generateJavaCodeSource(jsonPojoStructure, false, true);
    }

    static JavaSourceObject generateJavaCodeSource(JSONPojoStructure jsonPojoStructure, boolean printJavaSource) {
        return JSONPojoSerializerCodeGen.generateJavaCodeSource(jsonPojoStructure, printJavaSource, false);
    }

    /**
     * Generate serialized Java source code based on the pojo class
     * Using Java compilation can improve performance 20%
     *
     * @param pojoClass       pojo class
     * @param printJavaSource if print the gen code
     * @return java source object
     */
    public static JavaSourceObject generateJavaCodeSource(Class<?> pojoClass, boolean printJavaSource) {
        return generateJavaCodeSource(pojoClass, printJavaSource, false);
    }

    /**
     * Generate serialized Java source code based on the pojo class
     * Using Java compilation can improve performance 20%
     *
     * @param pojoClass       pojo class
     * @param printJavaSource if print the gen code
     * @param runtime         if runtime
     * @return java source object
     */
    public static JavaSourceObject generateJavaCodeSource(Class<?> pojoClass, boolean printJavaSource,
                                                          boolean runtime) {
        ReflectConsts.ClassCategory classCategory = ReflectConsts.getClassCategory(pojoClass);
        if (classCategory != ReflectConsts.ClassCategory.ObjectCategory) {
            throw new UnsupportedOperationException(pojoClass + " is not a pojo class");
        }
        JSONPojoStructure jsonPojoStructure = JSONStore.INSTANCE.getPojoStruc(pojoClass);
        if (!jsonPojoStructure.isSupportedJavaBeanConvention()) {
            throw new UnsupportedOperationException(pojoClass + " is not supported for code generator");
        }
        return JSONPojoSerializerCodeGen.generateJavaCodeSource(jsonPojoStructure, printJavaSource, runtime);
    }

    /**
     * Generate serialized Java source code based on the pojo class
     * Using Java compilation can improve performance 20%
     *
     * @param pojoClass pojo class
     * @return java source object
     */
    public static JavaSourceObject generateJavaCodeSource(Class<?> pojoClass) {
        return generateJavaCodeSource(pojoClass, false);
    }
}
