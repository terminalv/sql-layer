/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.store.format.protobuf;

import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.Group;
import com.foundationdb.ais.model.Join;
import com.foundationdb.ais.model.Table;
import com.foundationdb.server.rowdata.RowData;
import com.foundationdb.server.rowdata.RowDataValueSource;
import com.foundationdb.server.rowdata.RowDef;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.TextFormat;

import com.foundationdb.server.store.format.protobuf.CustomOptions.TableOptions;
import com.foundationdb.server.store.format.protobuf.CustomOptions.ColumnOptions;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;

public abstract class ProtobufRowDataConverter
{
    protected final int tableId;
    protected final Descriptor messageType;

    protected ProtobufRowDataConverter(int tableId, Descriptor messageType) {
        this.tableId = tableId;
        this.messageType = messageType;
    }

    /** Get the id of the group's table, should caller wish to store it. */
    public int getTableId() {
        return tableId;
    }

    /** Get the message type against which the top-level message should be decoded. */
    public Descriptor getMessageType() {
        return messageType;
    }

    /** Convert the given row into a message. */
    public abstract DynamicMessage encode(RowData rowData);

    /** Copy the given message into the given row data. */
    public abstract void decode(DynamicMessage msg, RowData rowData);

    public String shortFormat(DynamicMessage msg) {
        return TextFormat.shortDebugString(msg);
    }

    public void format(DynamicMessage msg, Appendable output) throws IOException {
        TextFormat.print(msg, output);
    }

