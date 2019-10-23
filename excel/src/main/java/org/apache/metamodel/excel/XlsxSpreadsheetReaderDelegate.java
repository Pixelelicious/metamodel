/**
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
package org.apache.metamodel.excel;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.metamodel.data.DataSet;
import org.apache.metamodel.data.RowPublisherDataSet;
import org.apache.metamodel.data.Style;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.schema.MutableColumn;
import org.apache.metamodel.schema.MutableSchema;
import org.apache.metamodel.schema.MutableTable;
import org.apache.metamodel.schema.Schema;
import org.apache.metamodel.schema.Table;
import org.apache.metamodel.schema.naming.ColumnNamingContext;
import org.apache.metamodel.schema.naming.ColumnNamingContextImpl;
import org.apache.metamodel.schema.naming.ColumnNamingSession;
import org.apache.metamodel.schema.naming.ColumnNamingStrategy;
import org.apache.metamodel.util.FileHelper;
import org.apache.metamodel.util.FileResource;
import org.apache.metamodel.util.Resource;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * {@link SpreadsheetReaderDelegate} implementation for the "new" XLSX format.
 * This implementation is very efficient as it uses SAX XML parsing which does
 * not bloat memory usage in the same way that POI's user model does.
 */
final class XlsxSpreadsheetReaderDelegate implements SpreadsheetReaderDelegate {

    private static final Logger logger = LoggerFactory.getLogger(XlsxSpreadsheetReaderDelegate.class);

    private final Resource _resource;
    private final ExcelConfiguration _configuration;
    private final Map<String, String> _tableNamesToInternalIds;

    public XlsxSpreadsheetReaderDelegate(Resource resource, ExcelConfiguration configuration) {
        _resource = resource;
        _configuration = configuration;
        _tableNamesToInternalIds = new ConcurrentHashMap<String, String>();
    }

    @Override
    public DataSet executeQuery(Table table, List<Column> columns, int maxRows) throws Exception {
        final OPCPackage pkg = openOPCPackage();
        final XSSFReader xssfReader = new XSSFReader(pkg);
        final String relationshipId = _tableNamesToInternalIds.get(table.getName());

        if (relationshipId == null) {
            throw new IllegalStateException("No internal relationshipId found for table: " + table);
        }

        return buildDataSet(columns, maxRows, relationshipId, xssfReader, pkg);
    }

    private OPCPackage openOPCPackage() throws Exception {
        if (_resource instanceof FileResource) {
            final File file = ((FileResource) _resource).getFile();
            return OPCPackage.open(file);
        }

        return OPCPackage.open(_resource.read());
    }

    @Override
    public Schema createSchema(String schemaName) throws Exception {
        final MutableSchema schema = new MutableSchema(schemaName);
        final OPCPackage pkg = openOPCPackage();
        try {
            final XSSFReader xssfReader = new XSSFReader(pkg);

            final XlsxWorkbookToTablesHandler workbookToTables = new XlsxWorkbookToTablesHandler(schema,
                    _tableNamesToInternalIds);
            buildTables(xssfReader, workbookToTables);

            for (Entry<String, String> entry : _tableNamesToInternalIds.entrySet()) {

                final String tableName = entry.getKey();
                final String relationshipId = entry.getValue();

                final MutableTable table = (MutableTable) schema.getTableByName(tableName);

                buildColumns(table, relationshipId, xssfReader);
            }
        } finally {
            pkg.revert();
        }
        return schema;
    }

