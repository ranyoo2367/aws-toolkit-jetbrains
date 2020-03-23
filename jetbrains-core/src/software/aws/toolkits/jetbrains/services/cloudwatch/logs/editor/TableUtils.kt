// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.cloudwatch.logs.editor

import com.intellij.ui.components.JBTextArea
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import software.amazon.awssdk.services.cloudwatchlogs.model.LogStream
import software.aws.toolkits.jetbrains.services.cloudwatch.logs.LogStreamEntry
import software.aws.toolkits.resources.message
import java.awt.Component
import javax.swing.JTable
import javax.swing.SortOrder
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableRowSorter

class LogStreamsStreamColumn : ColumnInfo<LogStream, String>(message("cloudwatch.logs.log_streams")) {
    override fun valueOf(item: LogStream?): String? = item?.logStreamName()

    override fun isCellEditable(item: LogStream?): Boolean = false
}

class LogStreamsDateColumn : ColumnInfo<LogStream, String>(message("cloudwatch.logs.last_event_time")) {
    override fun valueOf(item: LogStream?): String? {
        val timestamp = item?.lastEventTimestamp() ?: return null
        return DateFormatUtil.getDateTimeFormat().format(timestamp)
    }

    override fun isCellEditable(item: LogStream?): Boolean = false
}

class LogGroupTableSorter(model: ListTableModel<LogStream>) : TableRowSorter<ListTableModel<LogStream>>(model) {
    init {
        sortKeys = listOf(SortKey(1, SortOrder.UNSORTED))
        setSortable(0, false)
        setSortable(1, false)
    }
}

class LogStreamDateColumn : ColumnInfo<LogStreamEntry, String>(message("general.time")) {
    override fun valueOf(item: LogStreamEntry?): String? {
        val timestamp = item?.timestamp ?: return null
        return DateFormatUtil.getDateTimeFormat().format(timestamp)
    }

    override fun isCellEditable(item: LogStreamEntry?): Boolean = false
}

class LogStreamMessageColumn : ColumnInfo<LogStreamEntry, String>(message("general.message")) {
    private val renderer = WrappingLogStreamMessageRenderer()
    fun setWrap(wrap: Boolean) {
        renderer.wrap = wrap
    }

    override fun valueOf(item: LogStreamEntry?): String? = item?.message
    override fun isCellEditable(item: LogStreamEntry?): Boolean = false
    override fun getRenderer(item: LogStreamEntry?): TableCellRenderer? = renderer
}

private class WrappingLogStreamMessageRenderer : TableCellRenderer {
    var wrap: Boolean = false
        set(value) {
            field = value
            componentCache = mutableMapOf()
        }
    private var componentCache = mutableMapOf<Pair<Int, Int>, Component>()
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val item = componentCache[Pair(row, column)]
        if (item != null) {
            return item
        }
        val component = JBTextArea()
        component.wrapStyleWord = wrap
        component.lineWrap = wrap
        component.text = (value as? String)?.trim()
        component.setSize(table.columnModel.getColumn(column).width, component.preferredSize.height)
        if (table.getRowHeight(row) != component.preferredSize.height) {
            table.setRowHeight(row, component.preferredSize.height)
        }
        componentCache[Pair(row, column)] = component
        return component
    }
}
