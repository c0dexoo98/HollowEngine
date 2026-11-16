package com.hollow.editor;

import com.hollow.render.Frame;
import com.hollow.scene.Node;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** HollowEngine 2 — the editor window (Godot-style docks):
 *  scene tree on the left, viewport in the center, inspector on the
 *  right, console + project files along the bottom. All actions
 *  delegate to {@link EditorModel}; this class is only the view.
 *  Launch with `hollow editor` — needs a real display. */
public final class EditorApp extends JFrame {

    private final EditorModel model;
    private final JTree tree;
    private final DefaultTableModel inspector;
    private final JTextArea consoleArea;
    private final DefaultListModel<String> files;
    private final ViewportPanel viewport;
    private Frame lastFrame;

    public EditorApp(Path dir){
        super("HollowEngine Editor — " + dir.toAbsolutePath().getFileName());
        this.model = new EditorModel(dir);
        model.console.add("editor ready: " + dir.toAbsolutePath());

        tree = new JTree(new javax.swing.tree.DefaultTreeModel(new javax.swing.tree.DefaultMutableTreeNode("scene")));
        tree.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){
                Object o = tree.getLastSelectedPathComponent();
                if(o != null){
                    String name = String.valueOf(o);
                    if(model.findNode(name) != null && !name.equals("root")){
                        model.select(name);
                        refresh();
                    }
                }
            }
        });
        JScrollPane treePane = new JScrollPane(tree);
        treePane.setPreferredSize(new Dimension(230, 0));

        inspector = new DefaultTableModel(new Object[]{ "property", "value" }, 0){
            @Override public boolean isCellEditable(int r, int c){ return c == 1; }
        };
        JTable table = new JTable(inspector);
        table.putClientProperty("terminateEditOnFocusLost", true);
        JScrollPane inspectorPane = new JScrollPane(table);
        inspectorPane.setPreferredSize(new Dimension(260, 0));
        inspector.addTableModelListener(e -> {
            int row = e.getFirstRow();
            if(e.getColumn() != 1 || row < 0 || model.selected.isEmpty()) return;
            String key = String.valueOf(inspector.getValueAt(row, 0));
            String val = String.valueOf(inspector.getValueAt(row, 1));
            model.setProperty(model.selected, key, val);
            refresh();
        });

        viewport = new ViewportPanel();

        consoleArea = new JTextArea(8, 40);
        consoleArea.setEditable(false);
        files = new DefaultListModel<>();
        JList<String> fileList = new JList<>(files);
        JScrollPane filesPane = new JScrollPane(fileList);
        filesPane.setPreferredSize(new Dimension(230, 0));

        JSplitPane bottom = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
            new JScrollPane(consoleArea), filesPane);
        bottom.setResizeWeight(0.7);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePane, viewport);
        main.setResizeWeight(0.22);
        JSplitPane right = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, main, inspectorPane);
        right.setResizeWeight(0.85);
        JSplitPane outer = new JSplitPane(JSplitPane.VERTICAL_SPLIT, right, bottom);
        outer.setResizeWeight(0.82);

        setJMenuBar(menu());
        setContentPane(outer);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setLocationRelativeTo(null);
        refresh();
    }

    private JMenuBar menu(){
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        item(file, "New Scene", e -> { model.scene = new com.hollow.scene.Scene(); model.select(""); refresh(); });
        item(file, "Open Scene…", e -> {
            JFileChooser fc = new JFileChooser(model.projectDir.toFile());
            if(fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION && model.openScene(fc.getSelectedFile().toPath())) refresh();
        });
        item(file, "Save Scene", e -> { model.saveScene(null); refresh(); });
        file.addSeparator();
        item(file, "Quit", e -> dispose());
        bar.add(file);

        JMenu scene = new JMenu("Scene");
        for(String t : new String[]{ "mesh", "camera", "light", "script", "node" }){
            final String type = t;
            item(scene, "Add " + t, e -> {
                String name = uniqueName(type);
                model.addNode(type, name);
                refresh();
            });
        }
        scene.addSeparator();
        item(scene, "Delete Selected", e -> { model.deleteNode(model.selected); refresh(); });
        bar.add(scene);

        JMenu build = new JMenu("Build");
        item(build, "Run 60 Frames", e -> {
            model.runLog.setLength(0);
            model.run(60, 1.0 / 60, new ArrayList<>());
            refresh();
        });
        item(build, "Render Frame (PNG)", e -> {
            lastFrame = model.renderPreview(960, 540);
            lastFrame.writePNG(model.projectDir.resolve("frame.png"));
            model.console.add("wrote frame.png (" + lastFrame.fillRatio() + " filled)");
            viewport.setFrame(lastFrame);
            refresh();
        });
        bar.add(build);

        JMenu help = new JMenu("Help");
        item(help, "About", e -> JOptionPane.showMessageDialog(this,
            "HollowEngine 2.0\nA from-scratch game engine: HollowScript,\nscene graph, software renderer, editor.",
            "About", JOptionPane.INFORMATION_MESSAGE));
        bar.add(help);

        return bar;
    }

    private void item(JMenu m, String label, java.awt.event.ActionListener a){
        JMenuItem i = new JMenuItem(label);
        i.addActionListener(a);
        m.add(i);
    }

    private String uniqueName(String type){
        int i = 1;
        while(model.findNode(type + "_" + i) != null) i++;
        return type + "_" + i;
    }

    /** rebuild every dock from the model */
    public void refresh(){
        javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode("root");
        for(int i = 0; i < model.scene.root.childCount(); i++) fill(root, model.scene.root.child(i));
        tree.setModel(new javax.swing.tree.DefaultTreeModel(root));

        inspector.setRowCount(0);
        for(java.util.Map.Entry<String, Object> kv : model.properties(model.selected).entrySet())
            inspector.addRow(new Object[]{ kv.getKey(), String.valueOf(kv.getValue()) });

        consoleArea.setText(String.join("\n", model.console));
        files.clear();
        for(String f : model.projectFiles()) files.addElement(f);

        viewport.setFrame(model.renderPreview(480, 270));
        setTitle("HollowEngine Editor — " + model.projectDir.toAbsolutePath().getFileName()
                 + (model.selected.isEmpty() ? "" : "  [" + model.selected + "]"));
    }

    private void fill(javax.swing.tree.DefaultMutableTreeNode parent, Node n){
        javax.swing.tree.DefaultMutableTreeNode t = new javax.swing.tree.DefaultMutableTreeNode(n.name);
        parent.add(t);
        for(int i = 0; i < n.childCount(); i++) fill(t, n.child(i));
    }

    /** center dock: paints the last rendered frame, scaled to fit */
    private static final class ViewportPanel extends JPanel {
        private Image img;
        void setFrame(Frame f){
            if(f == null) return;
            BufferedImage bi = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, f.w, f.h, f.pixels, 0, f.w);
            img = bi;
            repaint();
        }
        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            if(img == null) return;
            double s = Math.min((double)getWidth() / img.getWidth(null), (double)getHeight() / img.getHeight(null));
            int w = (int)(img.getWidth(null) * s), h = (int)(img.getHeight(null) * s);
            g.drawImage(img, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
        }
    }

    /** launch on the EDT; throws HeadlessException on displays-less hosts */
    public static void launch(Path dir){
        java.awt.EventQueue.invokeLater(() -> new EditorApp(dir).setVisible(true));
    }
}
