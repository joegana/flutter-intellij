/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.logging;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.ui.treeStructure.treetable.TreeTable;
import com.intellij.ui.treeStructure.treetable.TreeTableTree;
import com.intellij.util.Alarm;
import com.intellij.util.EventDispatcher;
import com.intellij.util.ui.ColumnInfo;
import io.flutter.console.FlutterConsoleFilter;
import io.flutter.run.daemon.FlutterApp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.MutableTreeNode;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static io.flutter.logging.FlutterLogConstants.LogColumns.*;

public class FlutterLogTree extends TreeTable {

  private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");

  private static class ColumnModel {

    private abstract class EntryCellRenderer extends ColoredTableCellRenderer {
      @Override
      protected final void customizeCellRenderer(JTable table,
                                                 @Nullable Object value,
                                                 boolean selected,
                                                 boolean hasFocus,
                                                 int row,
                                                 int column) {
        if (value instanceof FlutterLogEntry) {
          render((FlutterLogEntry)value);
        }
      }

      @Override
      public void acquireState(JTable table, boolean isSelected, boolean hasFocus, int row, int column) {
        // Prevent cell borders on selected cells.
        super.acquireState(table, isSelected, false, row, column);
      }

      void appendStyled(FlutterLogEntry entry, String text) {
        append(text, entryModel.style(entry, STYLE_PLAIN));
      }


      abstract void render(FlutterLogEntry entry);
    }

    abstract class Column extends ColumnInfo<DefaultMutableTreeNode, FlutterLogEntry> {
      boolean show = true;
      private TableCellRenderer renderer;

      Column(String name) {
        super(name);
      }

      boolean isVisible() {
        return show;
      }

      @Nullable
      @Override
      public FlutterLogEntry valueOf(DefaultMutableTreeNode node) {
        if (node instanceof FlutterEventNode) {
          return ((FlutterEventNode)node).entry;
        }
        return null;
      }

      @Override
      public TableCellRenderer getCustomizedRenderer(DefaultMutableTreeNode o, TableCellRenderer renderer) {
        if (renderer == null) {
          renderer = createRenderer();
        }
        return renderer;
      }

      abstract TableCellRenderer createRenderer();
    }

    private final List<Column> columns = new ArrayList<>();

    private final FlutterApp app;
    @NotNull private final EntryModel entryModel;
    boolean updateVisibility;
    private List<Column> visible;
    private ArrayList<TableColumn> tableColumns;
    private TreeTable treeTable;

    ColumnModel(@NotNull FlutterApp app, @NotNull EntryModel entryModel) {
      this.app = app;
      this.entryModel = entryModel;
      columns.add(new Column("Time") {
        @Override
        TableCellRenderer createRenderer() {
          return new EntryCellRenderer() {
            @Override
            void render(FlutterLogEntry entry) {
              appendStyled(entry, TIMESTAMP_FORMAT.format(entry.getTimestamp()));
            }
          };
        }
      });
      columns.add(new Column("Sequence") {
        @Override
        TableCellRenderer createRenderer() {
          return new EntryCellRenderer() {
            @Override
            void render(FlutterLogEntry entry) {
              appendStyled(entry, Integer.toString(entry.getSequenceNumber()));
            }
          };
        }
      });
      columns.add(new Column("Level") {
        @Override
        TableCellRenderer createRenderer() {
          return new EntryCellRenderer() {
            @Override
            void render(FlutterLogEntry entry) {
              final FlutterLog.Level level = FlutterLog.Level.forValue(entry.getLevel());
              final String value = level != null ? level.name() : Integer.toString(entry.getLevel());
              appendStyled(entry, value);
            }
          };
        }
      });
      columns.add(new Column("Category") {
        @Override
        TableCellRenderer createRenderer() {
          return new EntryCellRenderer() {
            @Override
            void render(FlutterLogEntry entry) {
              appendStyled(entry, entry.getCategory());
            }
          };
        }
      });
      columns.add(new Column("Message") {
        @Override
        TableCellRenderer createRenderer() {
          return new EntryCellRenderer() {
            // TODO(pq): handle possible null module.
            FlutterConsoleFilter consoleFilter = new FlutterConsoleFilter(app.getModule());

            @Override
            void render(FlutterLogEntry entry) {
              // TODO(pq): SpeedSearchUtil.applySpeedSearchHighlighting
              // TODO(pq): setTooltipText
              final String message = entry.getMessage();
              int cursor = 0;

              // TODO(pq): add support for dart uris, etc.
              // TODO(pq): fix FlutterConsoleFilter to handle multiple links.
              final Filter.Result result = consoleFilter.applyFilter(message, message.length());
              if (result != null) {
                for (Filter.ResultItem item: result.getResultItems()) {
                  final HyperlinkInfo hyperlinkInfo = item.getHyperlinkInfo();
                  if (hyperlinkInfo != null) {
                    final int start = item.getHighlightStartOffset();
                    final int end = item.getHighlightEndOffset();
                    // append leading text.
                    if (cursor < start) {
                      appendStyled(entry, message.substring(cursor, start));
                    }
                    // TODO(pq): re-style hyperlinks?
                    append(message.substring(start, end), SimpleTextAttributes.LINK_ATTRIBUTES, hyperlinkInfo);
                    cursor = end;
                  }
                }
              }

              // append trailing text
              if (cursor < message.length()) {
                appendStyled(entry, message.substring(cursor));
              }
            }
          };
        }
      });
      // Cache for quick access.
      visible = columns.stream().filter(c -> c.show).collect(Collectors.toList());
    }

