package svkreml.prop_loc_edit.gui;

import svkreml.prop_loc_edit.OrderedProperties;
import svkreml.prop_loc_edit.PropIo;
import svkreml.prop_loc_edit.ai.AiConfig;
import svkreml.prop_loc_edit.ai.AiTranslationService;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

public class TranslationEditor extends JFrame {
    private static final Logger LOG = Logger.getLogger(TranslationEditor.class.getName());
    private static final int UNDO_LIMIT = 100;

    private JTable table;
    private DefaultTableModel tableModel;
    private OrderedProperties englishProps;
    private OrderedProperties russianProps;
    private String englishFile;
    private String russianFile;
    private final transient Preferences prefs;
    private final transient AiTranslationService aiService = new AiTranslationService();
    private static final String PREF_ENGLISH_FILE = "englishFile";
    private static final String PREF_RUSSIAN_FILE = "russianFile";
    private static final String PREF_ESCAPE_UNICODE = "escapeUnicode";

    private JDialog progressDialog;
    private JProgressBar progressBar;
    private JLabel progressLabel;

    private JToolBar toolbar;
    private JButton translateButton;
    private JButton batchTranslateButton;
    private JButton verifyButton;

    private JTextField filterField;
    private JCheckBox missingOnlyCheck;
    private JCheckBox dirtyOnlyCheck;
    private JLabel statusBarLabel;
    private JLabel filterCountLabel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private long lastSaveTime;
    private AiConfig aiConfigSnapshot;

    private final ArrayDeque<List<Object[]>> undoStack = new ArrayDeque<>();
    private final ArrayDeque<List<Object[]>> redoStack = new ArrayDeque<>();
    private boolean suppressUndo;
    private boolean rowNumbersScheduled;
    private boolean escapeUnicodePref;

    private static final Color DIRTY_BG = new Color(255, 244, 179);
    private static final Color EMPTY_BG = new Color(255, 160, 160);
    private static final Vector<String> TABLE_COLUMNS =
            new Vector<>(Arrays.asList("№", "Ключ", "Английский", "Русский"));
    private final Map<String, String[]> baseline = new HashMap<>();
    private final Set<String> dirtyKeys = new HashSet<>();

    private void setTableData(List<Object[]> rows) {
        Vector<Vector<Object>> data = new Vector<>(rows.size());
        for (Object[] e : rows) {
            Vector<Object> row = new Vector<>(4);
            row.add(e[0]);
            row.add(e[1]);
            row.add(e[2]);
            row.add(e[3]);
            data.add(row);
        }
        tableModel.setDataVector(data, TABLE_COLUMNS);
    }

