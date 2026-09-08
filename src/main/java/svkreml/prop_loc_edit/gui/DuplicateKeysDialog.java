package svkreml.prop_loc_edit.gui;

import svkreml.prop_loc_edit.PropIo;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DuplicateKeysDialog extends JDialog {

    private final Path filePath;
    private final DefaultTableModel tableModel;
    private final List<PropIo.Occurrence> occurrences;
    private boolean applied;

    public DuplicateKeysDialog(Window owner, String path) {
        super(owner, "Дубликаты ключей — " + new File(path).getName(), ModalityType.APPLICATION_MODAL);
        filePath = Paths.get(path);
        Map<String, List<PropIo.Occurrence>> duplicates = PropIo.findDuplicateOccurrences(path);

        occurrences = new ArrayList<>();
        duplicates.values().forEach(occurrences::addAll);
        occurrences.sort(Comparator.comparingInt(o -> o.line));

        tableModel = new DefaultTableModel(new Object[]{"Строка", "Ключ", "Значение"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column > 0;
            }
        };
        for (PropIo.Occurrence o : occurrences) {
            String lineText = o.lastLine > o.line ? o.line + "-" + o.lastLine : String.valueOf(o.line);
            tableModel.addRow(new Object[]{lineText, o.key, o.value});
        }

        JTable table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setMaxWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(240);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, Math.min(400, 30 * tableModel.getRowCount() + 40)));

        JButton autoFixButton = new JButton("Автофикс (переименовать дубликаты)");
        autoFixButton.addActionListener(e -> autoFixDuplicates(table));

        JButton applyButton = new JButton("Применить исправления");
        applyButton.addActionListener(e -> applyFixes(table));

        JButton cancelButton = new JButton("Отмена");
        cancelButton.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(autoFixButton);
        buttons.add(applyButton);
        buttons.add(cancelButton);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        root.add(scroll, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        setContentPane(root);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setModal(true);

        getRootPane().setDefaultButton(applyButton);
        getRootPane().registerKeyboardAction(
                e -> applyFixes(table),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                dispose();
            }
        });

        pack();
        setLocationRelativeTo(owner);
    }

    private void autoFixDuplicates(JTable table) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String key = (String) tableModel.getValueAt(row, 1);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (List<Integer> rowsIndexes : groups.values()) {
            for (int i = 1; i < rowsIndexes.size(); i++) {
                int row = rowsIndexes.get(i);
                String key = (String) tableModel.getValueAt(row, 1);
                tableModel.setValueAt(key + "_" + (i + 1), row, 1);
            }
        }
        table.repaint();
    }

    private void applyFixes(JTable table) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }

        List<PropIo.LineEdit> edits = new ArrayList<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            PropIo.Occurrence original = occurrences.get(row);
            String keyText = (String) tableModel.getValueAt(row, 1);
            String valueText = (String) tableModel.getValueAt(row, 2);
            keyText = keyText == null ? "" : keyText.trim();
            valueText = valueText == null ? "" : valueText.trim();
            if (!keyText.equals(original.key) || !valueText.equals(original.value)) {
                edits.add(new PropIo.LineEdit(original.line, original.lastLine,
                        List.of(PropIo.formatPropertyLine(keyText, valueText))));
            }
        }
        if (edits.isEmpty()) {
            dispose();
            return;
        }

        try {
            List<String> editedLines = PropIo.applyEditsTo(
                    Files.readAllLines(filePath, StandardCharsets.UTF_8), edits);
            if (PropIo.countLogicalKeys(editedLines) > 0) {
                JOptionPane.showMessageDialog(this,
                        "Остались дубликаты ключей. Исправьте их перед применением.",
                        "Дубликаты остались", JOptionPane.ERROR_MESSAGE);
                return;
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Ошибка чтения файла: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Path backup = Paths.get(filePath.toString() + ".bak");
            Files.copy(filePath, backup, StandardCopyOption.REPLACE_EXISTING);
            PropIo.applyEdits(filePath, edits);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Не удалось сохранить файл: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        applied = true;
        dispose();
    }

    public boolean isApplied() {
        return applied;
    }
}