    void show(String column, boolean show) {
      columns.forEach(c -> {
        if (Objects.equals(column, c.getName())) {
          c.show = show;
        }
      });

      updateVisibility = true;

      // Cache for quick access.
      visible = columns.stream().filter(c -> c.show).collect(Collectors.toList());
    }

    void update() {
      if (updateVisibility) {
        // Clear all.
        Collections.list(treeTable.getColumnModel().getColumns()).forEach(c -> treeTable.removeColumn(c));

        // Add back what's appropriate.
        if (isShowing(TIME)) {
          treeTable.addColumn(tableColumns.get(0));
        }
        if (isShowing(SEQUENCE)) {
          treeTable.addColumn(tableColumns.get(1));
        }
        if (isShowing(LEVEL)) {
          treeTable.addColumn(tableColumns.get(2));
        }
        if (isShowing(CATEGORY)) {
          treeTable.addColumn(tableColumns.get(3));
        }

        tableColumns.subList(4, tableColumns.size()).forEach(c -> treeTable.addColumn(c));
      }

      updateVisibility = false;
    }

    ColumnInfo[] getInfos() {
      return columns.toArray(new ColumnInfo[0]);
    }

    public TableCellRenderer getRenderer(int column) {
      return visible.get(column).createRenderer();
    }

    public boolean isShowing(String column) {
      for (Column c: columns) {
        if (Objects.equals(column, c.getName())) {
          return c.show;
        }
      }
      return false;
    }

    public void init(TreeTable table) {
      treeTable = table;
      tableColumns = Collections.list(table.getColumnModel().getColumns());
    }
  }

  static class TreeModel extends ListTreeTableModelOnColumns {
    @NotNull
    private final ColumnModel columns;
    @NotNull
    private final FlutterLog log;
    @NotNull
    private final Alarm uiThreadAlarm;
    boolean autoScrollToEnd;
    // Cached for hide and restore (because *sigh* Swing).
    private List<TableColumn> tableColumns;
    private JScrollPane scrollPane;
    private TreeTable treeTable;
    private boolean color;

    public TreeModel(@NotNull FlutterApp app,
                     @NotNull EntryModel entryModel,
                     @NotNull Disposable parent) {
      this(new ColumnModel(app, entryModel), parent);
    }

    private TreeModel(@NotNull ColumnModel columns, @NotNull Disposable parent) {
      super(new LogRootTreeNode(), columns.getInfos());

      this.log = columns.app.getFlutterLog();
      this.columns = columns;

      // Scroll to end by default.
      autoScrollToEnd = true;

      setShowSequenceNumbers(false);

      uiThreadAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
    }


    void scrollToEnd() {
      if (scrollPane != null) {
        final JScrollBar vertical = scrollPane.getVerticalScrollBar();
        vertical.setValue(vertical.getMaximum());
      }
    }

    void update() {
      columns.update();

      reload(getRoot());
      treeTable.updateUI();

      if (autoScrollToEnd) {
        scrollToEnd();
      }
    }

    @Override
    public LogRootTreeNode getRoot() {
      return (LogRootTreeNode)super.getRoot();
    }

    public void setScrollPane(JScrollPane scrollPane) {
      this.scrollPane = scrollPane;
    }

    @Override
    public void setTree(JTree tree) {
      super.setTree(tree);
      treeTable = ((TreeTableTree)tree).getTreeTable();
      columns.init(treeTable);
    }

    public void clearEntries() {
      log.clear();
      getRoot().removeAllChildren();
      update();
    }

    public void appendNodes(List<FlutterLogEntry> entries) {
      if (treeTable == null || uiThreadAlarm.isDisposed()) {
        return;
      }

      uiThreadAlarm.cancelAllRequests();
      uiThreadAlarm.addRequest(() -> {
        final MutableTreeNode root = getRoot();
        entries.forEach(entry -> insertNodeInto(new FlutterEventNode(entry), root, root.getChildCount()));
        update();

        // Schedule an update to scroll after the model has had time to re-render.
        uiThreadAlarm.addRequest(() -> {
          if (autoScrollToEnd) {
            scrollToEnd();
          }
          // A simple delay should suffice, given our mantra of eventual consistency.
        }, 100);
      }, 10);
    }