    public static ProtobufRowDataConverter forGroup(Group group, 
                                                    FileDescriptor fileDescriptor) {
        // Find the group message.
        Descriptor groupMessage = null;
        List<Descriptor> messages = fileDescriptor.getMessageTypes();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Descriptor message = messages.get(i);
            if (message.getOptions().getExtension(TableOptions.fdbsql).getIsGroup()) {
                groupMessage = message;
                break;
            }
        }
        if (groupMessage != null) {
            return new GroupConverter(group, groupMessage);
        }
        else {
            assert (messages.size() == 1 && group.getRoot().getChildJoins().isEmpty());
            return new TableConverter(group.getRoot(), messages.get(0));
        }
    }

    static class GroupConverter extends ProtobufRowDataConverter {
        private final Map<Integer,ProtobufRowDataConverter> tableConvertersByTableId;
        private final Map<Integer,FieldDescriptor> groupFieldsByTabelId;
        private final Map<FieldDescriptor,ProtobufRowDataConverter> tableConvertersByField;

        public GroupConverter(Group group, Descriptor groupMessage) {
            super(group.getRoot().getTableId(), groupMessage);
            Map<String,Table> tablesByUuid = new HashMap<>();
            getUuids(group.getRoot(), tablesByUuid);
            tableConvertersByTableId = new HashMap<>(tablesByUuid.size());
            groupFieldsByTabelId = new HashMap<>(tablesByUuid.size());
            tableConvertersByField = new HashMap<>(tablesByUuid.size());
            for (FieldDescriptor field : groupMessage.getFields()) {
                String uuid = field.getOptions().getExtension(ColumnOptions.fdbsql).getUuid();
                Table table = tablesByUuid.get(uuid);
                if (table != null) {
                    ProtobufRowDataConverter converter = new TableConverter(table, field.getMessageType());
                    tableConvertersByTableId.put(table.getTableId(), converter);
                    groupFieldsByTabelId.put(table.getTableId(), field);
                    tableConvertersByField.put(field, converter);
                }
            }
        }

        private void getUuids(Table table, Map<String,Table> tablesByUuid) {
            tablesByUuid.put(table.getUuid().toString(), table);
            for (Join join : table.getChildJoins()) {
                getUuids(join.getChild(), tablesByUuid);
            }
        }

        @Override
        public DynamicMessage encode(RowData rowData) {
            DynamicMessage inside = 
                tableConvertersByTableId.get(rowData.getRowDefId()).encode(rowData);
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(messageType);
            builder.setField(groupFieldsByTabelId.get(rowData.getRowDefId()), inside);
            return builder.build();
        }

        @Override
        public void decode(DynamicMessage msg, RowData rowData) {
            boolean first = true;
            for (FieldDescriptor field : msg.getAllFields().keySet()) {
                ProtobufRowDataConverter tableConverter = 
                    tableConvertersByField.get(field);
                if (tableConverter != null) {
                    assert first;
                    first = false;
                    tableConverter.decode((DynamicMessage)msg.getField(field), rowData);
                }
            }
            assert !first;
        }
    }

    static class TableConverter extends ProtobufRowDataConverter {
        private final int nfields;
        private final ProtobufRowConversion[] conversions;
        private final FieldDescriptor[] fields;
        private final FieldDescriptor[] nullFields;
        private final Map<FieldDescriptor,Integer> columnIndexesByField;
        private final Map<FieldDescriptor,Integer> nullableIndexesByField;
        private final RowDef rowDef;
        
        public TableConverter(Table table, Descriptor tableMessage) {
            super(table.getTableId(), tableMessage);
            nfields = table.getColumnsIncludingInternal().size();
            conversions = new ProtobufRowConversion[nfields];
            fields = new FieldDescriptor[nfields];
            columnIndexesByField = new HashMap<>(nfields);
            Map<String,Integer> columnIndexedByUuid = new HashMap<>(nfields);
            for (int i = 0; i < nfields; i++) {
                Column column = table.getColumnsIncludingInternal().get(i);
                conversions[i] = ProtobufRowConversion.forTInstance(column.getType());
                columnIndexedByUuid.put(column.getUuid().toString(), i);
            }
            FieldDescriptor[] nullFields = null;
            Map<FieldDescriptor,Integer> nullableIndexesByField = null;
            for (FieldDescriptor field : tableMessage.getFields()) {
                ColumnOptions options = field.getOptions().getExtension(ColumnOptions.fdbsql);
                if (options.hasUuid()) {
                    Integer columnIndex = columnIndexedByUuid.get(options.getUuid());
                    if (columnIndex != null) {
                        fields[columnIndex] = field;
                        columnIndexesByField.put(field, columnIndex);
                    }
                }
                else if (options.hasNullForField()) {
                    if (nullFields == null) {
                        nullFields = new FieldDescriptor[nfields];
                        nullableIndexesByField = new HashMap<>(nfields);
                    }
                    FieldDescriptor forField = tableMessage.findFieldByNumber(options.getNullForField());
                    Integer columnIndex = columnIndexesByField.get(forField);
                    nullFields[columnIndex] = field;
                    nullableIndexesByField.put(field, columnIndex);
                }
            }
            this.nullFields = nullFields;
            this.nullableIndexesByField = nullableIndexesByField;
            rowDef = table.rowDef();
        }

        @Override
        public DynamicMessage encode(RowData rowData) {
            RowDataValueSource value = new RowDataValueSource();
            DynamicMessage.Builder builder = DynamicMessage.newBuilder(messageType);
            for (int i = 0; i < fields.length; i++) {
                value.bind(rowDef.getFieldDef(i), rowData);
                if (value.isNull()) {
                    if (nullFields != null) {
                        FieldDescriptor nullField = nullFields[i];
                        if (nullField != null) {
                            builder.setField(nullField, Boolean.TRUE);
                        }
                    }
                }
                else {
                    conversions[i].setValue(builder, fields[i], value);
                }
            }
            return builder.build();
        }

        @Override
        public void decode(DynamicMessage msg, RowData rowData) {
            Object[] objects = new Object[fields.length];
            for (FieldDescriptor field : msg.getAllFields().keySet()) {
                Integer columnIndex = columnIndexesByField.get(field);
                if (columnIndex != null) {
                    objects[columnIndex] = conversions[columnIndex].getValue(msg, field);
                }
                else {
                    Integer nullIndex = nullableIndexesByField.get(field);
                    if (nullIndex != null) {
                        // TODO: It's already null, because we aren't
                        // handling defaults yet.
                        objects[nullIndex] = null;
                    }
                }
            }
            if (rowData.getBytes() == null) {
                rowData.reset(new byte[RowData.CREATE_ROW_INITIAL_SIZE]);
            }
            rowData.createRow(rowDef, objects, true);
        }
    }

}
