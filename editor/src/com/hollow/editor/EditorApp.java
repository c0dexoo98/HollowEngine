package com.hollow.editor;

import com.hollow.render.Frame;
import com.hollow.scene.Node;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

/** HollowEngine 2 — the editor window (Godot-style docks):
 *  scene tree on the left, viewport in the center, inspector on the
 *  right, console + project files along the bottom. All actions
 *  delegate to {@link EditorModel}; this class is only the view.
 *  Viewport: click picks, drag moves the picked node, empty-drag
 *  orbits the editor camera, wheel zooms. F5 plays the game with
 *  real keyboard input; Ctrl+Z / Ctrl+Y undo and redo.
 *  Launch with `hollow editor` — needs a real display. */
public final class EditorApp extends JFrame {

    private final EditorModel model;
    private final JTree tree;
    private final DefaultTableModel inspector;
    private final JTextArea consoleArea;
    private final DefaultListModel<String> files;
    private final ViewportPanel viewport;
    private final Timer playTimer;
    private boolean draggingNode = false, orbiting = false;
    private int lastX, lastY;

    public EditorApp(Path dir){
        super("HollowEngine Editor — " + dir.toAbsolutePath().getFileName());
        this.model = new EditorModel(dir);
        model.useOrbitCamera = true;
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
        viewport.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){
                lastX = e.getX(); lastY = e.getY();
                java.awt.Point p = viewport.toFrameCoords(e.getX(), e.getY());
                if(p != null && model.pickAt(p.x, p.y, 480, 270)){
                    model.beginDrag();
                    draggingNode = true;
                } else {
                    orbiting = true;
                }
                refresh();
            }
            @Override public void mouseReleased(MouseEvent e){
                draggingNode = false;
                orbiting = false;
            }
        });
        viewport.addMouseMotionListener(new java.awt.event.MouseMotionAdapter(){
            @Override public void mouseDragged(MouseEvent e){
                int dx = e.getX() - lastX, dy = e.getY() - lastY;
                lastX = e.getX(); lastY = e.getY();
                if(draggingNode){
                    model.dragSelected(dx, dy, 480, 270);
                    viewport.setFrame(model.renderPreview(480, 270));
                    refreshDocksOnly();
                } else if(orbiting){
                    model.orbit(dx * 0.4f, dy * 0.4f);
                    viewport.setFrame(model.renderPreview(480, 270));
                }
            }
        });
        viewport.addMouseWheelListener((MouseWheelEvent e) -> {
            model.zoom(e.getWheelRotation() < 0 ? 1.15f : 1f / 1.15f);
            viewport.setFrame(model.renderPreview(480, 270));
        });

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

        playTimer = new Timer(33, e -> {                 // ~30 fps simulated
            if(!model.isPlaying()) return;
            model.playTick(1.0 / 30);
            viewport.setFrame(model.renderPreview(480, 270));
            refreshDocksOnly();
        });

        bindKeys();
        setJMenuBar(menu());
        setContentPane(outer);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1280, 800);
        setLocationRelativeTo(null);
        refresh();
    }

    /** F5 = play/stop, Ctrl+Z/Y = undo/redo, arrows/WASD = on_input while playing */
    private void bindKeys(){
        JRootPane rp = getRootPane();
        bind(rp, "control Z", () -> { model.undo(); refresh(); });
        bind(rp, "control Y", () -> { model.redo(); refresh(); });
        bind(rp, "F5", this::togglePlay);
        String[][] keys = {
            { "UP", "up" }, { "DOWN", "down" }, { "LEFT", "left" }, { "RIGHT", "right" },
            { "W", "up" }, { "S", "down" }, { "A", "left" }, { "D", "right" },
            { "SPACE", "space" }
        };
        for(String[] k : keys) bind(rp, k[0], () -> model.playInput(k[1]));
    }

    private void bind(JRootPane rp, String stroke, Runnable action){
        rp.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
          .put(javax.swing.KeyStroke.getKeyStroke(stroke), stroke);
        rp.getActionMap().put(stroke, new javax.swing.AbstractAction(){
            @Override public void actionPerformed(java.awt.event.ActionEvent e){ action.run(); }
        });
    }

    private void togglePlay(){
        if(model.isPlaying()){
            playTimer.stop();
            model.playStop();
        } else {
            model.playStart();
            playTimer.start();
        }
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

        JMenu edit = new JMenu("Edit");
        item(edit, "Undo", e -> { model.undo(); refresh(); });
        item(edit, "Redo", e -> { model.redo(); refresh(); });
        bar.add(edit);

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
        item(build, "Play / Stop  (F5)", e -> togglePlay());
        item(build, "Run 60 Frames (headless)", e -> {
            model.runLog.setLength(0);
            model.run(60, 1.0 / 60, new ArrayList<>());
            refresh();
        });
        item(build, "Render Frame (PNG)", e -> {
            Frame f = model.renderPreview(960, 540);
            f.writePNG(model.projectDir.resolve("frame.png"));
            model.console.add("wrote frame.png (" + f.fillRatio() + " filled)");
            viewport.setFrame(f);
            refresh();
        });
        bar.add(build);

        JMenu view = new JMenu("View");
        JCheckBoxMenuItem orbit = new JCheckBoxMenuItem("Editor orbit camera", model.useOrbitCamera);
        orbit.addActionListener(e -> {
            model.useOrbitCamera = orbit.isSelected();
            refresh();
        });
        view.add(orbit);
        bar.add(view);

        JMenu help = new JMenu("Help");
        item(help, "About", e -> JOptionPane.showMessageDialog(this,
            "HollowEngine 2.1\nA from-scratch game engine: HollowScript classes,\nscene graph, physics, shadows, editor with play mode.",
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
                 + (model.selected.isEmpty() ? "" : "  [" + model.selected + "]")
                 + (model.isPlaying() ? "  ▶ PLAYING" : ""));
    }

    /** lightweight refresh during interaction: inspector + console + title only */
    private void refreshDocksOnly(){
        inspector.setRowCount(0);
        for(java.util.Map.Entry<String, Object> kv : model.properties(model.selected).entrySet())
            inspector.addRow(new Object[]{ kv.getKey(), String.valueOf(kv.getValue()) });
        consoleArea.setText(String.join("\n", model.console));
        setTitle("HollowEngine Editor — " + model.projectDir.toAbsolutePath().getFileName()
                 + (model.selected.isEmpty() ? "" : "  [" + model.selected + "]")
                 + (model.isPlaying() ? "  ▶ PLAYING" : ""));
    }

    private void fill(javax.swing.tree.DefaultMutableTreeNode parent, Node n){
        javax.swing.tree.DefaultMutableTreeNode t = new javax.swing.tree.DefaultMutableTreeNode(n.name);
        parent.add(t);
        for(int i = 0; i < n.childCount(); i++) fill(t, n.child(i));
    }

    /** center dock: paints the last rendered frame, scaled to fit;
     *  maps mouse coords back to frame pixels for picking */
    private static final class ViewportPanel extends JPanel {
        private Image img;
        private int fw, fh, ox, oy, dw, dh;

        void setFrame(Frame f){
            if(f == null) return;
            BufferedImage bi = new BufferedImage(f.w, f.h, BufferedImage.TYPE_INT_ARGB);
            bi.setRGB(0, 0, f.w, f.h, f.pixels, 0, f.w);
            img = bi;
            fw = f.w; fh = f.h;
            repaint();
        }

        java.awt.Point toFrameCoords(int mx, int my){
            if(img == null) return null;
            int fx = (mx - ox) * fw / Math.max(1, dw);
            int fy = (my - oy) * fh / Math.max(1, dh);
            if(fx < 0 || fy < 0 || fx >= fw || fy >= fh) return null;
            return new java.awt.Point(fx, fy);
        }

        @Override protected void paintComponent(Graphics g){
            super.paintComponent(g);
            if(img == null) return;
            double s = Math.min((double)getWidth() / img.getWidth(null), (double)getHeight() / img.getHeight(null));
            dw = (int)(img.getWidth(null) * s);
            dh = (int)(img.getHeight(null) * s);
            ox = (getWidth() - dw) / 2;
            oy = (getHeight() - dh) / 2;
            g.drawImage(img, ox, oy, dw, dh, null);
        }
    }

    /** launch on the EDT; throws HeadlessException on displays-less hosts */
    public static void launch(Path dir){
        java.awt.EventQueue.invokeLater(() -> new EditorApp(dir).setVisible(true));
    }
}
