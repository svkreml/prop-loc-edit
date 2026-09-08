package svkreml.prop_loc_edit.gui;

import svkreml.prop_loc_edit.ai.AiConfig;
import svkreml.prop_loc_edit.ai.AiTranslationService;
import javax.swing.*;
import java.awt.*;

public class AiSettingsDialog extends JDialog {

    private final AiConfig config;
    private boolean confirmed;

    private final JTextField baseUrlField;
    private final JTextField modelField;
    private final JTextField fallbackBaseUrlField;
    private final JTextField fallbackModelField;
    private final JPasswordField apiKeyField;
    private final JTextField timeoutField;
    private final JTextField temperatureField;
    private final JTextField maxTokensField;
    private final JTextField maxAttemptsField;
    private final JTextField threadsField;
    private final JTextArea contextField;
    private final JButton checkButton;

    public AiSettingsDialog(Window owner) {
        super(owner, "Настройки ИИ", ModalityType.APPLICATION_MODAL);
        this.config = AiConfig.load();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        int row = 0;

        baseUrlField = new JTextField(config.getBaseUrl(), 40);
        modelField = new JTextField(config.getModel(), 40);
        fallbackBaseUrlField = new JTextField(config.getFallbackBaseUrl(), 40);
        fallbackModelField = new JTextField(config.getFallbackModel(), 40);
        apiKeyField = new JPasswordField(config.getApiKey(), 40);
        timeoutField = new JTextField(String.valueOf(config.getTimeoutMs()), 10);
        temperatureField = new JTextField(String.valueOf(config.getTemperature()), 10);
        maxTokensField = new JTextField(String.valueOf(config.getMaxTokens()), 10);
        maxAttemptsField = new JTextField(String.valueOf(config.getMaxAttempts()), 10);
        threadsField = new JTextField(String.valueOf(config.getThreads()), 10);
        contextField = new JTextArea(config.getContext() == null ? "" : config.getContext(), 8, 40);
        contextField.setLineWrap(true);
        contextField.setWrapStyleWord(true);
        contextField.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        gbc.gridx = 0;
        gbc.gridwidth = 1;
        addLabel(form, gbc, row, "Адрес сервера (baseUrl):");
        gbc.gridx = 1;
        form.add(baseUrlField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Модель:");
        gbc.gridx = 1;
        form.add(modelField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Резервный адрес (отказоустойчивость):");
        gbc.gridx = 1;
        form.add(fallbackBaseUrlField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Резервная модель:");
        gbc.gridx = 1;
        form.add(fallbackModelField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "API-ключ (необязателен):");
        gbc.gridx = 1;
        form.add(apiKeyField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Таймаут (мс):");
        gbc.gridx = 1;
        form.add(timeoutField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Temperature:");
        gbc.gridx = 1;
        form.add(temperatureField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Max tokens:");
        gbc.gridx = 1;
        form.add(maxTokensField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Попытки (maxAttempts):");
        gbc.gridx = 1;
        form.add(maxAttemptsField, gbc);
        row++;

        gbc.gridx = 0;
        addLabel(form, gbc, row, "Потоков (threads):");
        gbc.gridx = 1;
        form.add(threadsField, gbc);
        row++;

        JScrollPane contextScroll = new JScrollPane(contextField);
        contextScroll.setPreferredSize(new Dimension(420, 150));
        contextScroll.setMinimumSize(new Dimension(300, 120));
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.weighty = 1.0;
        addLabel(form, gbc, row, "Контекст перевода (что локализуется):");
        gbc.gridx = 1;
        form.add(contextScroll, gbc);
        row++;

        checkButton = new JButton("Проверить подключение");
        checkButton.addActionListener(e -> checkConnection());
        JButton saveButton = new JButton("Сохранить");
        JButton cancelButton = new JButton("Отмена");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(checkButton);
        buttons.add(saveButton);
        buttons.add(cancelButton);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);

        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> dispose());

        getRootPane().setDefaultButton(saveButton);
        pack();
        setMinimumSize(new Dimension(560, 520));
        setResizable(true);
        setLocationRelativeTo(owner);
    }

    private void addLabel(JPanel panel, GridBagConstraints gbc, int row, String text) {
        gbc.gridy = row;
        panel.add(new JLabel(text), gbc);
    }

    private void save() {
        try {
            config.setBaseUrl(baseUrlField.getText().trim());
            config.setModel(modelField.getText().trim());
            config.setFallbackBaseUrl(fallbackBaseUrlField.getText().trim());
            config.setFallbackModel(fallbackModelField.getText().trim());
            config.setApiKey(new String(apiKeyField.getPassword()));
            config.setTimeoutMs(Integer.parseInt(timeoutField.getText().trim()));
            config.setTemperature(Double.parseDouble(temperatureField.getText().trim()));
            config.setMaxTokens(Integer.parseInt(maxTokensField.getText().trim()));
            config.setMaxAttempts(Integer.parseInt(maxAttemptsField.getText().trim()));
            config.setThreads(Math.max(1, Integer.parseInt(threadsField.getText().trim())));
            config.setContext(contextField.getText());
            config.save();
            confirmed = true;
            dispose();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this,
                    "Числовые поля (timeoutMs, temperature, maxTokens, maxAttempts, threads) заполнены неверно.",
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Не удалось сохранить конфигурацию: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void checkConnection() {
        try {
            AiConfig probe = new AiConfig();
            probe.setBaseUrl(baseUrlField.getText().trim());
            probe.setModel(modelField.getText().trim());
            probe.setApiKey(new String(apiKeyField.getPassword()));
            probe.setTimeoutMs(config.getTimeoutMs());
            probe.setTemperature(config.getTemperature());
            probe.setMaxTokens(config.getMaxTokens());
            probe.setMaxAttempts(config.getMaxAttempts());
            probe.setThreads(config.getThreads());
            probe.setContext(contextField.getText());

            checkButton.setEnabled(false);
            final Cursor oldCursor = getCursor();
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    return new AiTranslationService(probe).checkConnection();
                }

                @Override
                protected void done() {
                    setCursor(oldCursor);
                    checkButton.setEnabled(true);
                    try {
                        JOptionPane.showMessageDialog(AiSettingsDialog.this, get(),
                                "Проверка подключения", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(AiSettingsDialog.this,
                                "Сервер недоступен: " + e.getMessage(),
                                "Проверка подключения", JOptionPane.ERROR_MESSAGE);
                    }
                }
            };
            worker.execute();
        } catch (Exception ex) {
            checkButton.setEnabled(true);
            JOptionPane.showMessageDialog(this,
                    "Неверно заполнены поля: " + ex.getMessage(),
                    "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public AiConfig getConfig() {
        return config;
    }
}