    @Override
    public void notifyTablesModified() {
        final XlsxWorkbookToTablesHandler workbookToTables = new XlsxWorkbookToTablesHandler(null,
                _tableNamesToInternalIds);
        try {
            final OPCPackage pkg = openOPCPackage();
            try {
                final XSSFReader xssfReader = new XSSFReader(pkg);
                buildTables(xssfReader, workbookToTables);
            } finally {
                pkg.revert();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DataSet buildDataSet(final List<Column> columns, int maxRows, final String relationshipId,
            final XSSFReader xssfReader, final OPCPackage pkg) throws Exception {

        List<SelectItem> selectItems = columns.stream().map(SelectItem::new).collect(Collectors.toList());
        final XlsxRowPublisherAction publishAction = new XlsxRowPublisherAction(_configuration, columns, relationshipId,
                xssfReader);

        return new RowPublisherDataSet(selectItems.toArray(new SelectItem[selectItems.size()]), maxRows, publishAction,
                new Closeable() {
                    @Override
                    public void close() throws IOException {
                        pkg.revert();
                    }
                });
    }

    private ColumnType[] getColumnTypes(final Sheet sheet, final Row row) {
        final Iterator<Row> data = ExcelUtils.getRowIterator(sheet, _configuration, false);
        final int rowLength = row.getLastCellNum();
        final ColumnType[] columnTypes = new ColumnType[rowLength];
        if (_configuration.isDetectColumnTypes()) {

            int eagerness = _configuration.getEagerness();

            while (data.hasNext() && eagerness-- > 0) {
                final Row currentRow = data.next();
                if (currentRow.getRowNum() < _configuration.getColumnNameLineNumber()) {
                    continue;
                }
                for (int index = 0; index < rowLength; index++) {
                    if (currentRow.getLastCellNum() == 0) {
                        continue;
                    }

                    final ColumnType columnType = columnTypes[index];
                    final ColumnType expectedColumnType = getColumnTypeFromRow(currentRow, index);
                    if (columnType != null) {
                        if (!columnType.equals(ColumnType.STRING) && !columnType.equals(expectedColumnType)) {
                            columnTypes[index] = ColumnType.VARCHAR;
                        }
                    } else {
                        columnTypes[index] = expectedColumnType;
                    }
                }
            }
        } else {
            Arrays.fill(columnTypes, ColumnType.STRING);
        }
        return columnTypes;
    }

    private ColumnType getColumnTypeFromRow(final Row currentRow, int index) {
        if (currentRow.getCell(index) == null) {
            return ColumnType.STRING;
        } else {
            switch (currentRow.getCell(index).getCellType()) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(currentRow.getCell(index))) {
                    return ColumnType.DATE;
                } else {
                    return (currentRow.getCell(index).getNumericCellValue() % 1 == 0) ? ColumnType.INTEGER
                            : ColumnType.DOUBLE;
                }
            case BOOLEAN:
                return ColumnType.BOOLEAN;
            case ERROR:
                // fall through
            case _NONE:
                // fall through
            case STRING:
                // fall through
            case FORMULA:
                // fall through
            case BLANK:
                // fall through
            default:
                return ColumnType.STRING;
            }
        }
    }

    /**
     * Builds columns based on row/cell values.
     * 
     * @param table
     * @param wb
     * @param row
     */
    private void createColumns(final MutableTable table, final Workbook wb, final Row row,
            final ColumnType[] columTypes) {
        if (row == null) {
            logger.warn("Cannot create columns based on null row!");
            return;
        }
        final short rowLength = row.getLastCellNum();

        final int offset = getColumnOffset(row);

        // build columns based on cell values.
        try (final ColumnNamingSession columnNamingSession = _configuration
                .getColumnNamingStrategy()
                .startColumnNamingSession()) {
            for (int j = offset; j < rowLength; j++) {
                final Cell cell = row.getCell(j);
                final String intrinsicColumnName = ExcelUtils.getCellValue(wb, cell);
                final ColumnNamingContext columnNamingContext = new ColumnNamingContextImpl(table, intrinsicColumnName,
                        j);
                final String columnName = columnNamingSession.getNextColumnName(columnNamingContext);
                final Column column;
                if (!_configuration.isDetectColumnTypes()) {
                    column = new MutableColumn(columnName, ColumnType.VARCHAR, table, j, true);
                } else {
                    column = new MutableColumn(columnName, columTypes[j], table, j, true);
                }
                table.addColumn(column);
            }
        }
    }

    /**
     * Gets the column offset (first column to include). This is dependent on
     * the row used for column processing and whether the skip empty columns
     * property is set.
     * 
     * @param row
     * @return
     */
    private int getColumnOffset(Row row) {
        final int offset;
        if (_configuration.isSkipEmptyColumns()) {
            offset = row.getFirstCellNum();
        } else {
            offset = 0;
        }
        return offset;
    }

    private void buildColumns(final MutableTable table, final String relationshipId, final XSSFReader xssfReader)
            throws Exception {
        final InputStream sheetData = xssfReader.getSheet(relationshipId);

        final Workbook wb = ExcelUtils.readWorkbook(_resource, true);
        Sheet sheet = wb.getSheetAt(0);
        final Iterator<Row> rowIterator = ExcelUtils.getRowIterator(sheet, _configuration, false);

        Row row = null;
        if (!rowIterator.hasNext()) {
            // no physical rows in sheet
            return;
        }

        if (_configuration.isSkipEmptyLines()) {
            while (row == null && rowIterator.hasNext()) {
                row = rowIterator.next();
            }
        } else {
            row = rowIterator.next();
        }
        final Row currentRow = row;
        
        final XlsxRowCallback rowCallback = new XlsxRowCallback() {
            @Override
            public boolean row(int rowNumber, List<String> values, List<Style> styles) {
                final int columnNameLineNumber = _configuration.getColumnNameLineNumber();
                final boolean hasColumnNameLine = columnNameLineNumber != ExcelConfiguration.NO_COLUMN_NAME_LINE;

                if (hasColumnNameLine) {
                    final int zeroBasedLineNumber = columnNameLineNumber - 1;
                    if (rowNumber < zeroBasedLineNumber) {
                        // jump to read the next line
                        return true;
                    }
                }

                final ColumnNamingStrategy columnNamingStrategy = _configuration.getColumnNamingStrategy();
                try (ColumnNamingSession session = columnNamingStrategy.startColumnNamingSession()) {
                    for (int i = 0; i < values.size(); i++) {
                        final String intrinsicColumnName = hasColumnNameLine ? values.get(i) : null;
                        final String columnName = session.getNextColumnName(new ColumnNamingContextImpl(table,
                                intrinsicColumnName, i));

                        if (!(_configuration.isSkipEmptyColumns() && values.get(i) == null)) {
                            if (currentRow != null) {
                                final ColumnType[] columnTypes = getColumnTypes(sheet, currentRow);
                                table.addColumn(new MutableColumn(columnName, columnTypes[i], table, i, true));
                            } else {
                                table.addColumn(new MutableColumn(columnName, ColumnType.STRING, table, i, true));
                            }
                        }
                    }
                }

                // now we're done, no more reading
                return false;
            }
        };
        final XlsxSheetToRowsHandler handler = new XlsxSheetToRowsHandler(rowCallback, xssfReader, _configuration);

        final XMLReader sheetParser = ExcelUtils.createXmlReader();
        sheetParser.setContentHandler(handler);
        try {
            sheetParser.parse(new InputSource(sheetData));
        } catch (XlsxStopParsingException e) {
            logger.debug("Parsing stop signal thrown");
        } finally {
            FileHelper.safeClose(sheetData);
        }
    }

    private void buildTables(final XSSFReader xssfReader, final XlsxWorkbookToTablesHandler workbookToTables)
            throws Exception {
        final InputStream workbookData = xssfReader.getWorkbookData();
        final XMLReader workbookParser = ExcelUtils.createXmlReader();
        workbookParser.setContentHandler(workbookToTables);
        workbookParser.parse(new InputSource(workbookData));
        FileHelper.safeClose(workbookData);
    }
}
