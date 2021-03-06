/*
 * Copyright 2014 Guidewire Software, Inc.
 */

package gw.plugin.ij.refactor.signature;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentAdapter;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiCodeFragment;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.changeSignature.CallerChooserBase;
import com.intellij.refactoring.changeSignature.ParameterInfoImpl;
import com.intellij.refactoring.ui.*;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.ui.*;
import com.intellij.ui.table.JBTable;
import com.intellij.ui.table.TableView;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.Consumer;
import com.intellij.util.IJSwingUtilities;
import com.intellij.util.PlatformIcons;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.table.JBListTable;
import com.intellij.util.ui.table.JBTableRow;
import com.intellij.util.ui.table.JBTableRowEditor;
import gw.plugin.ij.lang.psi.api.statements.typedef.IGosuMethod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class ChangeSignatureDialogBase extends RefactoringDialog {

  private static final Logger LOG = Logger.getInstance(ChangeSignatureDialogBase.class);

  protected static final String EXIT_SILENTLY = "";

  protected final GosuMethodDescriptor myMethod;
  private final boolean myAllowDelegation;
  protected JPanel myNamePanel;
  protected EditorTextField myNameField;
  protected EditorTextField myReturnTypeField;
  protected JBListTable myParametersList;
  protected TableView<ParameterTableModelItem> myParametersTable;
  protected final GosuParameterTableModel myParametersTableModel;
  protected final UpdateSignatureListener mySignatureUpdater = new UpdateSignatureListener();
  private MethodSignatureComponent mySignatureArea;
  private final Alarm myUpdateSignatureAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

  protected VisibilityPanelBase<String> myVisibilityPanel;

  @Nullable
  protected PsiCodeFragment myReturnTypeCodeFragment;
  private DelegationPanel myDelegationPanel;
  protected AnActionButton myPropagateParamChangesButton;
  protected Set<IGosuMethod> myMethodsToPropagateParameters = null;

  private Tree myParameterPropagationTreeToReuse;

  protected final PsiElement myDefaultValueContext;

  protected abstract LanguageFileType getFileType();

  protected abstract GosuParameterTableModel createParametersInfoModel(GosuMethodDescriptor method);

  protected abstract BaseRefactoringProcessor createRefactoringProcessor();

  protected abstract PsiCodeFragment createReturnTypeCodeFragment();

  @Nullable
  protected abstract CallerChooserBase<PsiMethod> createCallerChooser(String title, Tree treeToReuse, Consumer<Set<IGosuMethod>> callback);

  @Nullable
  protected abstract String validateAndCommitData();

  protected abstract String calculateSignature();

  protected abstract VisibilityPanelBase<String> createVisibilityControl();

  protected final ChangeSignatureHandler changeSignatureHandler;

  public ChangeSignatureDialogBase(Project project, final GosuMethodDescriptor method, boolean allowDelegation,
                                   PsiElement defaultValueContext, ChangeSignatureHandler changeSignatureHandler) {
    super(project, true);
    myMethod = method;
    myDefaultValueContext = defaultValueContext;
    myParametersTableModel = createParametersInfoModel(method);
    myAllowDelegation = allowDelegation;
    this.changeSignatureHandler = changeSignatureHandler;

    setParameterInfos(method.getParameters());

    setTitle(GosuChangeSignatureHandler.REFACTORING_NAME);
    init();
    doUpdateSignature();
    Disposer.register(myDisposable, new Disposable() {
      public void dispose() {
        myUpdateSignatureAlarm.cancelAllRequests();
      }
    });
  }

  public void setParameterInfos(List<ParameterInfoImpl> parameterInfos) {
    myParametersTableModel.setParameterInfos(parameterInfos);
    updateSignature();
  }

  protected String getMethodName() {
    if (myNameField != null) {
      return myNameField.getText().trim();
    }
    else {
      return myMethod.getName();
    }
  }

  @Nullable
  protected String getVisibility() {
    if (myVisibilityPanel != null) {
      return myVisibilityPanel.getVisibility();
    }
    else {
      return myMethod.getVisibility();
    }
  }

  public List<ParameterInfoImpl> getParameters() {
    List<ParameterInfoImpl> result = new ArrayList<ParameterInfoImpl>(myParametersTableModel.getRowCount());
    for (ParameterTableModelItem item : myParametersTableModel.getItems()) {
      result.add(item.parameter);
    }
    return result;
  }

  public boolean isGenerateDelegate() {
    return myAllowDelegation && myDelegationPanel.isGenerateDelegate();
  }

  public JComponent getPreferredFocusedComponent() {
    final JTable table = getTableComponent();

    if (table != null && table.getRowCount() > 0) {
      if (table.getColumnModel().getSelectedColumnCount() == 0) {
        final int selectedIdx = getSelectedIdx();
        table.getSelectionModel().setSelectionInterval(selectedIdx, selectedIdx);
        table.getColumnModel().getSelectionModel().setSelectionInterval(0, 0);
      }
      return table;
    }
    else {
      return myNameField == null ? super.getPreferredFocusedComponent() : myNameField;
    }
  }

  protected int getSelectedIdx() {
    return 0;
  }

  protected JBTable getTableComponent() {
    return myParametersList == null ? myParametersTable : myParametersList.getTable();
  }


  protected JComponent createNorthPanel() {
    final JPanel panel = new JPanel(new GridBagLayout());
    GridBagConstraints gbc = new GridBagConstraints(0,0,1,1,0,1, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, new Insets(0,0,0,0), 0,0);

    myNamePanel = new JPanel(new BorderLayout(0,2));
    myNameField = new EditorTextField(myMethod.getName());
    final JLabel nameLabel = new JLabel(RefactoringBundle.message("changeSignature.name.prompt"));
    nameLabel.setLabelFor(myNameField);
    myNameField.setEnabled(myMethod.canChangeName());
    if (myMethod.canChangeName()) {
      myNameField.addDocumentListener(mySignatureUpdater);
      myNameField.setPreferredWidth(200);
    }
    myNamePanel.add(nameLabel, BorderLayout.NORTH);
    IJSwingUtilities.adjustComponentsOnMac(nameLabel, myNameField);
    myNamePanel.add(myNameField, BorderLayout.SOUTH);

    createVisibilityPanel();

    if (myMethod.canChangeVisibility() && myVisibilityPanel instanceof ComboBoxVisibilityPanel) {
      ((ComboBoxVisibilityPanel)myVisibilityPanel).registerUpDownActionsFor(myNameField);
      myVisibilityPanel.setBorder(new EmptyBorder(0,0,0,8));
      panel.add(myVisibilityPanel, gbc);
      gbc.gridx++;
    }

    gbc.weightx = 1;

    if (myMethod.canChangeReturnType() != GosuMethodDescriptor.ReadWriteOption.None) {
      JPanel typePanel = new JPanel(new BorderLayout(0,2));
      typePanel.setBorder(new EmptyBorder(0,0,0,8));
      final JLabel typeLabel = new JLabel(RefactoringBundle.message("changeSignature.return.type.prompt"));
      myReturnTypeCodeFragment = createReturnTypeCodeFragment();
      final Document document = PsiDocumentManager.getInstance(myProject).getDocument(myReturnTypeCodeFragment);
      myReturnTypeField = createReturnTypeTextField(document);
      ((ComboBoxVisibilityPanel)myVisibilityPanel).registerUpDownActionsFor(myReturnTypeField);
      typeLabel.setLabelFor(myReturnTypeField);

      if (myMethod.canChangeReturnType() == GosuMethodDescriptor.ReadWriteOption.ReadWrite) {
        myReturnTypeField.setPreferredWidth(200);
        myReturnTypeField.addDocumentListener(mySignatureUpdater);
      }
      else {
        myReturnTypeField.setEnabled(false);
      }

      typePanel.add(typeLabel, BorderLayout.NORTH);
      IJSwingUtilities.adjustComponentsOnMac(typeLabel, myReturnTypeField);
      typePanel.add(myReturnTypeField, BorderLayout.SOUTH);
      panel.add(typePanel, gbc);
      gbc.gridx++;
    }

    panel.add(myNamePanel, gbc);

    return panel;
  }

  protected EditorTextField createReturnTypeTextField(Document document) {
    return new EditorTextField(document, myProject, getFileType());
  }

  private DelegationPanel createDelegationPanel() {
    return new DelegationPanel() {
      protected void stateModified() {
        myParametersTableModel.fireTableDataChanged();
        myParametersTable.repaint();
      }
    };
  }

  protected JComponent createCenterPanel() {
    final JPanel panel = new JPanel(new BorderLayout());

    //Should be called from here to initialize fields !!!
    final JComponent optionsPanel = createOptionsPanel();

    final JPanel subPanel = new JPanel(new BorderLayout());
    final List<Pair<String, JPanel>> panels = createAdditionalPanels();
    if (myMethod.canChangeParameters()) {
      final JPanel parametersPanel = createParametersPanel(!panels.isEmpty());
      if (!panels.isEmpty()) {
        parametersPanel.setBorder(IdeBorderFactory.createEmptyBorder(0));
      }
      subPanel.add(parametersPanel, BorderLayout.CENTER);
    }

    if (myMethod.canChangeVisibility() && !(myVisibilityPanel instanceof ComboBoxVisibilityPanel)) {
      subPanel.add(myVisibilityPanel, myMethod.canChangeParameters() ? BorderLayout.EAST : BorderLayout.CENTER);
    }

    panel.add(subPanel, BorderLayout.CENTER);
    final JPanel main;
    if (panels.isEmpty()) {
      main = panel;
    }
    else {
      final TabbedPaneWrapper tabbedPane = new TabbedPaneWrapper(getDisposable());
      tabbedPane.addTab(RefactoringBundle.message("parameters.border.title"), panel);
      for (Pair<String, JPanel> extraPanel : panels) {
        tabbedPane.addTab(extraPanel.first, extraPanel.second);
      }
      main = new JPanel(new BorderLayout());
      final JComponent tabs = tabbedPane.getComponent();
      main.add(tabs, BorderLayout.CENTER);
      //remove traversal policies
      for (JComponent c : UIUtil.findComponentsOfType(tabs, JComponent.class)) {
        c.setFocusCycleRoot(false);
        c.setFocusTraversalPolicy(null);
      }
    }
    final JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(optionsPanel, BorderLayout.NORTH);
    bottom.add(createSignaturePanel(), BorderLayout.SOUTH);
    main.add(bottom, BorderLayout.SOUTH);
    main.setBorder(IdeBorderFactory.createEmptyBorder(5, 0, 0, 0));
    return main;
  }

  protected JComponent createOptionsPanel() {
    final JPanel panel = new JPanel(new BorderLayout());
    if (myAllowDelegation) {
      myDelegationPanel = createDelegationPanel();
      panel.add(myDelegationPanel, BorderLayout.WEST);
    }

    myPropagateParamChangesButton =
      new AnActionButton(RefactoringBundle.message("changeSignature.propagate.parameters.title"), null, PlatformIcons.NEW_PARAMETER) {
        @Override
        public void actionPerformed(AnActionEvent e) {
          final Ref<CallerChooserBase<IGosuMethod>> chooser = new Ref<CallerChooserBase<IGosuMethod>>();
          Consumer<Set<IGosuMethod>> callback = new Consumer<Set<IGosuMethod>>() {
            @Override
            public void consume(Set<IGosuMethod> callers) {
              myMethodsToPropagateParameters = callers;
              myParameterPropagationTreeToReuse = chooser.get().getTree();
            }
          };
          try {
            String message = RefactoringBundle.message("changeSignature.parameter.caller.chooser");
            chooser.set((CallerChooserBase) createCallerChooser(message, myParameterPropagationTreeToReuse, (Consumer) callback));
          }
          catch (ProcessCanceledException ex) {
            // user cancelled initial callers search, don't show dialog
            return;
          }
          chooser.get().show();
        }
      };

    final JPanel result = new JPanel(new VerticalFlowLayout(VerticalFlowLayout.TOP));
    result.add(panel);
    return result;
  }

  protected JPanel createVisibilityPanel() {
    myVisibilityPanel = createVisibilityControl();
    myVisibilityPanel.setVisibility(myMethod.getVisibility());
    myVisibilityPanel.addListener(mySignatureUpdater);
    return myVisibilityPanel;
  }


  @NotNull
  protected List<Pair<String, JPanel>> createAdditionalPanels() {
    return Collections.emptyList();
  }

  protected String getDimensionServiceKey() {
    return "refactoring.ChangeSignatureDialog";
  }

  protected boolean isListTableViewSupported() {
    return false;
  }


  protected JPanel createParametersPanel(boolean hasTabsInDialog) {
    myParametersTable = new TableView<ParameterTableModelItem>(myParametersTableModel) {

      public void removeEditor() {
        clearEditorListeners();
        super.removeEditor();
      }

      @Override
      public void editingStopped(ChangeEvent e) {
        super.editingStopped(e);
        repaint(); // to update disabled cells background
      }

      private void clearEditorListeners() {
        final TableCellEditor editor = getCellEditor();
        if (editor instanceof StringTableCellEditor) {
          final StringTableCellEditor ed = (StringTableCellEditor)editor;
          ed.clearListeners();
        }
        else if (editor instanceof CodeFragmentTableCellEditorBase) {
          ((CodeFragmentTableCellEditorBase)editor).clearListeners();
        }
      }

      public Component prepareEditor(final TableCellEditor editor, final int row, final int column) {
        final DocumentAdapter listener = new DocumentAdapter() {
          @Override
          public void documentChanged(DocumentEvent e) {
            final TableCellEditor ed = myParametersTable.getCellEditor();
            if (ed != null) {
              Object editorValue = ed.getCellEditorValue();
              myParametersTableModel
                .setValueAtWithoutUpdate(editorValue, row, column);
              updateSignature();
            }
          }
        };

        if (editor instanceof StringTableCellEditor) {
          final StringTableCellEditor ed = (StringTableCellEditor)editor;
          ed.addDocumentListener(listener);
        }
        else if (editor instanceof CodeFragmentTableCellEditorBase) {
          ((CodeFragmentTableCellEditorBase)editor).addDocumentListener(listener);
        }
        return super.prepareEditor(editor, row, column);
      }
    };

    myParametersTable.setCellSelectionEnabled(true);
    myParametersTable.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    myParametersTable.getSelectionModel().setSelectionInterval(0, 0);
    myParametersTable.setSurrendersFocusOnKeystroke(true);
    myPropagateParamChangesButton.setShortcut(CustomShortcutSet.fromString("alt G"));

    if (isListTableViewSupported() && Registry.is("change.signature.awesome.mode")) {
      myParametersList = new JBListTable(myParametersTable) {
        @Override
        protected JComponent getRowRenderer(JTable table, int row, boolean selected, boolean focused) {
          final List<ParameterTableModelItem> items = myParametersTable.getItems();
          final JComponent component = getRowPresentation(items.get(row), selected, focused);
          for (EditorTextField editorTextField : UIUtil.findComponentsOfType(component, EditorTextField.class)) {
            editorTextField.addSettingsProvider(new EditorSettingsProvider() {
              @Override
              public void customizeSettings(EditorEx editor) {
                editor.getSettings().setWhitespacesShown(false);
              }
            });
          }
          return component;
        }

        @Override
        protected boolean isRowEmpty(int row) {
          final List<ParameterTableModelItem> items = myParametersTable.getItems();
          return isEmptyRow(items.get(row));
        }

        @Override
        protected JBTableRowEditor getRowEditor(final int row) {
          final List<ParameterTableModelItem> items = myParametersTable.getItems();
          JBTableRowEditor editor = getTableEditor(myParametersList.getTable(), items.get(row));
          LOG.assertTrue(editor != null);
          editor.addDocumentListener(new JBTableRowEditor.RowDocumentListener() {
            @Override
            public void documentChanged(DocumentEvent e, int column) {
              if (myParametersTableModel.getColumnClass(column).equals(String.class)) {
                myParametersTableModel.setValueAtWithoutUpdate(e.getDocument().getText(), row, column);
              }

              updateSignature();
            }
          });
          return editor;
        }

        @Override
        protected JBTableRow getRowAt(final int row) {
          return new JBTableRow() {
            @Override
            public Object getValueAt(int column) {
              return myInternalTable.getValueAt(row, column);
            }
          };
        }
      };
      final JPanel buttonsPanel = ToolbarDecorator.createDecorator(myParametersList.getTable())
        .addExtraAction(myPropagateParamChangesButton)
        .createPanel();
      myParametersList.getTable().getModel().addTableModelListener(mySignatureUpdater);
      return buttonsPanel;
    }
    else {
      final JPanel buttonsPanel =
        ToolbarDecorator.createDecorator(getTableComponent())
          .addExtraAction(myPropagateParamChangesButton)
          .createPanel();

      myPropagateParamChangesButton.setEnabled(false);
      myPropagateParamChangesButton.setVisible(false);
      myParametersTable.setStriped(true);

      myParametersTableModel.addTableModelListener(mySignatureUpdater);

      customizeParametersTable(myParametersTable);
      return buttonsPanel;
    }
  }

  @Nullable
  protected JBTableRowEditor getTableEditor(JTable table, ParameterTableModelItem item) {
    return null;
  }

  protected boolean isEmptyRow(ParameterTableModelItem row) {
    return false;
  }

  @Nullable
  protected JComponent getRowPresentation(ParameterTableModelItem item, boolean selected, boolean focused) {
    return null;
  }

  protected void customizeParametersTable(TableView<ParameterTableModelItem> table) {
  }

  private JComponent createSignaturePanel() {
    mySignatureArea = createSignaturePreviewComponent();
    JPanel panel = new JPanel(new BorderLayout());
    panel.add(SeparatorFactory.createSeparator(RefactoringBundle.message("signature.preview.border.title"), null), BorderLayout.NORTH);
    panel.add(mySignatureArea, BorderLayout.CENTER);
    mySignatureArea.setPreferredSize(new Dimension(-1, 130));
    mySignatureArea.setMinimumSize(new Dimension(-1, 130));
    mySignatureArea.addFocusListener(new FocusAdapter() {
      @Override
      public void focusGained(FocusEvent e) {
        Container root = findTraversalRoot(getContentPane());

        if (root != null) {
          Component c = root.getFocusTraversalPolicy().getComponentAfter(root, mySignatureArea);
          if (c != null) {
            IdeFocusManager.findInstance().requestFocus(c, true);
          }
        }
      }
    });
    updateSignature();
    return panel;
  }

  private static Container findTraversalRoot(Container container) {
    Container current = KeyboardFocusManager.getCurrentKeyboardFocusManager().getCurrentFocusCycleRoot();
    Container root;

    if (current == container) {
      root = container;
    }
    else {
      root = container.getFocusCycleRootAncestor();
      if (root == null) {
        root = container;
      }
    }

    if (root != current) {
      KeyboardFocusManager.getCurrentKeyboardFocusManager().setGlobalCurrentFocusCycleRoot(root);
    }
    return root;
  }

  protected MethodSignatureComponent createSignaturePreviewComponent() {
    return new MethodSignatureComponent(calculateSignature(), getProject(), getFileType());
  }

  protected void updateSignature() {
    if (mySignatureArea == null || myPropagateParamChangesButton == null) return;

    final Runnable updateRunnable = new Runnable() {
      public void run() {
        myUpdateSignatureAlarm.cancelAllRequests();
        myUpdateSignatureAlarm.addRequest(new Runnable() {
          public void run() {
            updateSignatureAlarmFired();
          }
        }, 100);
      }
    };
    SwingUtilities.invokeLater(updateRunnable);
  }

  protected void updateSignatureAlarmFired() {
    doUpdateSignature();
    updatePropagateButtons();
  }

  private void doUpdateSignature() {
    PsiDocumentManager.getInstance(myProject).commitAllDocuments();
    mySignatureArea.setSignature(calculateSignature());
  }

  protected void updatePropagateButtons() {
    if (myPropagateParamChangesButton != null) {
      myPropagateParamChangesButton.setEnabled(!isGenerateDelegate() && mayPropagateParameters());
    }
  }

  protected boolean mayPropagateParameters() {
    final List<ParameterInfoImpl> infos = getParameters();
    if (infos.size() <= myMethod.getParametersCount()) return false;
    for (int i = 0; i < myMethod.getParametersCount(); i++) {
      if (infos.get(i).getOldIndex() != i) return false;
    }
    return true;
  }

  protected void doAction() {
    if (myParametersTable != null) {
      TableUtil.stopEditing(myParametersTable);
    }
    String message = validateAndCommitData();
    if (message != null) {
      if (message != EXIT_SILENTLY) {
        CommonRefactoringUtil.showErrorMessage(getTitle(), message, getHelpId(), myProject);
      }
      return;
    }
    if (myMethodsToPropagateParameters != null && !mayPropagateParameters()) {
      Messages.showWarningDialog(myProject, RefactoringBundle.message("changeSignature.parameters.wont.propagate"),
                                 com.intellij.refactoring.changeSignature.ChangeSignatureHandler.REFACTORING_NAME);
      myMethodsToPropagateParameters = null;
    }

//    invokeRefactoring(createRefactoringProcessor());

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      public void run() {
        invokeRefactoring(createRefactoringProcessor());
      }
    });
  }

  @Override
  protected String getHelpId() {
    return "refactoring.changeSignature";
  }

  class UpdateSignatureListener implements ChangeListener, DocumentListener, TableModelListener {
    private void update() {
      updateSignature();
    }

    @Override
    public void stateChanged(ChangeEvent e) {
      update();
    }

    @Override
    public void documentChanged(DocumentEvent event) {
      update();
    }

    @Override
    public void tableChanged(TableModelEvent e) {
      update();
    }

    //---ignored
    @Override
    public void beforeDocumentChange(DocumentEvent event) {
    }
  }
}
