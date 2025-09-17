package svkreml.prop_loc_edit;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.prefs.Preferences;

public class TranslationEditor extends JFrame {
    private JTable table;
    private DefaultTableModel tableModel;
    private OrderedProperties englishProps;
    private OrderedProperties russianProps;
    private OrderedProperties originalEnglishProps;
    private OrderedProperties originalRussianProps;
    private String englishFile;
    private String russianFile;
    private Preferences prefs;
    private static final String PREF_ENGLISH_FILE = "englishFile";
    private static final String PREF_RUSSIAN_FILE = "russianFile";

    public TranslationEditor() {
        setTitle("Редактор переводов");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1400, 1000);
        setLocationRelativeTo(null);

        // Инициализация настроек
        prefs = Preferences.userNodeForPackage(TranslationEditor.class);
        loadPreferences();

        initializeComponents();

        // Если файлы не заданы, открываем диалог выбора
        if (englishFile == null || russianFile == null ||
            englishFile.isEmpty() || russianFile.isEmpty()) {
            chooseFiles();
        }

        loadProperties();
        setupMenuBar();
    }

    private void loadPreferences() {
        englishFile = prefs.get(PREF_ENGLISH_FILE, "");
        russianFile = prefs.get(PREF_RUSSIAN_FILE, "");
    }

    private void savePreferences() {
        prefs.put(PREF_ENGLISH_FILE, englishFile);
        prefs.put(PREF_RUSSIAN_FILE, russianFile);
    }

    private void initializeComponents() {
        // Создание таблицы
        tableModel = new DefaultTableModel(new Object[]{"Ключ", "Английский", "Русский"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true; // Все колонки редактируемы
            }
        };

        table = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component c = super.prepareRenderer(renderer, row, column);

                // Получаем ключ для текущей строки
                String key = (String) getValueAt(row, 0);

                // Проверяем наличие переводов
                boolean hasEnglish = englishProps != null && englishProps.containsKey(key) &&
                                     englishProps.getProperty(key) != null &&
                                     !englishProps.getProperty(key).trim().isEmpty();
                boolean hasRussian = russianProps != null && russianProps.containsKey(key) &&
                                     russianProps.getProperty(key) != null &&
                                     !russianProps.getProperty(key).trim().isEmpty();

                // Проверяем, были ли изменения
                boolean isModified = isRowModified(key, row);

                // Раскрашивание строк
                if (isModified) {
                    c.setBackground(Color.LIGHT_GRAY); // Измененная строка
                } else if (!hasEnglish && !hasRussian) {
                    c.setBackground(Color.PINK); // Нет обоих переводов
                } else if (!hasEnglish) {
                    c.setBackground(Color.PINK); // Нет английского перевода
                } else if (!hasRussian) {
                    c.setBackground(Color.YELLOW); // Нет русского перевода
                } else {
                    c.setBackground(Color.WHITE); // Оба перевода есть и строка не изменена
                }

                return c;
            }
        };

        table.setRowHeight(60);
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(2).setPreferredWidth(300);

        // Установка кастомного редактора для многострочного текста
        table.getColumnModel().getColumn(0).setCellEditor(new KeyCellEditor());
        table.getColumnModel().getColumn(1).setCellEditor(new MultiLineCellEditor());
        table.getColumnModel().getColumn(2).setCellEditor(new MultiLineCellEditor());

        // Установка кастомного рендерера для отображения многострочного текста
        table.getColumnModel().getColumn(0).setCellRenderer(new KeyCellRenderer());
        table.getColumnModel().getColumn(1).setCellRenderer(new MultiLineCellRenderer());
        table.getColumnModel().getColumn(2).setCellRenderer(new MultiLineCellRenderer());

        // Добавляем слушатель изменений в таблице
        tableModel.addTableModelListener(e -> {
            table.repaint();
        });

        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        // Панель инструментов
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addButton = new JButton("Добавить");
        JButton deleteButton = new JButton("Удалить");
        JButton saveButton = new JButton("Сохранить");
        JButton chooseFilesButton = new JButton("Выбрать файлы");

        addButton.addActionListener(e -> addNewRow());
        deleteButton.addActionListener(e -> deleteSelectedRow());
        saveButton.addActionListener(e -> saveProperties());
        chooseFilesButton.addActionListener(e -> chooseFiles());

        toolbar.add(addButton);
        toolbar.add(deleteButton);
        toolbar.add(saveButton);
        toolbar.add(chooseFilesButton);
        add(toolbar, BorderLayout.NORTH);
    }

    private void chooseFiles() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Выберите файл английских переводов (messages_en.properties)");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            String newEnglishFile = fileChooser.getSelectedFile().getAbsolutePath();

            fileChooser.setDialogTitle("Выберите файл русских переводов (messages_ru.properties)");
            result = fileChooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                String newRussianFile = fileChooser.getSelectedFile().getAbsolutePath();

                // Проверяем, что файлы разные
                if (newEnglishFile.equals(newRussianFile)) {
                    JOptionPane.showMessageDialog(this,
                            "Файлы английских и русских переводов должны быть разными!",
                            "Ошибка", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                englishFile = newEnglishFile;
                russianFile = newRussianFile;

                // Сохраняем настройки
                savePreferences();

                // Перезагружаем свойства
                loadProperties();
            }
        }
    }

    private boolean isRowModified(String key, int row) {
        if (originalEnglishProps == null || originalRussianProps == null) {
            return false;
        }

        String currentEnglish = (String) tableModel.getValueAt(row, 1);
        String currentRussian = (String) tableModel.getValueAt(row, 2);

        String originalEnglish = originalEnglishProps.getProperty(key, "");
        String originalRussian = originalRussianProps.getProperty(key, "");

        return !Objects.equals(currentEnglish, originalEnglish) ||
               !Objects.equals(currentRussian, originalRussian);
    }

    private void setupMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu fileMenu = new JMenu("Файл");

        JMenuItem saveItem = new JMenuItem("Сохранить");
        JMenuItem chooseFilesItem = new JMenuItem("Выбрать файлы");
        JMenuItem exitItem = new JMenuItem("Выход");

        saveItem.addActionListener(e -> saveProperties());
        chooseFilesItem.addActionListener(e -> chooseFiles());
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(saveItem);
        fileMenu.add(chooseFilesItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        menuBar.add(fileMenu);
        setJMenuBar(menuBar);
    }

    private void loadProperties() {
        // Очищаем таблицу перед загрузкой
        tableModel.setRowCount(0);

        englishProps = new OrderedProperties();
        russianProps = new OrderedProperties();
        originalEnglishProps = new OrderedProperties();
        originalRussianProps = new OrderedProperties();

        try {
            // Загрузка английских переводов
            if (englishFile != null && !englishFile.isEmpty() && new File(englishFile).exists()) {
                try (InputStreamReader reader = new InputStreamReader(
                        new FileInputStream(englishFile), "UTF-8")) {
                    englishProps.load(reader);
                    // Создаем копию для отслеживания изменений
                    originalEnglishProps.load(new InputStreamReader(
                            new FileInputStream(englishFile), "UTF-8"));
                }
            }

            // Загрузка русских переводов
            if (russianFile != null && !russianFile.isEmpty() && new File(russianFile).exists()) {
                try (InputStreamReader reader = new InputStreamReader(
                        new FileInputStream(russianFile), "UTF-8")) {
                    russianProps.load(reader);
                    // Создаем копию для отслеживания изменений
                    originalRussianProps.load(new InputStreamReader(
                            new FileInputStream(russianFile), "UTF-8"));
                }
            }

            // Создание объединенного списка ключей
            Set<String> allKeys = new TreeSet<>();
            allKeys.addAll(englishProps.stringPropertyNames());
            allKeys.addAll(russianProps.stringPropertyNames());

            // Заполнение таблицы
            for (String key : allKeys) {
                String englishValue = englishProps.getProperty(key, "");
                String russianValue = russianProps.getProperty(key, "");
                tableModel.addRow(new Object[]{key, englishValue, russianValue});
            }

            // Обновляем отображение для применения цветов
            table.repaint();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Ошибка загрузки файлов: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void saveProperties() {
        try {
            // Очистка существующих свойств
            englishProps.clear();
            russianProps.clear();

            // Заполнение свойств из таблицы
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                String key = (String) tableModel.getValueAt(i, 0);
                String englishValue = (String) tableModel.getValueAt(i, 1);
                String russianValue = (String) tableModel.getValueAt(i, 2);

                if (key != null && !key.trim().isEmpty()) {
                    englishProps.setProperty(key, englishValue != null ? englishValue : "");
                    russianProps.setProperty(key, russianValue != null ? russianValue : "");
                }
            }

            // Сохранение английских переводов
            if (englishFile != null && !englishFile.isEmpty()) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(englishFile), "UTF-8")) {
                    englishProps.store(writer, "English translations");
                }
            }

            // Сохранение русских переводов
            if (russianFile != null && !russianFile.isEmpty()) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        new FileOutputStream(russianFile), "UTF-8")) {
                    russianProps.store(writer, "Russian translations");
                }
            }

            // Обновляем оригинальные свойства
            originalEnglishProps.clear();
            originalRussianProps.clear();
            originalEnglishProps.putAll(englishProps);
            originalRussianProps.putAll(russianProps);

            // Обновляем отображение для применения цветов после сохранения
            table.repaint();

            JOptionPane.showMessageDialog(this, "Файлы успешно сохранены!",
                    "Сохранение", JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Ошибка сохранения файлов: " + e.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void addNewRow() {
        // Диалог для ввода ключа
        String key = JOptionPane.showInputDialog(this, "Введите ключ:", "Добавление новой строки", JOptionPane.QUESTION_MESSAGE);

        if (key != null && !key.trim().isEmpty()) {
            // Проверяем, существует ли уже такой ключ
            boolean keyExists = false;
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                if (key.equals(tableModel.getValueAt(i, 0))) {
                    keyExists = true;
                    break;
                }
            }

            if (keyExists) {
                JOptionPane.showMessageDialog(this, "Ключ '" + key + "' уже существует!", "Ошибка", JOptionPane.ERROR_MESSAGE);
                return;
            }

            tableModel.addRow(new Object[]{key, "", ""});
            int lastRow = tableModel.getRowCount() - 1;
            table.setRowSelectionInterval(lastRow, lastRow);
            table.editCellAt(lastRow, 1); // Начинаем редактирование с английского перевода
            table.requestFocus();
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
                tableModel.removeRow(selectedRow);
                // Обновляем отображение для применения цветов
                table.repaint();
            }
        } else {
            JOptionPane.showMessageDialog(this, "Выберите строку для удаления",
                    "Предупреждение", JOptionPane.WARNING_MESSAGE);
        }
    }

    // Кастомный рендерер для ключей
    private static class KeyCellRenderer extends JTextArea implements TableCellRenderer {
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
        private JTextField textField;

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
    private static class MultiLineCellRenderer extends JTextArea implements TableCellRenderer {
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
        private JTextArea textArea;

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

            // Сохранение при нажатии Enter
/*            textArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "save");
            textArea.getActionMap().put("save", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    fireEditingStopped();
                }
            });*/
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
                UIManager.setLookAndFeel(UIManager.getLookAndFeel());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new TranslationEditor().setVisible(true);
        });
    }
}