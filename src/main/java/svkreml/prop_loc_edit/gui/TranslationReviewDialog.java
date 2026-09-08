package svkreml.prop_loc_edit.gui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TranslationReviewDialog extends JDialog {

    private final List<Object[]> results;
    private final boolean[] apply;
    private boolean confirmed;
    private JTable table;

    public TranslationReviewDialog(Window owner, List<Object[]> results) {
        super(owner, "Предпросмотр изменений перевода", ModalityType.APPLICATION_MODAL);
        this.results = results;
        this.apply = new boolean[results.size()];
        int changedCount = 0;
        for (int i = 0; i < results.size(); i++) {
            Object[] r = results.get(i);
            String oldRu = (String) r[3];
            String newRu = (String) r[4];
            apply[i] = !Objects.equals(oldRu, newRu);
            if (apply[i]) {
                changedCount++;
            }
        }
        initComponents(results.size(), changedCount);
        pack();
        setLocationRelativeTo(owner);
    }

    private void initComponents(int total, int changedCount) {
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        table = new JTable(new ReviewTableModel());
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(2).setPreferredWidth(320);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);
        table.getColumnModel().getColumn(4).setPreferredWidth(320);
        table.setRowHeight(60);
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        table.setGridColor(new Color(224, 226, 230));
        table.setIntercellSpacing(new Dimension(1, 1));

        ReviewCellRenderer multiline = new ReviewCellRenderer();
        for (int c = 1; c <= 4; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(multiline);
        }

        JButton applyButton = new JButton("Применить выбранные");
        JButton cancelButton = new JButton("Отмена");
        applyButton.addActionListener(e -> {
            if (anySelected()) {
                confirmed = true;
                dispose();
            } else {
                JOptionPane.showMessageDialog(TranslationReviewDialog.this,
                        "Не выбрано ни одного изменения для применения.",
                        "Предпросмотр изменений", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        cancelButton.addActionListener(e -> dispose());

        JLabel info = new JLabel("Изменено строк: " + changedCount + " из " + total
                + ". Уберите галочку, чтобы отменить конкретную правку.");
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(1160, 500));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(applyButton);
        bottom.add(cancelButton);

        setLayout(new BorderLayout());
        add(info, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
    }

    private boolean anySelected() {
        for (boolean b : apply) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public int[] getAppliedIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < apply.length; i++) {
            if (apply[i]) {
                indices.add(i);
            }
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = indices.get(i);
        }
        return result;
    }

    private boolean isChanged(int rowIndex) {
        return !Objects.equals((String) results.get(rowIndex)[3], (String) results.get(rowIndex)[4]);
    }

    private class ReviewTableModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return results.size();
        }

        @Override
        public int getColumnCount() {
            return 5;
        }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case 0: return "✓";
                case 1: return "Ключ";
                case 2: return "Английский";
                case 3: return "Текущий русский";
                case 4: return "Новый русский";
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
            Object[] r = results.get(rowIndex);
            switch (columnIndex) {
                case 0:
                    return apply[rowIndex];
                case 1:
                    return r[1];
                case 2:
                    return r[2];
                case 3: {
                    String oldRu = (String) r[3];
                    return (oldRu == null || oldRu.isEmpty()) ? "(перевод отсутствует)" : oldRu;
                }
                case 4:
                    return r[4];
                default:
                    return "";
            }
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (columnIndex == 0) {
                apply[rowIndex] = Boolean.TRUE.equals(value);
                fireTableCellUpdated(rowIndex, columnIndex);
            }
        }
    }

    private class ReviewCellRenderer extends JTextArea implements javax.swing.table.TableCellRenderer {
        ReviewCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            setText((value == null) ? "" : value.toString());
            if (isSelected) {
                setForeground(t.getSelectionForeground());
                setBackground(t.getSelectionBackground());
            } else {
                setForeground(t.getForeground());
                setBackground(t.getBackground());
                if (isChanged(row)) {
                    if (column == 3) {
                        setBackground(new Color(255, 224, 224));
                    } else if (column == 4) {
                        setBackground(new Color(215, 255, 215));
                    }
                }
            }
            return this;
        }
    }
}