    public TranslationEditor() {
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exitApplication();
            }
        });
        setSize(1400, 1000);
        setLocationRelativeTo(null);

        // Инициализация настроек
        prefs = Preferences.userNodeForPackage(TranslationEditor.class);
        loadPreferences();
        aiConfigSnapshot = AiConfig.exists() ? AiConfig.load() : null;

        initializeComponents();

        // Если файлы не заданы, открываем диалог выбора
        if (englishFile == null || russianFile == null ||
            englishFile.isEmpty() || russianFile.isEmpty()) {
            chooseFiles();
        }

        loadProperties();
        setupMenuBar();
        updateWindowTitle();
        bindShortcuts();
    }

    private void loadPreferences() {
        englishFile = prefs.get(PREF_ENGLISH_FILE, "");
        russianFile = prefs.get(PREF_RUSSIAN_FILE, "");
        escapeUnicodePref = prefs.getBoolean(PREF_ESCAPE_UNICODE, false);
    }

    private void savePreferences() {
        prefs.put(PREF_ENGLISH_FILE, englishFile);
        prefs.put(PREF_RUSSIAN_FILE, russianFile);
    }

    private void bindShortcuts() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focusFilter");
        getRootPane().getActionMap().put("focusFilter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                filterField.requestFocusInWindow();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undoAction");
        getRootPane().getActionMap().put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undoTable();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redoAction");
        getRootPane().getActionMap().put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redoTable();
            }
        });
        filterField.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clearFilter");
        filterField.getActionMap().put("clearFilter", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                filterField.setText("");
                table.requestFocusInWindow();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveAction");
        getRootPane().getActionMap().put("saveAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                saveProperties();
            }
        });
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redoAction2");
        getRootPane().getActionMap().put("redoAction2", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                redoTable();
            }
        });
        table.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteRowAction");
        table.getActionMap().put("deleteRowAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelectedRow();
            }
        });
    }

    private void applyFilter() {
        if (rowSorter == null) {
            return;
        }
        String query = filterField.getText().trim().toLowerCase();
        boolean onlyMissing = missingOnlyCheck.isSelected();
        boolean onlyDirty = dirtyOnlyCheck.isSelected();
        rowSorter.setRowFilter(new RowFilter<DefaultTableModel, Integer>() {
            @Override
            public boolean include(RowFilter.Entry<? extends DefaultTableModel, ? extends Integer> entry) {
                if (onlyDirty) {
                    Object k = entry.getValue(1);
                    if (k == null || !dirtyKeys.contains(String.valueOf(k))) {
                        return false;
                    }
                }
                if (onlyMissing) {
                    Object ru = entry.getValue(3);
                    if (ru != null && !String.valueOf(ru).trim().isEmpty()) {
                        return false;
                    }
                }
                if (!query.isEmpty()) {
                    for (int c = 1; c <= 3; c++) {
                        Object v = entry.getValue(c);
                        if (v != null && String.valueOf(v).toLowerCase().contains(query)) {
                            return true;
                        }
                    }
                    return false;
                }
                return true;
            }
        });
        filterCountLabel.setText("Показано: " + table.getRowCount() + " / " + tableModel.getRowCount());
    }

    private void updateStatusBar() {
        if (statusBarLabel == null) {
            return;
        }
        int total = tableModel.getRowCount();
        int missingEn = 0;
        int missingRu = 0;
        for (int i = 0; i < total; i++) {
            String en = (String) tableModel.getValueAt(i, 2);
            String ru = (String) tableModel.getValueAt(i, 3);
            if (en == null || en.trim().isEmpty()) {
                missingEn++;
            }
            if (ru == null || ru.trim().isEmpty()) {
                missingRu++;
            }
        }
        StringBuilder sb = new StringBuilder("Ключей: ").append(total)
                .append("   |   без английского: ").append(missingEn)
                .append("   |   без русского: ").append(missingRu)
                .append("   |   изменено: ").append(dirtyKeys.size());
        if (lastSaveTime > 0) {
            sb.append("   |   сохранено: ").append(new SimpleDateFormat("HH:mm:ss").format(new Date(lastSaveTime)));
        }
        if (aiConfigSnapshot != null && aiConfigSnapshot.getBaseUrl() != null
                && !aiConfigSnapshot.getBaseUrl().isEmpty()) {
            sb.append("   |   ИИ: ").append(aiConfigSnapshot.getBaseUrl())
                    .append(" (").append(aiConfigSnapshot.getThreads()).append(" потоков)");
        }
        statusBarLabel.setText(sb.toString());
    }

    private void updateWindowTitle() {
        String ru = russianFile == null || russianFile.isEmpty() ? "" : new File(russianFile).getName();
        String en = englishFile == null || englishFile.isEmpty() ? "" : " / " + new File(englishFile).getName();
        setTitle("Редактор переводов" + (ru.isEmpty() ? "" : " — " + ru + en));
    }

    private void refreshAiConfig() {
        aiConfigSnapshot = AiConfig.exists() ? AiConfig.load() : null;
        updateStatusBar();
        updateWindowTitle();
    }

    private void setEscapeUnicode(boolean enabled) {
        escapeUnicodePref = enabled;
        prefs.putBoolean(PREF_ESCAPE_UNICODE, enabled);
        if (englishProps != null) {
            englishProps.setEscapeUnicode(enabled);
        }
        if (russianProps != null) {
            russianProps.setEscapeUnicode(enabled);
        }
        LOG.info("Экранирование юникода при сохранении: " + (enabled ? "включено" : "выключено"));
    }

    private boolean backupFile(String path) {
        if (path == null || path.isEmpty()) {
            return true;
        }
        File src = new File(path);
        if (!src.exists()) {
            return true;
        }
        try {
            File bak = new File(src.getParent(), src.getName() + ".bak");
            Files.copy(src.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            LOG.severe("Failed to create backup: " + path + " - " + e.getMessage());
            return false;
        }
    }

    private void warnAboutDuplicateKeys(String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        Map<String, List<Integer>> dups = PropIo.findDuplicateKeyLines(path);
        if (dups.isEmpty()) {
            return;
        }
        LOG.warning("Найдены дубликаты ключей в файле: " + path + " (ключей с дубликатами: " + dups.size() + ")");
        DuplicateKeysDialog dialog = new DuplicateKeysDialog(this, path);
        dialog.setVisible(true);
        if (dialog.isApplied()) {
            LOG.info("Duplicate keys fixed in: " + path);
            loadProperties();
        }
    }

    private void initializeComponents() {
        // Создание таблицы с номерами строк
        tableModel = new DefaultTableModel(new Object[]{"№", "Ключ", "Английский", "Русский"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > 0; // Все колонки кроме номера строки редактируемы
            }

            @Override
            public void setValueAt(Object aValue, int row, int column) {
                String oldKey = null;
                if (column > 0) {
                    Object k = getValueAt(row, 1);
                    oldKey = k == null ? null : String.valueOf(k);
                }
                if (column > 0 && !suppressUndo) {
                    pushUndoSnapshot();
                }
                super.setValueAt(aValue, row, column);
                if (column > 0 && !suppressUndo) {
                    if (oldKey != null) {
                        dirtyKeys.remove(oldKey);
                    }
                    markRowDirty(row);
                    updateStatusBar();
                }
            }
        };

        table = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);

                if (isCellSelected(row, column)) {
                    return c;
                }

                // Для колонки с номерами строк
                if (column == 0) {
                    c.setBackground(new Color(238, 240, 245));
                    return c;
                }

                // Пустые ячейки подсвечиваем красным
                Object v = getValueAt(row, column);
                if (v == null || v.toString().trim().isEmpty()) {
                    c.setBackground(EMPTY_BG);
                    return c;
                }

                // Изменённые, но не сохранённые ячейки подсвечиваем жёлтым
                if (isCellDirtyView(row)) {
                    c.setBackground(DIRTY_BG);
                    return c;
                }

                c.setBackground(UIManager.getColor("Table.background")); // По умолчанию
                return c;
            }
        };

        table.setRowHeight(56);
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(224, 226, 230));
        table.setIntercellSpacing(new Dimension(1, 1));
        TableColumn numCol = table.getColumnModel().getColumn(0);
        numCol.setMinWidth(36);
        numCol.setPreferredWidth(48);
        numCol.setMaxWidth(56);  // № — ширина под 5-6 символов
        table.getColumnModel().getColumn(1).setPreferredWidth(150); // Ключ
        table.getColumnModel().getColumn(2).setPreferredWidth(300); // Английский
        table.getColumnModel().getColumn(3).setPreferredWidth(300); // Русский

        // Установка кастомного рендерера для номеров строк
        table.getColumnModel().getColumn(0).setCellRenderer(new RowNumberRenderer());

        // Установка кастомного редактора для многострочного текста
        table.getColumnModel().getColumn(1).setCellEditor(new KeyCellEditor());
        table.getColumnModel().getColumn(2).setCellEditor(new MultiLineCellEditor());
        table.getColumnModel().getColumn(3).setCellEditor(new MultiLineCellEditor());

        // Установка кастомного рендерера для отображения многострочного текста
        table.getColumnModel().getColumn(1).setCellRenderer(new KeyCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new MultiLineCellRenderer());
        table.getColumnModel().getColumn(3).setCellRenderer(new MultiLineCellRenderer());

        // Добавляем слушатель изменений в таблице
        tableModel.addTableModelListener(e -> {
            if (e.getType() == javax.swing.event.TableModelEvent.INSERT ||
                e.getType() == javax.swing.event.TableModelEvent.DELETE) {
                scheduleRowNumbersUpdate();
            }
            updateStatusBar();
            applyFilter();
            table.repaint();
        });

        rowSorter = new TableRowSorter<>(tableModel);
        rowSorter.setSortable(0, false);
        table.setRowSorter(rowSorter);

        JScrollPane scrollPane = new JScrollPane(table);

        filterField = new JTextField(24);
        filterField.setToolTipText("Поиск по ключу, английскому или русскому тексту (Ctrl+F)");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        missingOnlyCheck = new JCheckBox("Только без русского перевода");
        missingOnlyCheck.addActionListener(e -> applyFilter());
        dirtyOnlyCheck = new JCheckBox("Только не сохранённое");
        dirtyOnlyCheck.addActionListener(e -> applyFilter());
        JButton clearFilterButton = new JButton("Очистить");
        clearFilterButton.addActionListener(e -> {
            filterField.setText("");
            missingOnlyCheck.setSelected(false);
            dirtyOnlyCheck.setSelected(false);
            table.requestFocusInWindow();
        });
        filterCountLabel = new JLabel(" ");

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        filterPanel.add(new JLabel("Поиск:"));
        filterPanel.add(filterField);
        filterPanel.add(missingOnlyCheck);
        filterPanel.add(dirtyOnlyCheck);
        filterPanel.add(clearFilterButton);
        filterPanel.add(filterCountLabel);

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(filterPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);

        statusBarLabel = new JLabel(" ");
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusBar.add(statusBarLabel, BorderLayout.CENTER);
        add(statusBar, BorderLayout.SOUTH);

        // Панель инструментов
        toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        JButton addButton = new JButton("Добавить");
        JButton deleteButton = new JButton("Удалить");
        JButton saveButton = new JButton("Сохранить");
        JButton chooseFilesButton = new JButton("Выбрать файлы");
        translateButton = new JButton("Перевести (ИИ)");
        batchTranslateButton = new JButton("Пакетный перевод (ИИ)");
        verifyButton = new JButton("Проверить перевод (ИИ)");

        addButton.addActionListener(e -> addNewRow());
        deleteButton.addActionListener(e -> deleteSelectedRow());
        saveButton.addActionListener(e -> saveProperties());
        chooseFilesButton.addActionListener(e -> chooseFiles());
        translateButton.addActionListener(e -> translateSelectedRow());
        batchTranslateButton.addActionListener(e -> batchTranslate());
        verifyButton.addActionListener(e -> verifySelectedRows());

        toolbar.add(addButton);
        toolbar.add(deleteButton);
        toolbar.add(saveButton);
        toolbar.add(chooseFilesButton);
        toolbar.addSeparator();
        addAiButtonsIfConfigured();
        add(toolbar, BorderLayout.NORTH);
    }

    private void addAiButtonsIfConfigured() {
        if (!AiConfig.exists()) {
            return;
        }
        if (Arrays.asList(toolbar.getComponents()).contains(translateButton)) {
            return;
        }
        toolbar.addSeparator();
        toolbar.add(translateButton);
        toolbar.add(batchTranslateButton);
        toolbar.add(verifyButton);
        toolbar.revalidate();
        toolbar.repaint();
    }

    private void removeAiButtons() {
        toolbar.remove(translateButton);
        toolbar.remove(batchTranslateButton);
        toolbar.remove(verifyButton);
        toolbar.revalidate();
        toolbar.repaint();
    }

    // Обновление номеров строк
    private void scheduleRowNumbersUpdate() {
        if (rowNumbersScheduled) {
            return;
        }
        rowNumbersScheduled = true;
        SwingUtilities.invokeLater(() -> {
            rowNumbersScheduled = false;
            updateRowNumbers();
        });
    }

    private void updateRowNumbers() {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            tableModel.setValueAt(i + 1, i, 0);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void markRowDirty(int modelRow) {
        Object k = tableModel.getValueAt(modelRow, 1);
        if (k == null) {
            return;
        }
        String key = String.valueOf(k);
        String en = nz((String) tableModel.getValueAt(modelRow, 2));
        String ru = nz((String) tableModel.getValueAt(modelRow, 3));
        String[] b = baseline.get(key);
        boolean dirty = b == null || !Objects.equals(b[0], en) || !Objects.equals(b[1], ru);
        if (dirty) {
            dirtyKeys.add(key);
        } else {
            dirtyKeys.remove(key);
        }
    }

    private void recomputeDirty() {
        dirtyKeys.clear();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            markRowDirty(i);
        }
        updateStatusBar();
    }

    private void rebuildBaseline() {
        baseline.clear();
        dirtyKeys.clear();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            Object k = tableModel.getValueAt(i, 1);
            if (k != null && !String.valueOf(k).isEmpty()) {
                baseline.put(String.valueOf(k), new String[]{
                        nz((String) tableModel.getValueAt(i, 2)),
                        nz((String) tableModel.getValueAt(i, 3))});
            }
        }
        updateStatusBar();
    }

    private boolean isCellDirty(int modelRow) {
        Object k = tableModel.getValueAt(modelRow, 1);
        return k != null && dirtyKeys.contains(String.valueOf(k));
    }

    private boolean isCellDirtyView(int viewRow) {
        if (viewRow < 0 || viewRow >= table.getRowCount()) {
            return false;
        }
        return isCellDirty(table.convertRowIndexToModel(viewRow));
    }

    private String pickPropertiesFile(String title) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle(title);
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile().getAbsolutePath();
        }
        return null;
    }

    private void chooseFiles() {
        String newEnglishFile = pickPropertiesFile("Выберите файл английских переводов (messages_en.properties)");
        if (newEnglishFile == null) {
            return;
        }
        String newRussianFile = pickPropertiesFile("Выберите файл русских переводов (messages_ru.properties)");
        if (newRussianFile == null) {
            return;
        }
        applyChosenFiles(newEnglishFile, newRussianFile);
    }

    private void chooseEnglishFile() {
        String f = pickPropertiesFile("Выберите файл английских переводов (messages_en.properties)");
        if (f == null) {
            return;
        }
        if (Objects.equals(f, russianFile)) {
            JOptionPane.showMessageDialog(this,
                    "Файлы английских и русских переводов должны быть разными!",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        englishFile = f;
        savePreferences();
        loadProperties();
    }

    private void chooseRussianFile() {
        String f = pickPropertiesFile("Выберите файл русских переводов (messages_ru.properties)");
        if (f == null) {
            return;
        }
        if (Objects.equals(f, englishFile)) {
            JOptionPane.showMessageDialog(this,
                    "Файлы английских и русских переводов должны быть разными!",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        russianFile = f;
        savePreferences();
        loadProperties();
    }

    private void applyChosenFiles(String newEnglishFile, String newRussianFile) {
        if (newEnglishFile.equals(newRussianFile)) {
            JOptionPane.showMessageDialog(this,
                    "Файлы английских и русских переводов должны быть разными!",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }
        englishFile = newEnglishFile;
        russianFile = newRussianFile;
        savePreferences();
        loadProperties();
    }

    private void reloadFiles() {
        stopCellEditing();
        if (!dirtyKeys.isEmpty()) {
            int res = JOptionPane.showConfirmDialog(this,
                    "Несохранённые изменения будут потеряны. Перезагрузить файлы с диска?",
                    "Перезагрузка", JOptionPane.YES_NO_OPTION);
            if (res != JOptionPane.YES_OPTION) {
                return;
            }
        }
        loadProperties();
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("Файл");

        JMenuItem saveItem = new JMenuItem("Сохранить");
        JMenuItem reloadItem = new JMenuItem("Перезагрузить с диска");
        JMenuItem chooseFilesItem = new JMenuItem("Выбрать файлы...");
        JMenuItem chooseEnglishFileItem = new JMenuItem("Выбрать EN файл...");
        JMenuItem chooseRussianFileItem = new JMenuItem("Выбрать RU файл...");
        JMenuItem exportCsvItem = new JMenuItem("Экспорт в CSV...");
        JMenuItem importCsvItem = new JMenuItem("Импорт из CSV...");
        JMenuItem addMissingKeysItem = new JMenuItem("Добавить отсутствующие ключи из RU");
        JCheckBoxMenuItem escapeUnicodeItem = new JCheckBoxMenuItem("Экранировать юникод (\\uXXXX) при сохранении", escapeUnicodePref);
        JMenuItem exitItem = new JMenuItem("Выход");

        saveItem.addActionListener(e -> saveProperties());
        reloadItem.addActionListener(e -> reloadFiles());
        chooseFilesItem.addActionListener(e -> chooseFiles());
        chooseEnglishFileItem.addActionListener(e -> chooseEnglishFile());
        chooseRussianFileItem.addActionListener(e -> chooseRussianFile());
        exportCsvItem.addActionListener(e -> exportCsv());
        importCsvItem.addActionListener(e -> importCsv());
        addMissingKeysItem.addActionListener(e -> addMissingEnglishKeys());
        escapeUnicodeItem.addActionListener(e -> setEscapeUnicode(escapeUnicodeItem.isSelected()));
        exitItem.addActionListener(e -> exitApplication());

        fileMenu.add(saveItem);
        fileMenu.add(reloadItem);
        fileMenu.addSeparator();
        fileMenu.add(chooseFilesItem);
        fileMenu.add(chooseEnglishFileItem);
        fileMenu.add(chooseRussianFileItem);
        fileMenu.addSeparator();
        fileMenu.add(exportCsvItem);
        fileMenu.add(importCsvItem);
        fileMenu.add(addMissingKeysItem);
        fileMenu.addSeparator();
        fileMenu.add(escapeUnicodeItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);

        JMenu editMenu = new JMenu("Правка");
        JMenuItem undoItem = new JMenuItem("Отменить");
        JMenuItem redoItem = new JMenuItem("Повторить");
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> undoTable());
        redoItem.addActionListener(e -> redoTable());
        editMenu.add(undoItem);
        editMenu.add(redoItem);
        menuBar.add(editMenu);

        JMenu aiMenu = new JMenu("ИИ");
        JMenuItem settingsItem = new JMenuItem("Настройки ИИ...");
        JMenuItem checkItem = new JMenuItem("Проверить подключение");
        settingsItem.addActionListener(e -> openAiSettings());
        checkItem.addActionListener(e -> checkAiConnection());
        aiMenu.add(settingsItem);
        aiMenu.add(checkItem);
        menuBar.add(aiMenu);

        setJMenuBar(menuBar);
    }

    private void openAiSettings() {
        AiSettingsDialog dialog = new AiSettingsDialog(this);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            addAiButtonsIfConfigured();
            refreshAiConfig();
        }
    }

    private void checkAiConnection() {
        if (!AiConfig.exists()) {
            JOptionPane.showMessageDialog(this,
                    "Конфигурация ИИ не найдена. Откройте «ИИ → Настройки ИИ…» и укажите сервер.",
                    "Проверка подключения", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final Cursor oldCursor = getCursor();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return new AiTranslationService().checkConnection();
            }

            @Override
            protected void done() {
                setCursor(oldCursor);
                try {
                    JOptionPane.showMessageDialog(TranslationEditor.this, get(),
                            "Проверка подключения", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception e) {
                    JOptionPane.showMessageDialog(TranslationEditor.this,
                            "Сервер недоступен: " + e.getMessage(),
                            "Проверка подключения", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void loadProperties() {
        LOG.info("==================================================================");
        LOG.info("Открытие файлов");
        LOG.info("   EN файл: " + (englishFile == null || englishFile.isEmpty() ? "(не задан)" : englishFile));
        LOG.info("   RU файл: " + (russianFile == null || russianFile.isEmpty() ? "(не задан)" : russianFile));
        long started = System.currentTimeMillis();
        // Очищаем таблицу перед загрузкой
        pushUndoSnapshot();

        englishProps = new OrderedProperties();
        russianProps = new OrderedProperties();
        englishProps.setEscapeUnicode(escapeUnicodePref);
        russianProps.setEscapeUnicode(escapeUnicodePref);

        try {
            // Загрузка английских переводов
            if (englishFile != null && !englishFile.isEmpty() && new File(englishFile).exists()) {
                File f = new File(englishFile);
                try (InputStreamReader reader = new InputStreamReader(
                        Files.newInputStream(Paths.get(englishFile)), StandardCharsets.UTF_8)) {
                    englishProps.load(reader);
                }
                LOG.info("   EN загружен: " + f.getName() + " (байт: " + f.length() + ", ключей: " + englishProps.size() + ")");
            } else {
                LOG.warning("   EN файл не найден или не задан: " + englishFile);
            }

            // Загрузка русских переводов
            if (russianFile != null && !russianFile.isEmpty() && new File(russianFile).exists()) {
                File f = new File(russianFile);
                try (InputStreamReader reader = new InputStreamReader(
                        Files.newInputStream(Paths.get(russianFile)), StandardCharsets.UTF_8)) {
                    russianProps.load(reader);
                }
                LOG.info("   RU загружен: " + f.getName() + " (байт: " + f.length() + ", ключей: " + russianProps.size() + ")");
            } else {
                LOG.warning("   RU файл не найден или не задан: " + russianFile);
            }

            Set<String> enKeys = englishProps.stringPropertyNames();
            int ruOnly = 0;
            for (String key : russianProps.stringPropertyNames()) {
                if (!enKeys.contains(key)) {
                    ruOnly++;
                }
            }

            // Заполнение таблицы
            List<Object[]> rows = new ArrayList<>(enKeys.size());
            int rowNum = 1;
            for (String key : enKeys) {
                String englishValue = englishProps.getProperty(key, "");
                String russianValue = russianProps.getProperty(key, "");
                rows.add(new Object[]{rowNum++, key, englishValue, russianValue});
            }
            setTableData(rows);

            warnAboutDuplicateKeys(englishFile);
            rebuildBaseline();

            // Обновляем отображение для применения цветов
            table.repaint();
            applyFilter();
            updateStatusBar();

            long elapsed = System.currentTimeMillis() - started;
            LOG.info("Загружено строк: " + tableModel.getRowCount()
                    + ", RU-ключей без EN: " + ruOnly
                    + ", время: " + elapsed + " мс");

        } catch (IOException e) {
            LOG.severe("Ошибка при открытии файлов: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Ошибка загрузки файлов: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveProperties() {
        saveProperties(true);
    }

    private boolean saveProperties(boolean showSuccessDialog) {
        LOG.info("==================================================================");
        LOG.info("Сохранение файлов");
        LOG.info("   escapeUnicode=" + escapeUnicodePref + ", dirty/изменено: " + dirtyKeys.size());
        long started = System.currentTimeMillis();
        stopCellEditing();
        try {
            if (englishFile != null && !englishFile.isEmpty() && !backupFile(englishFile)) {
                int res = JOptionPane.showConfirmDialog(this,
                        "Не удалось создать резервную копию: " + englishFile +
                                "\nПродолжить сохранение без бэкапа?",
                        "Сохранение", JOptionPane.YES_NO_OPTION);
                if (res != JOptionPane.YES_OPTION) {
                    return false;
                }
            }
            if (russianFile != null && !russianFile.isEmpty() && !backupFile(russianFile)) {
                int res = JOptionPane.showConfirmDialog(this,
                        "Не удалось создать резервную копию: " + russianFile +
                                "\nПродолжить сохранение без бэкапа?",
                        "Сохранение", JOptionPane.YES_NO_OPTION);
                if (res != JOptionPane.YES_OPTION) {
                    return false;
                }
            }

            Set<String> tableKeys = new HashSet<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                Object k = tableModel.getValueAt(i, 1);
                if (k != null && !String.valueOf(k).trim().isEmpty()) {
                    tableKeys.add(String.valueOf(k));
                }
            }
            englishProps.keySet().removeIf(key -> !tableKeys.contains(String.valueOf(key)));
            russianProps.keySet().removeIf(key -> !tableKeys.contains(String.valueOf(key)));

            // Заполнение свойств из таблицы
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String key = (String) tableModel.getValueAt(i, 1);
                String englishValue = (String) tableModel.getValueAt(i, 2);
                String russianValue = (String) tableModel.getValueAt(i, 3);
                if (key != null && !key.trim().isEmpty()) {
                    englishProps.setProperty(key, englishValue != null ? englishValue : "");
                    russianProps.setProperty(key, russianValue != null ? russianValue : "");
                }
            }

            // Сохранение английских переводов
            if (englishFile != null && !englishFile.isEmpty()) {
                englishProps.setEscapeUnicode(escapeUnicodePref);
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        Files.newOutputStream(Paths.get(englishFile)), StandardCharsets.UTF_8)) {
                    englishProps.store(writer, null);
                }
                File f = new File(englishFile);
                LOG.info("   EN сохранён: " + f.getName() + " (байт: " + f.length() + ", ключей: " + englishProps.size() + ")");
            }

            // Сохранение русских переводов
            if (russianFile != null && !russianFile.isEmpty()) {
                OrderedProperties finalRussianProps = new OrderedProperties();
                finalRussianProps.setEscapeUnicode(escapeUnicodePref);

                englishProps.forEach(
                        (k,v) -> {
                            finalRussianProps.put(k, russianProps.getProperty((String) k));
                        }
                );

                try (OutputStreamWriter writer = new OutputStreamWriter(
                        Files.newOutputStream(Paths.get(russianFile)), StandardCharsets.UTF_8)) {
                    finalRussianProps.store(writer, null);
                }
                File f = new File(russianFile);
                LOG.info("   RU сохранён: " + f.getName() + " (байт: " + f.length() + ", ключей: " + finalRussianProps.size() + ")");
                russianProps = finalRussianProps;
            }

            // Обновляем отображение для применения цветов после сохранения
            table.repaint();

            lastSaveTime = System.currentTimeMillis();
            rebuildBaseline();
            long elapsed = System.currentTimeMillis() - started;
            LOG.info("Файлы успешно сохранены (время: " + elapsed + " мс). EN: " + englishFile + ", RU: " + russianFile);

            if (showSuccessDialog) {
                JOptionPane.showMessageDialog(this, "Файлы успешно сохранены!",
                        "Сохранение", JOptionPane.INFORMATION_MESSAGE);
            }

            return true;

        } catch (IOException e) {
            LOG.severe("Failed to save files: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Ошибка сохранения файлов: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void stopCellEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private void exitApplication() {
        stopCellEditing();
        if (!dirtyKeys.isEmpty()) {
            int res = JOptionPane.showConfirmDialog(this,
                    "Есть несохранённые изменения. Сохранить их перед выходом?",
                    "Выход", JOptionPane.YES_NO_CANCEL_OPTION);
            if (res == JOptionPane.CANCEL_OPTION) {
                return;
            }
            if (res == JOptionPane.YES_OPTION && !saveProperties(false)) {
                return;
            }
        }
        LOG.info("Выход из приложения. Сохранено: " + lastSaveTime);
        setVisible(false);
        dispose();
        System.exit(0);
    }

    private void translateSelectedRow() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "Выберите строку для перевода.",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
            return;
        }
        stopCellEditing();

        List<Integer> valid = new ArrayList<>();
        for (int viewRow : rows) {
            int row = table.convertRowIndexToModel(viewRow);
            String key = (String) tableModel.getValueAt(row, 1);
            String eng = (String) tableModel.getValueAt(row, 2);
            if (key != null && !key.trim().isEmpty() && eng != null && !eng.trim().isEmpty()) {
                valid.add(row);
            }
        }

        if (valid.isEmpty()) {
            JOptionPane.showMessageDialog(this, "В выбранных строках нет английского текста для перевода.",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int[] mainRows = new int[valid.size()];
        for (int i = 0; i < valid.size(); i++) {
            mainRows[i] = valid.get(i);
        }
        runBatchTranslation("Перевод (ИИ)", mainRows, false);
    }

    private void verifySelectedRows() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "Выберите строку для проверки перевода.",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
            return;
        }
        stopCellEditing();

        List<Integer> valid = new ArrayList<>();
        for (int viewRow : rows) {
            int row = table.convertRowIndexToModel(viewRow);
            String key = (String) tableModel.getValueAt(row, 1);
            String eng = (String) tableModel.getValueAt(row, 2);
            String ru = (String) tableModel.getValueAt(row, 3);
            if (key != null && !key.trim().isEmpty() && eng != null && !eng.trim().isEmpty()
                    && ru != null && !ru.trim().isEmpty()) {
                valid.add(row);
            }
        }

        if (valid.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "В выбранных строках нет заполненных русских переводов для проверки.",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int[] mainRows = new int[valid.size()];
        for (int i = 0; i < valid.size(); i++) {
            mainRows[i] = valid.get(i);
        }
        runBatchTranslation("Проверка перевода (ИИ)", mainRows, true);
    }

    private void batchTranslate() {
        stopCellEditing();

        List<Integer> rowIndexes = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        List<String> engs = new ArrayList<>();
        List<String> rus = new ArrayList<>();
        List<Boolean> selectedFlags = new ArrayList<>();

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            String key = (String) tableModel.getValueAt(i, 1);
            String eng = (String) tableModel.getValueAt(i, 2);
            String ru = (String) tableModel.getValueAt(i, 3);
            if (key == null || key.trim().isEmpty() || eng == null || eng.trim().isEmpty()) {
                continue;
            }
            rowIndexes.add(i);
            keys.add(key);
            engs.add(eng);
            rus.add(ru != null ? ru : "");
            selectedFlags.add(ru == null || ru.trim().isEmpty());
        }

        if (keys.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Нет строк с английским текстом для перевода.",
                    "Пакетный перевод", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        boolean[] selected = new boolean[selectedFlags.size()];
        for (int i = 0; i < selectedFlags.size(); i++) {
            selected[i] = selectedFlags.get(i);
        }

        BatchTranslateDialog dialog = new BatchTranslateDialog(this, keys, engs, rus, selected);
        dialog.setVisible(true);
        if (!dialog.isConfirmed()) {
            return;
        }

        int[] selectedIndices = dialog.getSelectedIndices();
        if (selectedIndices.length == 0) {
            return;
        }

        int[] mainRows = new int[selectedIndices.length];
        for (int i = 0; i < selectedIndices.length; i++) {
            mainRows[i] = rowIndexes.get(selectedIndices[i]);
        }

        runBatchTranslation("Пакетный перевод (ИИ)", mainRows, dialog.isVerifyExisting());
    }

    private void showProgressDialog(String title, int total, Runnable cancelAction) {
        progressDialog = new JDialog(this, title, true);
        progressBar = new JProgressBar(0, total);
        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        progressLabel = new JLabel("Подготовка...", SwingConstants.CENTER);
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(progressLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);
        if (cancelAction != null) {
            JButton cancelButton = new JButton("Отмена");
            cancelButton.addActionListener(e -> {
                cancelButton.setEnabled(false);
                cancelAction.run();
            });
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            bottom.add(cancelButton);
            panel.add(bottom, BorderLayout.SOUTH);
        }
        progressDialog.add(panel);
        progressDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progressDialog.pack();
        progressDialog.setLocationRelativeTo(this);
    }

    private void runBatchTranslation(final String title, final int[] mainRows, final boolean verifyExisting) {
        if (mainRows == null || mainRows.length == 0) {
            return;
        }
        final int total = mainRows.length;
        final List<String> errorMessages = Collections.synchronizedList(new ArrayList<>());
        final int threads = aiService.getThreads();
        final long tokensBefore = aiService.getTotalTokens();

        SwingWorker<List<Object[]>, Void> worker = new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() {
                List<Object[]> results = new ArrayList<>();
                if (threads <= 1) {
                    int done = 0;
                    for (int row : mainRows) {
                        if (isCancelled()) {
                            break;
                        }
                        Object[] r = translateRow(row, verifyExisting, errorMessages);
                        if (r != null) {
                            results.add(r);
                        }
                        done++;
                        setProgress(done);
                    }
                    return results;
                }

                ExecutorService executor = Executors.newFixedThreadPool(threads);
                ExecutorCompletionService<Object[]> completion = new ExecutorCompletionService<>(executor);
                int submitted = 0;
                for (int row : mainRows) {
                    if (isCancelled()) {
                        break;
                    }
                    final int currentRow = row;
                    completion.submit(() -> translateRow(currentRow, verifyExisting, errorMessages));
                    submitted++;
                }
                int done = 0;
                while (done < submitted) {
                    if (isCancelled()) {
                        break;
                    }
                    try {
                        Future<Object[]> future = completion.poll(200, TimeUnit.MILLISECONDS);
                        if (future == null) {
                            continue;
                        }
                        try {
                            Object[] r = future.get();
                            if (r != null) {
                                results.add(r);
                            }
                        } catch (ExecutionException ignored) {
                        }
                        done++;
                        setProgress(done);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                executor.shutdownNow();
                results.sort(Comparator.comparingInt(r -> (Integer) r[0]));
                return results;
            }
        };

        showProgressDialog(title, total, () -> worker.cancel(true));

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int value = (Integer) evt.getNewValue();
                progressBar.setValue(value);
                progressLabel.setText("Обработано: " + value + " / " + total);
            } else if (SwingWorker.StateValue.DONE == evt.getNewValue()) {
                progressDialog.dispose();
                if (worker.isCancelled()) {
                    JOptionPane.showMessageDialog(TranslationEditor.this,
                            "Операция отменена.", title, JOptionPane.INFORMATION_MESSAGE);
                    table.repaint();
                    return;
                }
                List<Object[]> results;
                try {
                    results = worker.get();
                } catch (Exception e) {
                    results = new ArrayList<>();
                    errorMessages.add(e.getMessage());
                }
                applyReviewedChanges(results);
                long tokensUsed = aiService.getTotalTokens() - tokensBefore;
                String tokenInfo = tokensUsed > 0 ? "\nИспользовано токенов: " + tokensUsed : "";
                if (errorMessages.isEmpty()) {
                    JOptionPane.showMessageDialog(TranslationEditor.this,
                            "Обработка завершена." + tokenInfo, title, JOptionPane.INFORMATION_MESSAGE);
                } else {
                    StringBuilder sb = new StringBuilder("Не удалось обработать:\n");
                    for (String msg : errorMessages) {
                        sb.append(msg).append('\n');
                    }
                    if (!tokenInfo.isEmpty()) {
                        sb.append('\n').append(tokenInfo.substring(1));
                    }
                    JOptionPane.showMessageDialog(TranslationEditor.this, sb.toString(),
                            "Завершено с ошибками", JOptionPane.WARNING_MESSAGE);
                }
                table.repaint();
            }
        });

        worker.execute();
        progressDialog.setVisible(true);
    }

    private Object[] translateRow(int row, boolean verifyExisting, List<String> errorMessages) {
        String key = (String) tableModel.getValueAt(row, 1);
        String eng = (String) tableModel.getValueAt(row, 2);
        String oldRu = (String) tableModel.getValueAt(row, 3);
        oldRu = (oldRu == null) ? "" : oldRu;
        try {
            String newRu;
            boolean hasRussian = !oldRu.trim().isEmpty();
            if (verifyExisting && hasRussian) {
                newRu = aiService.verifyAndCorrect(eng, oldRu);
            } else {
                newRu = aiService.translate(eng);
            }
            return new Object[]{row, key, eng, oldRu, newRu};
        } catch (Exception ex) {
            errorMessages.add("- " + key + ": " + ex.getMessage());
            return null;
        }
    }

    private void applyReviewedChanges(List<Object[]> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        TranslationReviewDialog dialog = new TranslationReviewDialog(this, results);
        dialog.setVisible(true);
        if (!dialog.isConfirmed()) {
            return;
        }
        int[] indices = dialog.getAppliedIndices();
        for (int idx : indices) {
            Object[] r = results.get(idx);
            int row = (Integer) r[0];
            String newRu = (String) r[4];
            tableModel.setValueAt(newRu, row, 3);
        }
    }

    private void addNewRow() {
        // Диалог для ввода ключа
        String key = JOptionPane.showInputDialog(this, "Введите ключ:", "Добавление новой строки", JOptionPane.QUESTION_MESSAGE);

        if (key != null && !key.trim().isEmpty()) {
            // Проверяем, существует ли уже такой ключ
            boolean keyExists = false;
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (key.equals(tableModel.getValueAt(i, 1))) {
                    keyExists = true;
                    break;
                }
            }

            if (keyExists) {
                JOptionPane.showMessageDialog(this, "Ключ '" + key + "' уже существует!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                return;
            }

            int newRowNumber = tableModel.getRowCount() + 1;
            pushUndoSnapshot();
            tableModel.addRow(new Object[]{newRowNumber, key, "", ""});
            int lastRow = tableModel.getRowCount() - 1;
            int viewRow = table.convertRowIndexToView(lastRow);
            if (viewRow >= 0) {
                table.setRowSelectionInterval(viewRow, viewRow);
                table.editCellAt(viewRow, 2); // Начинаем редактирование с английского перевода
            }
            table.requestFocus();

            // Обновляем все номера строк
            updateRowNumbers();
            recomputeDirty();
        }

        // Обновляем отображение для применения цветов
        table.repaint();
    }

    private void deleteSelectedRow() {
        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            int result = JOptionPane.showConfirmDialog(this,
                    "Вы уверены, что хотите удалить выбранную строку?",
                    "Подтверждение удаления",
                    JOptionPane.YES_NO_OPTION);
            if (result == JOptionPane.YES_OPTION) {
                int modelRow = table.convertRowIndexToModel(selectedRow);
                pushUndoSnapshot();
                tableModel.removeRow(modelRow);
                // Обновляем номера строк после удаления
                updateRowNumbers();
                recomputeDirty();
                // Обновляем отображение для применения цветов
                table.repaint();
            }
        } else {
            JOptionPane.showMessageDialog(this, "Выберите строку для удаления",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
        }
    }

    private List<Object[]> snapshotTable() {
        List<Object[]> snap = new ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            snap.add(new Object[]{
                    String.valueOf(tableModel.getValueAt(i, 1)),
                    String.valueOf(tableModel.getValueAt(i, 2)),
                    String.valueOf(tableModel.getValueAt(i, 3))
            });
        }
        return snap;
    }

    private void pushUndoSnapshot() {
        undoStack.addLast(snapshotTable());
        if (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeFirst();
        }
        redoStack.clear();
    }

    private void restoreTable(List<Object[]> snap) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        suppressUndo = true;
        try {
            List<Object[]> rows = new ArrayList<>(snap.size());
            for (Object[] e : snap) {
                rows.add(new Object[]{0, e[0], e[1], e[2]});
            }
            setTableData(rows);
        } finally {
            suppressUndo = false;
        }
        updateRowNumbers();
        applyFilter();
        recomputeDirty();
        table.repaint();
    }

    private void undoTable() {
        if (undoStack.isEmpty()) {
            return;
        }
        List<Object[]> current = snapshotTable();
        restoreTable(undoStack.removeLast());
        redoStack.addLast(current);
        if (redoStack.size() > UNDO_LIMIT) {
            redoStack.removeFirst();
        }
        updateStatusBar();
    }

    private void redoTable() {
        if (redoStack.isEmpty()) {
            return;
        }
        List<Object[]> current = snapshotTable();
        restoreTable(redoStack.removeLast());
        undoStack.addLast(current);
        if (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeFirst();
        }
        updateStatusBar();
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Экспорт в CSV");
        fc.setFileFilter(new FileNameExtensionFilter("CSV файлы (*.csv)", "csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        if (f == null) {
            return;
        }
        if (!f.getName().toLowerCase().endsWith(".csv")) {
            f = new File(f.getParentFile(), f.getName() + ".csv");
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"key", "english", "russian"});
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            rows.add(new String[]{
                    (String) tableModel.getValueAt(i, 1),
                    (String) tableModel.getValueAt(i, 2),
                    (String) tableModel.getValueAt(i, 3)
            });
        }
        try {
            PropIo.writeCsv(f.toPath(), rows);
            LOG.info("CSV exported to: " + f.getAbsolutePath());
            JOptionPane.showMessageDialog(this, "CSV экспортирован: " + f.getName(),
                    "Экспорт", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            LOG.severe("CSV export failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Ошибка экспорта: " + e.getMessage(),
                    "Экспорт", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void importCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Импорт из CSV");
        fc.setFileFilter(new FileNameExtensionFilter("CSV файлы (*.csv)", "csv"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        if (f == null) {
            return;
        }
        List<String[]> rows;
        try {
            rows = PropIo.readCsv(f.toPath());
        } catch (IOException e) {
            LOG.severe("CSV import failed: " + e.getMessage());
            JOptionPane.showMessageDialog(this, "Ошибка импорта: " + e.getMessage(),
                    "Импорт", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Файл пуст.", "Импорт", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (rows.get(0).length >= 3
                && "key".equalsIgnoreCase(rows.get(0)[0] == null ? "" : rows.get(0)[0].trim())) {
            rows.remove(0);
        }

        Map<String, Integer> byKey = new HashMap<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            byKey.put(String.valueOf(tableModel.getValueAt(i, 1)), i);
        }

        int added = 0;
        int updated = 0;
        pushUndoSnapshot();
        suppressUndo = true;
        try {
            for (String[] r : rows) {
                if (r.length < 3) {
                    continue;
                }
                String key = r[0] == null ? "" : r[0].trim();
                if (key.isEmpty()) {
                    continue;
                }
                String en = r[1] == null ? "" : r[1];
                String ru = r[2] == null ? "" : r[2];
                Integer idx = byKey.get(key);
                if (idx != null) {
                    tableModel.setValueAt(en, idx, 2);
                    tableModel.setValueAt(ru, idx, 3);
                    updated++;
                } else {
                    tableModel.addRow(new Object[]{0, key, en, ru});
                    byKey.put(key, tableModel.getRowCount() - 1);
                    added++;
                }
            }
        } finally {
            suppressUndo = false;
        }
        updateRowNumbers();
        applyFilter();
        recomputeDirty();
        table.repaint();
        LOG.info("CSV imported from: " + f.getAbsolutePath() + " (added " + added + ", updated " + updated + ")");
        JOptionPane.showMessageDialog(this,
                "Импорт завершён: добавлено " + added + ", обновлено " + updated + ".",
                "Импорт", JOptionPane.INFORMATION_MESSAGE);
    }

    private void addMissingEnglishKeys() {
        int added = 0;
        pushUndoSnapshot();
        suppressUndo = true;
        try {
            Set<String> existing = new HashSet<>();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                existing.add(String.valueOf(tableModel.getValueAt(i, 1)));
            }
            for (String key : russianProps.stringPropertyNames()) {
                if (!existing.contains(key)) {
                    String ru = russianProps.getProperty(key, "");
                    tableModel.addRow(new Object[]{0, key, "", ru});
                    added++;
                }
            }
        } finally {
            suppressUndo = false;
        }
        if (added > 0) {
            updateRowNumbers();
            applyFilter();
            recomputeDirty();
            table.repaint();
        }
        LOG.info("Added " + added + " missing keys from RU file.");
        JOptionPane.showMessageDialog(this,
                "Добавлено отсутствующих ключей: " + added + ".",
                "Добавление ключей", JOptionPane.INFORMATION_MESSAGE);
    }

    // Кастомный рендерер для номеров строк
    private static class RowNumberRenderer extends JLabel implements TableCellRenderer {
        public RowNumberRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(new Color(238, 240, 245));
                setForeground(UIManager.getColor("Table.foreground"));
            }
            setText((value == null) ? "" : value.toString());
            return this;
        }
    }

    // Кастомный рендерер для ключей
    private class KeyCellRenderer extends JTextArea implements TableCellRenderer {
        public KeyCellRenderer() {
            setLineWrap(false);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (isSelected) {
                setForeground(table.getSelectionForeground());
                setBackground(table.getSelectionBackground());
            } else {
                setForeground(table.getForeground());
                setBackground(table.getBackground());
            }
            setText((value == null) ? "" : value.toString());
            return this;
        }
    }

    // Кастомный редактор для ключей
    private static class KeyCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextField textField;

        public KeyCellEditor() {
            textField = new JTextField();
            textField.setBorder(BorderFactory.createEmptyBorder());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            textField.setText((value == null) ? "" : value.toString());
            return textField;
        }

        @Override
        public Object getCellEditorValue() {
            return textField.getText();
        }
    }

    // Кастомный рендерер для многострочного текста
    private class MultiLineCellRenderer extends JTextArea implements TableCellRenderer {
        public MultiLineCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            if (isSelected) {
                setForeground(table.getSelectionForeground());
                setBackground(table.getSelectionBackground());
            } else {
                setForeground(table.getForeground());
                setBackground(table.getBackground());
            }
            setText((value == null) ? "" : value.toString());
            return this;
        }
    }

    // Кастомный редактор для многострочного текста
    private static class MultiLineCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JTextArea textArea;

        public MultiLineCellEditor() {
            textArea = new JTextArea();
            textArea.setLineWrap(true);
            textArea.setWrapStyleWord(true);
            textArea.setBorder(BorderFactory.createEmptyBorder());

            // Сохранение при потере фокуса
            textArea.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    fireEditingStopped();
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            textArea.setText((value == null) ? "" : value.toString());
            return textArea;
        }

        @Override
        public Object getCellEditorValue() {
            return textArea.getText();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(new com.formdev.flatlaf.FlatLightLaf());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new TranslationEditor().setVisible(true);
        });
    }
}