    public boolean shouldShowTimestamps() {
      return columns.isShowing(TIME);
    }

    public void setShowTimestamps(boolean show) {
      columns.show(TIME, show);
    }

    public boolean shouldShowSequenceNumbers() {
      return columns.isShowing(SEQUENCE);
    }

    public void setShowSequenceNumbers(boolean show) {
      columns.show(SEQUENCE, show);
    }

    public boolean shouldShowLogLevels() {
      return columns.isShowing(LEVEL);
    }

    public void setShowLogLevels(boolean show) {
      columns.show(LEVEL, show);
    }

    public void setShowCategories(boolean show) {
      columns.show(CATEGORY, show);
    }

    public boolean shouldShowCategories() {
      return columns.isShowing(CATEGORY);
    }
  }

  static class LogRootTreeNode extends DefaultMutableTreeNode {

  }

  static class FlutterEventNode extends DefaultMutableTreeNode {
    final FlutterLogEntry entry;

    FlutterEventNode(FlutterLogEntry entry) {
      this.entry = entry;
    }

    public void describeTo(@NotNull StringBuilder buffer) {
      buffer
        .append(TIMESTAMP_FORMAT.format(entry.getTimestamp()))
        .append(" ").append(entry.getSequenceNumber())
        .append(" ").append(entry.getLevel())
        .append(" ").append(entry.getCategory())
        .append(" ").append(entry.getMessage());
      if (!entry.getMessage().endsWith("\n")) {
        buffer.append("\n");
      }
    }
  }

  public static class EntryFilter {
    @NotNull
    private final FlutterLogFilterPanel.FilterParam filterParam;

    public EntryFilter(@NotNull FlutterLogFilterPanel.FilterParam filterParam) {
      this.filterParam = filterParam;
    }

    public boolean accept(@NotNull FlutterLogEntry entry) {
      if (entry.getLevel() < filterParam.getLogLevel().value) {
        return false;
      }
      final String text = filterParam.getExpression();
      if (text == null) {
        return true;
      }
      final boolean isMatchCase = filterParam.isMatchCase();
      final String standardText = isMatchCase ? text : text.toLowerCase();
      final String standardMessage = isMatchCase ? entry.getMessage() : entry.getMessage().toLowerCase();
      final String standardCategory = isMatchCase ? entry.getCategory() : entry.getCategory().toLowerCase();
      if (acceptByCheckingRegexOption(standardCategory, standardText)) {
        return true;
      }
      return acceptByCheckingRegexOption(standardMessage, standardText);
    }

    private boolean acceptByCheckingRegexOption(@NotNull String message, @NotNull String text) {
      if (filterParam.isRegex()) {
        return message.matches("(?s).*" + text + ".*");
      }
      return message.contains(text);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      final EntryFilter filter = (EntryFilter)o;
      return Objects.equals(filterParam, filter.filterParam);
    }

    @Override
    public int hashCode() {
      return Objects.hash(filterParam);
    }
  }

  public interface EntryModel {
    SimpleTextAttributes style(@Nullable FlutterLogEntry entry, int attributes);
  }

  public interface EventCountListener extends EventListener {
    void updated(int filtered, int total);
  }

  private final EventDispatcher<EventCountListener> countDispatcher = EventDispatcher.create(EventCountListener.class);
  private final TreeModel model;
  private EntryFilter filter;

  public FlutterLogTree(@NotNull FlutterApp app,
                        @NotNull EntryModel entryModel,
                        @NotNull Disposable parent) {
    this(new TreeModel(app, entryModel, parent));
  }

  FlutterLogTree(@NotNull TreeModel model) {
    super(model);
    model.setTree(this.getTree());
    this.model = model;
  }

  public void addListener(@NotNull EventCountListener listener, @NotNull Disposable parent) {
    countDispatcher.addListener(listener, parent);
  }

  public void removeListener(@NotNull EventCountListener listener) {
    countDispatcher.removeListener(listener);
  }

  @NotNull
  TreeModel getLogTreeModel() {
    return model;
  }

  @Override
  public TableCellRenderer getCellRenderer(int row, int column) {
    return model.columns.getRenderer(column);
  }

  public void setFilter(@Nullable EntryFilter filter) {
    // Only set and reload if the filter has changed.
    if (!Objects.equals(this.filter, filter)) {
      this.filter = filter;
      reload();
    }
  }

  void reload() {
    ApplicationManager.getApplication().invokeLater(() -> {
      model.getRoot().removeAllChildren();

      final List<FlutterLogEntry> entries = model.log.getEntries();
      final List<FlutterLogEntry> matched = entries.stream()
        .filter(entry -> filter == null || filter.accept(entry)).collect(Collectors.toList());

      model.appendNodes(matched);

      countDispatcher.getMulticaster().updated(entries.size() - matched.size(), entries.size());
    });
  }

  public void clearEntries() {
    model.clearEntries();
    reload();
  }
}
