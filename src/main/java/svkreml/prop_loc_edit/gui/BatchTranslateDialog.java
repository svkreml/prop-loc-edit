package svkreml.prop_loc_edit.gui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BatchTranslateDialog extends JDialog {

    private final List<String> keys;
    private final List<String> engTexts;
    private final List<String> ruTexts;
    private boolean[] selected;
    private boolean confirmed;
    private boolean verifyExisting;
    private JTable previewTable;

    public BatchTranslateDialog(Window owner,
                                List<String> keys,
                                List<String> engTexts,
                                List<String> ruTexts,
                                boolean[] initialSelected) {
        super(owner, "Пакетный перевод с ИИ — предпросмотр", ModalityType.APPLICATION_MODAL);
        this.keys = keys;
        this.engTexts = engTexts;
        this.ruTexts = ruTexts;
        this.selected = (initialSelected != null) ? initialSelected.clone() : new boolean[keys.size()];
        initComponents();
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents() {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        previewTable = new JTable(new PreviewTableModel());
        previewTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        previewTable.getColumnModel().getColumn(0).setMaxWidth(40);
        previewTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        previewTable.getColumnModel().getColumn(2).setPreferredWidth(360);
        previewTable.getColumnModel().getColumn(3).setPreferredWidth(360);
        previewTable.setRowHeight(50);
        previewTable.setShowVerticalLines(true);
        previewTable.setShowHorizontalLines(true);
        previewTable.setGridColor(new Color(224, 226, 230));
        previewTable.setIntercellSpacing(new Dimension(1, 1));

        JButton allButton = new JButton("Выбрать все");
        JButton noneButton = new JButton("Снять выбор");
        JButton translateButton = new JButton("Перевести выбранные");
        JButton cancelButton = new JButton("Отмена");

        JCheckBox verifyCheckBox = new JCheckBox(
                "Проверять и исправлять уже имеющиеся русские переводы");
        verifyCheckBox.addItemListener(e -> {
            verifyExisting = verifyCheckBox.isSelected();
            applyDefaultSelection();
        });

        allButton.addActionListener(e -> setAllSelected(true));
        noneButton.addActionListener(e -> setAllSelected(false));
        translateButton.addActionListener(e -> {
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(e -> dispose());

        JLabel info = new JLabel("Предпросмотр: выберите строки для обработки на русском:");
        JScrollPane scroll = new JScrollPane(previewTable);
        scroll.setPreferredSize(new Dimension(1000, 450));

        JPanel northPanel = new JPanel(new BorderLayout(8, 8));
        northPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        northPanel.add(info, BorderLayout.NORTH);
        northPanel.add(verifyCheckBox, BorderLayout.SOUTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(allButton);
        buttonPanel.add(noneButton);
        buttonPanel.add(translateButton);
        buttonPanel.add(cancelButton);

        setLayout(new BorderLayout());
        add(northPanel, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void applyDefaultSelection() {
        for (int i = 0; i < selected.length; i++) {
            String ru = ruTexts.get(i);
            boolean hasRussian = ru != null && !ru.trim().isEmpty();
            selected[i] = !hasRussian || verifyExisting;
        }
        previewTable.repaint();
    }

    private void setAllSelected(boolean value) {
        for (int i = 0; i < selected.length; i++) {
            selected[i] = value;
        }
        previewTable.repaint();
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public boolean isVerifyExisting() {
        return verifyExisting;
    }

    public int[] getSelectedIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                indices.add(i);
            }
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = indices.get(i);
        }
        return result;
    }

    private class PreviewTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return keys.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0: return "✓";
                case 1: return "Ключ";
                case 2: return "Английский (исходник)";
                case 3: return "Русский (текущий)";
                default: return "";
            }
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            switch (columnIndex) {
                case 0: return selected[rowIndex];
                case 1: return keys.get(rowIndex);
                case 2: return engTexts.get(rowIndex);
                case 3: {
                    String r = ruTexts.get(rowIndex);
                    return (r == null || r.isEmpty()) ? "(перевод отсутствует)" : r;
                }
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                selected[rowIndex] = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }
}
