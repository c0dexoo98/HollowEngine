package com.hollow.cli;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * v3.0.0 CLI 'pack' — package a project (or the engine runtime + project) into
 * a distributable .zip bundle:
 *
 *   pack -p demo-fps                          -> dist/demo-fps.zip (project only)
 *   pack -p demo-fps --with-runtime           -> + engine classes + launch scripts
 *   pack -p demo-fps --with-runtime --name mygame-1.0
 *
 * The bundle layout mirrors what the runtime expects:
 *   project.json, scenes/, assets/, strings/ ...
 *   (runtime) engine/ + cli/ class dirs + run.sh
 */
final class PackCmd {

    private PackCmd(){ }

    static int run(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        if(project == null){ System.err.println("pack: -p <project> required"); return 2; }
        boolean withRuntime = hasFlag(args, "--with-runtime");
        String name = null;
        for(int i = 1; i < args.length - 1; i++) if(args[i].equals("--name")) name = args[i + 1];

        File pdir = new File("projects/" + project);
        if(!pdir.isDirectory()) pdir = new File(project);
        String baseName = pdir.getName().isEmpty() ? project : pdir.getName();
        if(name == null) name = baseName + (withRuntime ? "-bundle" : "");
        if(!pdir.isDirectory()){ System.err.println("pack: project dir not found for " + project); return 2; }

        File dist = new File("dist");
        dist.mkdirs();
        File out = new File(dist, name + ".zip");
        if(out.exists()) out.delete();

        List<String> packed = new ArrayList<>();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(out))){
            zip.setLevel(Deflater.BEST_COMPRESSION);
            addDir(zip, pdir, "project/", packed);
            if(withRuntime) addRuntime(zip, packed);
        } catch(IOException e){
            System.err.println("pack failed: " + e);
            return 1;
        }
        long size = out.length();
        System.out.println("pack: " + packed.size() + " files -> " + out.getPath()
            + " (" + String.format("%.1f KB", size / 1024f) + ")" + (withRuntime ? " [with runtime]" : ""));
        return 0;
    }

    private static boolean hasFlag(String[] args, String flag){
        for(String a : args) if(a.equals(flag)) return true;
        return false;
    }

    private static void addDir(ZipOutputStream zip, File dir, String prefix, List<String> packed) throws IOException {
        File[] children = dir.listFiles();
        if(children == null) return;
        for(File f : children){
            if(f.getName().equals(".git") || f.getName().endsWith(".tmp")) continue;
            if(f.isDirectory()){
                addDir(zip, f, prefix + f.getName() + "/", packed);
            } else {
                zip.putNextEntry(new ZipEntry(prefix + f.getName()));
                Files.copy(f.toPath(), zip);
                zip.closeEntry();
                packed.add(prefix + f.getName());
            }
        }
    }

    /** embed the built engine/cli classes + a launcher script so the zip runs anywhere with java 21 */
    private static void addRuntime(ZipOutputStream zip, List<String> packed) throws IOException {
        String[] roots = { "build-out/engine", "build-out/cli", "scripts/build/engine", "scripts/build/cli" };
        String engineRoot = null, cliRoot = null;
        for(String r : roots){
            File f = new File(r);
            if(f.isDirectory()){
                if(r.endsWith("engine")) engineRoot = r;
                else cliRoot = r;
            }
        }
        if(engineRoot != null) addDir(zip, new File(engineRoot), "engine/", packed);
        if(cliRoot != null) addDir(zip, new File(cliRoot), "cli/", packed);

        // launcher
        StringBuilder sh = new StringBuilder();
        sh.append("#!/usr/bin/env bash\n");
        sh.append("# HollowEngine bundle launcher (java 21+)\n");
        sh.append("DIR=\"$(cd \"$(dirname \"$BASH_SOURCE\")\" && pwd)\"\n");
        sh.append("CP=\"$DIR/engine:$DIR/cli\"\n");
        sh.append("PROJ=\"$DIR/project\"\n");
        sh.append("exec java -cp \"$CP\" com.hollow.cli.Main run -p \"$PROJ\" \"$@\"\n");
        zip.putNextEntry(new ZipEntry("run.sh"));
        zip.write(sh.toString().getBytes("UTF-8"));
        zip.closeEntry();
        packed.add("run.sh");

        StringBuilder readme = new StringBuilder();
        readme.append("HollowEngine bundle\n===================\n");
        readme.append("Run:   bash run.sh\n");
        readme.append("Needs: Java 21+ on PATH\n");
        readme.append("Layout: engine/ cli/ (runtime classes), project/ (game data)\n");
        zip.putNextEntry(new ZipEntry("README.txt"));
        zip.write(readme.toString().getBytes("UTF-8"));
        zip.closeEntry();
        packed.add("README.txt");
    }
}
