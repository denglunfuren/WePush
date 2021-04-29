package com.fangxuele.tool.push.ui.dialog.importway;

import cn.hutool.core.io.file.FileReader;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.json.JSONUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.fangxuele.tool.push.App;
import com.fangxuele.tool.push.dao.TPeopleDataMapper;
import com.fangxuele.tool.push.dao.TPeopleImportConfigMapper;
import com.fangxuele.tool.push.dao.TPeopleMapper;
import com.fangxuele.tool.push.domain.TPeopleData;
import com.fangxuele.tool.push.domain.TPeopleImportConfig;
import com.fangxuele.tool.push.logic.PeopleImportWayEnum;
import com.fangxuele.tool.push.logic.PushData;
import com.fangxuele.tool.push.ui.UiConsts;
import com.fangxuele.tool.push.ui.form.PeopleEditForm;
import com.fangxuele.tool.push.ui.listener.PeopleManageListener;
import com.fangxuele.tool.push.util.ComponentUtil;
import com.fangxuele.tool.push.util.FileCharSetUtil;
import com.fangxuele.tool.push.util.MybatisUtil;
import com.fangxuele.tool.push.util.SqliteUtil;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import com.opencsv.CSVReader;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ImportByFile extends JDialog {
    private JPanel contentPane;
    private JButton buttonCancel;
    private JLabel importFromFileLabel;
    private JTextField memberFilePathField;
    private JButton memberImportExploreButton;
    private JButton importFromFileButton;

    private static final Log logger = LogFactory.get();

    public static final String TXT_FILE_DATA_SEPERATOR_REGEX = "\\|";

    private static TPeopleMapper peopleMapper = MybatisUtil.getSqlSession().getMapper(TPeopleMapper.class);
    private static TPeopleDataMapper peopleDataMapper = MybatisUtil.getSqlSession().getMapper(TPeopleDataMapper.class);
    private static TPeopleImportConfigMapper peopleImportConfigMapper = MybatisUtil.getSqlSession().getMapper(TPeopleImportConfigMapper.class);

    public ImportByFile() {
        super(App.mainFrame, "通过文件导入人群");
        setContentPane(contentPane);
        setModal(true);
        ComponentUtil.setPreferSizeAndLocateToCenter(this, 0.4, 0.2);
        getRootPane().setDefaultButton(importFromFileButton);

        // 获取上一次导入的配置
        TPeopleImportConfig tPeopleImportConfig = peopleImportConfigMapper.selectByPeopleId(PeopleManageListener.selectedPeopleId);
        if (tPeopleImportConfig != null) {
            memberFilePathField.setText(tPeopleImportConfig.getLastFilePath());
        }

        // 文件浏览按钮
        memberImportExploreButton.addActionListener(e -> {
            File beforeFile = new File(memberFilePathField.getText());
            JFileChooser fileChooser;

            if (beforeFile.exists()) {
                fileChooser = new JFileChooser(beforeFile);
            } else {
                fileChooser = new JFileChooser();
            }

            FileFilter filter = new FileNameExtensionFilter("*.txt,*.csv,*.xlsx,*.xls", "txt", "csv", "TXT", "CSV", "xlsx", "xls");
            fileChooser.setFileFilter(filter);

            int approve = fileChooser.showOpenDialog(App.mainFrame);
            if (approve == JFileChooser.APPROVE_OPTION) {
                memberFilePathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }

        });

        importFromFileButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onOK();
            }
        });

        buttonCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        });

        // call onCancel() when cross is clicked
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                onCancel();
            }
        });

        // call onCancel() on ESCAPE
        contentPane.registerKeyboardAction(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                onCancel();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void onOK() {
        String filePath = memberFilePathField.getText();
        if (StringUtils.isBlank(filePath)) {
            JOptionPane.showMessageDialog(PeopleEditForm.getInstance().getMainPanel(), "请填写或点击浏览按钮选择要导入的文件的路径！", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        ThreadUtil.execute(() -> importFromFile(filePath));
        dispose();
    }

    private void onCancel() {
        dispose();
    }

    /**
     * 通过文件导入
     */
    public static void importFromFile(String filePath) {
        PeopleEditForm peopleEditForm = PeopleEditForm.getInstance();
        peopleEditForm.getImportButton().setEnabled(false);
        JPanel memberPanel = peopleEditForm.getMainPanel();
        JProgressBar progressBar = peopleEditForm.getMemberTabImportProgressBar();
        JLabel memberCountLabel = peopleEditForm.getMemberTabCountLabel();

        File file = new File(filePath);
        if (!file.exists()) {
            JOptionPane.showMessageDialog(memberPanel, filePath + "\n该文件不存在！", "文件不存在",
                    JOptionPane.ERROR_MESSAGE);
            peopleEditForm.getImportButton().setEnabled(true);
            return;
        }
        CSVReader reader = null;
        FileReader fileReader;

        String memberCount = memberCountLabel.getText();
        int currentImported = 0;
        if (!"-".equals(memberCount)) {
            currentImported = Integer.parseInt(memberCount);
        }

        try {
            progressBar.setVisible(true);
            progressBar.setIndeterminate(true);
            String fileNameLowerCase = file.getName().toLowerCase();
            TPeopleData tPeopleData;
            String now = SqliteUtil.nowDateForSqlite();

            // 保存导入配置
            TPeopleImportConfig beforePeopleImportConfig = peopleImportConfigMapper.selectByPeopleId(PeopleManageListener.selectedPeopleId);

            TPeopleImportConfig tPeopleImportConfig = new TPeopleImportConfig();
            tPeopleImportConfig.setPeopleId(PeopleManageListener.selectedPeopleId);
            tPeopleImportConfig.setLastWay(String.valueOf(PeopleImportWayEnum.BY_FILE_CODE));
            tPeopleImportConfig.setLastFilePath(filePath);
            tPeopleImportConfig.setAppVersion(UiConsts.APP_VERSION);
            tPeopleImportConfig.setModifiedTime(now);

            if (beforePeopleImportConfig != null) {
                tPeopleImportConfig.setId(beforePeopleImportConfig.getId());
                peopleImportConfigMapper.updateByPrimaryKeySelective(tPeopleImportConfig);
            } else {
                tPeopleImportConfig.setCreateTime(now);
                peopleImportConfigMapper.insert(tPeopleImportConfig);
            }

            if (fileNameLowerCase.endsWith(".csv")) {
                // 可以解决中文乱码问题
                DataInputStream in = new DataInputStream(new FileInputStream(file));
                reader = new CSVReader(new InputStreamReader(in, FileCharSetUtil.getCharSet(file)));

                String[] nextLine;
                while (PeopleManageListener.selectedPeopleId != null && (nextLine = reader.readNext()) != null) {
                    tPeopleData = new TPeopleData();
                    tPeopleData.setPeopleId(PeopleManageListener.selectedPeopleId);
                    tPeopleData.setPin(nextLine[0]);
                    tPeopleData.setVarData(JSONUtil.toJsonStr(nextLine));
                    tPeopleData.setAppVersion(UiConsts.APP_VERSION);
                    tPeopleData.setCreateTime(now);
                    tPeopleData.setModifiedTime(now);

                    peopleDataMapper.insert(tPeopleData);
                    currentImported++;
                    memberCountLabel.setText(String.valueOf(currentImported));
                }
            } else if (fileNameLowerCase.endsWith(".xlsx") || fileNameLowerCase.endsWith(".xls")) {
                ExcelReader excelReader = ExcelUtil.getReader(file);
                List<List<Object>> readAll = excelReader.read(1, Integer.MAX_VALUE);
                PushData.allUser = Collections.synchronizedList(new ArrayList<>());

                for (List<Object> objects : readAll) {
                    if (objects != null && objects.size() > 0) {
                        String[] nextLine = new String[objects.size()];
                        for (int i = 0; i < objects.size(); i++) {
                            nextLine[i] = objects.get(i).toString();
                        }
                        currentImported++;
                        memberCountLabel.setText(String.valueOf(currentImported));
                    }
                }
            } else if (fileNameLowerCase.endsWith(".txt")) {
                fileReader = new FileReader(file, FileCharSetUtil.getCharSetName(file));
                BufferedReader br = fileReader.getReader();

                String line;
                while (PeopleManageListener.selectedPeopleId != null && (line = br.readLine()) != null) {
                    String[] nextLine = line.split(TXT_FILE_DATA_SEPERATOR_REGEX);

                    tPeopleData = new TPeopleData();
                    tPeopleData.setPeopleId(PeopleManageListener.selectedPeopleId);
                    tPeopleData.setPin(nextLine[0]);
                    tPeopleData.setVarData(JSONUtil.toJsonStr(nextLine));
                    tPeopleData.setAppVersion(UiConsts.APP_VERSION);
                    tPeopleData.setCreateTime(now);
                    tPeopleData.setModifiedTime(now);

                    peopleDataMapper.insert(tPeopleData);
                    currentImported++;
                    memberCountLabel.setText(String.valueOf(currentImported));
                }
            } else {
                JOptionPane.showMessageDialog(memberPanel, "不支持该格式的文件！", "文件格式不支持",
                        JOptionPane.ERROR_MESSAGE);
                peopleEditForm.getImportButton().setEnabled(true);
                return;
            }

            PeopleEditForm.initDataTable(PeopleManageListener.selectedPeopleId);

            if (!PushData.fixRateScheduling) {
                progressBar.setIndeterminate(false);
                progressBar.setVisible(false);
                JOptionPane.showMessageDialog(memberPanel, "导入完成！", "完成", JOptionPane.INFORMATION_MESSAGE);
            }

            App.config.setMemberFilePath(filePath);
            App.config.save();
        } catch (Exception e1) {
            JOptionPane.showMessageDialog(memberPanel, "导入失败！\n\n" + e1.getMessage(), "失败",
                    JOptionPane.ERROR_MESSAGE);
            logger.error(e1);
            e1.printStackTrace();
        } finally {
            progressBar.setMaximum(100);
            progressBar.setValue(100);
            progressBar.setIndeterminate(false);
            progressBar.setVisible(false);
            peopleEditForm.getImportButton().setEnabled(true);
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e1) {
                    logger.error(e1);
                    e1.printStackTrace();
                }
            }
        }
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        contentPane = new JPanel();
        contentPane.setLayout(new GridLayoutManager(2, 1, new Insets(10, 10, 10, 10), -1, -1));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(panel1, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, 1, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        panel1.add(spacer1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        buttonCancel = new JButton();
        buttonCancel.setText("Cancel");
        panel2.add(buttonCancel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        importFromFileButton = new JButton();
        importFromFileButton.setIcon(new ImageIcon(getClass().getResource("/icon/import_dark.png")));
        importFromFileButton.setText("导入");
        panel2.add(importFromFileButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
        contentPane.add(panel3, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        importFromFileLabel = new JLabel();
        importFromFileLabel.setHorizontalAlignment(11);
        importFromFileLabel.setHorizontalTextPosition(4);
        importFromFileLabel.setText("文件路径（*.txt,*.csv,*.xlsx）");
        panel3.add(importFromFileLabel, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        memberFilePathField = new JTextField();
        panel3.add(memberFilePathField, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        memberImportExploreButton = new JButton();
        memberImportExploreButton.setText("...");
        panel3.add(memberImportExploreButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return contentPane;
    }

}
