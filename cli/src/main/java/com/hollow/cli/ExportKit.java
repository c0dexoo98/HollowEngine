package com.hollow.cli;

import com.hollow.core.Json;
import com.hollow.core.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** v2.5 EXT batch — EXT-005/006/007/010 (+EXT-008 LSP server & EXT-009 profiler
 *  clients live in Main/Commands). File/template generators for distribution. */
public final class ExportKit {
    private ExportKit() {}

    // =====================================================================
    // EXT-005 — itch.io export (zip + page template)
    // =====================================================================
    public static String itchExport(String projectDir, String apkPath, String title) throws Exception{
        Path outDir = Path.of(projectDir, "build", "itch");
        Files.createDirectories(outDir);
        String safeTitle = (title == null || title.isBlank() ? new File(projectDir).getName() : title)
            .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        Path zipPath = outDir.resolve(safeTitle + "-itch.zip");
        List<Path> inputs = new ArrayList<>();
        if(apkPath != null && Files.exists(Path.of(apkPath))) inputs.add(Path.of(apkPath));
        // bundle project metadata + assets the web player could serve
        Path assets = Path.of(projectDir, "assets");
        if(Files.isDirectory(assets)) try (var walk = Files.walk(assets)){
            walk.filter(Files::isRegularFile).limit(2000).forEach(inputs::add);
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))){
            for(Path p : inputs){
                String entryName = p.toString();
                if(entryName.startsWith(projectDir) && entryName.contains("/")){
                    entryName = entryName.substring(entryName.indexOf('/') + 1);   // strip "projects/"
                }
                zip.putNextEntry(new ZipEntry(entryName));
                try (FileInputStream in = new FileInputStream(p.toFile())){
                    in.transferTo(zip);
                }
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("itch-page.html"));
            zip.write(itchPageTemplate(title).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("itch-page.md"));
            zip.write(itchMarkdownTemplate(title).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        System.out.println("itch.io export → " + zipPath + " (" + Files.size(zipPath) / 1024 + " KB, " + inputs.size() + " files)");
        return zipPath.toString();
    }

    static String itchPageTemplate(String title){
        String t = title == null ? "HollowEngine Game" : title;
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head><meta charset="utf-8"><title>%s</title>
            <style>
              body { margin:0; background:#0b0d12; color:#e6e9ef; font-family:sans-serif; }
              .hero { padding:64px 32px; background:linear-gradient(160deg,#141821,#1d2430); }
              .buy { background:#fa5c5c; color:#fff; padding:12px 28px; border-radius:6px; text-decoration:none; font-weight:bold; }
              .shots img { max-width:420px; margin:8px; border-radius:4px; }
            </style></head>
            <body>
              <div class="hero">
                <h1>%s</h1>
                <p>Built with <b>HollowEngine</b> — pure-Java game engine for Android.</p>
                <a class="buy" href="#">Download or Pay What You Want</a>
              </div>
              <div class="shots"><!-- screenshots --></div>
              <h2>Description</h2>
              <p><!-- your description --></p>
            </body></html>
            """.formatted(t, t);
    }

    static String itchMarkdownTemplate(String title){
        String t = title == null ? "HollowEngine Game" : title;
        return """
            # %s

            Built with **HollowEngine**.

            ## Install
            1. Download the APK below.
            2. Enable "Install unknown apps" for your file manager.
            3. Install and play.

            ## Controls
            - Touch: move / look
            - Gamepad supported (Bluetooth)

            <!-- itch.io "Description" field, markdown allowed -->
            """.formatted(t);
    }

    // =====================================================================
    // EXT-006 — GitHub Actions CI template
    // =====================================================================
    public static String writeCiTemplate(String projectDir) throws Exception{
        Path dir = Path.of(projectDir, ".github", "workflows");
        Files.createDirectories(dir);
        Path yml = dir.resolve("hollow.yml");
        String content = """
            name: hollow-ci
            on:
              push: { branches: [main, master] }
              pull_request:
            jobs:
              build-test:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with: { distribution: temurin, java-version: '17' }
                  - name: Build engine + CLI (desktop profile)
                    run: ./gradlew :cli:jar -Phe.skipAndroid=true --no-daemon
                  - name: Engine self test
                    run: java -jar cli/build/libs/cli.jar selftest
                  - name: Validate projects
                    run: java -jar cli/build/libs/cli.jar validate -p demo
            """;
        Files.writeString(yml, content);
        System.out.println("CI template → " + yml);
        return yml.toString();
    }

    // =====================================================================
    // EXT-007 — Blender addon (exports .hvm directly)
    // =====================================================================
    public static String writeBlenderAddon(String toolsDir) throws Exception{
        Path dir = Path.of(toolsDir, "blender");
        Files.createDirectories(dir);
        Path py = dir.resolve("io_hollow_hvm.py");
        String content = """
            bl_info = {
                "name": "HollowEngine HVM Exporter",
                "author": "HollowEngine",
                "version": (1, 0, 0),
                "blender": (3, 0, 0),
                "location": "File > Export > HollowEngine Mesh (.hvm)",
                "category": "Import-Export",
            }
            \"\"\"EXT-007 — exports the ACTIVE OBJECT's evaluated mesh to the engine's
            binary .hvm format: int32 magic 'HVM1', int32 flags(bit0 normals, bit1 uvs),
            int32 vertexCount, float32 positions, [float32 normals], [float32 uvs],
            int32 indexCount, int32 indices (triangle list).\"\"\"

            import bpy, struct
            from mathutils import Vector

            HVM_MAGIC = 0x48564D31  # 'HVM1'

            def write_hvm(context, filepath):
                obj = context.active_object
                if obj is None or obj.type != 'MESH':
                    raise RuntimeError("select a mesh object first")
                deps = context.evaluated_depsgraph_get()
                me = obj.evaluated_get(deps).to_mesh()
                me.calc_loop_triangles()
                me.calc_normals_split()

                verts = [tuple(obj.matrix_world @ v.co) for v in me.vertices]
                tris = []
                for tri in me.loop_triangles:
                    tris.append((tri.vertices[0], tri.vertices[1], tri.vertices[2]))

                with open(filepath, "wb") as f:
                    f.write(struct.pack("<i", HVM_MAGIC))
                    flags = 1 | 2  # normals + uvs
                    f.write(struct.pack("<i", flags))
                    f.write(struct.pack("<i", len(verts)))
                    for v in verts:
                        f.write(struct.pack("<fff", v[0], v[1], v[2]))
                    for poly in me.polygons:
                        n = poly.normal
                        for _ in range(3):
                            f.write(struct.pack("<fff", n.x, n.y, n.z))
                    uv_layer = me.uv_layers.active.data if me.uv_layers.active else None
                    for tri in me.loop_triangles:
                        for li in tri.loops:
                            uv = uv_layer[li].uv if uv_layer else (0.0, 0.0)
                            f.write(struct.pack("<ff", uv[0], uv[1]))
                    f.write(struct.pack("<i", len(tris) * 3))
                    for tri in tris:
                        f.write(struct.pack("<iii", *tri))
                return {'FINISHED'}

            class ExportHVM(bpy.types.Operator):
                bl_idname = "export_scene.hvm"
                bl_label = "Export HollowEngine Mesh"
                filepath: bpy.props.StringProperty(subtype="FILE_PATH")

                def execute(self, context):
                    return write_hvm(context, self.filepath)
                def invoke(self, context, event):
                    context.window_manager.fileselect_add(self)
                    return {'RUNNING_MODAL'}

            def menu_func(self, context):
                self.layout.operator(ExportHVM.bl_idname, text="HollowEngine Mesh (.hvm)")

            def register():
                bpy.utils.register_class(ExportHVM)
                bpy.types.TOPBAR_MT_file_export.append(menu_func)
            def unregister():
                bpy.types.TOPBAR_MT_file_export.remove(menu_func)
                bpy.utils.unregister_class(ExportHVM)

            if __name__ == "__main__":
                register()
            """;
        Files.writeString(py, content);
        System.out.println("Blender addon → " + py + " (install via Edit > Preferences > Add-ons)");
        return py.toString();
    }

    // =====================================================================
    // EXT-010 — distributable single jar
    // =====================================================================
    public static String packSingleJar(String rootDir) throws Exception{
        Path dist = Path.of(rootDir, "dist");
        Files.createDirectories(dist);
        Path jarPath = dist.resolve("hollow.jar");
        Path engineClasses = Path.of(rootDir, "engine", "build", "classes", "java", "main");
        Path cliClasses = Path.of(rootDir, "cli", "build", "classes", "java", "main");
        List<Path[]> pairs = new ArrayList<>();   // {classFile, baseDir}
        for(Path cd : List.of(engineClasses, cliClasses)){
            if(!Files.isDirectory(cd)) continue;
            try (var walk = Files.walk(cd)){
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> pairs.add(new Path[]{ p, cd }));
            }
        }
        if(pairs.isEmpty())
            throw new Main.CliError("no compiled classes found — run: ./gradlew :cli:compileJava -Phe.skipAndroid=true");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jarPath.toFile()))){
            java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream();
            mb.write("Manifest-Version: 1.0\r\n".getBytes(StandardCharsets.UTF_8));
            mb.write("Main-Class: com.hollow.cli.Main\r\n".getBytes(StandardCharsets.UTF_8));
            mb.write("\r\n".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write(mb.toByteArray());
            zip.closeEntry();
            for(Path[] pair : pairs){
                zip.putNextEntry(new ZipEntry(relativize(pair[1], pair[0])));
                Files.copy(pair[0], zip);
                zip.closeEntry();
            }
        }
        // launcher scripts
        Files.writeString(dist.resolve("hollow.sh"),
            "#!/usr/bin/env bash\njava -jar \"$(dirname \"$0\")/hollow.jar\" \"$@\"\n");
        Files.writeString(dist.resolve("hollow.bat"),
            "@echo off\r\njava -jar \"%~dp0hollow.jar\" %*\r\n");
        System.out.println("single jar → " + jarPath + " (" + pairs.size() + " classes)");
        System.out.println("run: dist/hollow.sh version");
        return jarPath.toString();
    }

    static String relativize(Path base, Path file){
        return base.relativize(file).toString().replace('\\', '/');
    }
}
