package com.hollow.cli;

import com.hollow.core.NativeFS;

import java.io.File;

/** `hollow pose` — ANI-025: sample an animation clip at time t and dump joint
 *  transforms as JSON. Uses the project's first skinned model with clips; a
 *  built-in synthetic rig is used when the project has none (useful for tests).
 */
final class PoseCmd {
    private PoseCmd() {}

    static void run(String[] args){
        String project = Main.arg(args, "-p", "project", 1);
        float t = 0f;
        String tStr = Main.opt(args, "-t", "0");
        try { t = Float.parseFloat(tStr); } catch(Exception ignored){ t = 0f; }
        String clipName = Main.opt(args, "-c", null);
        int maxJoints = 64;
        String mj = Main.opt(args, "--max", "64");
        try { maxJoints = Math.max(1, Integer.parseInt(mj)); } catch(Exception ignored){}

        com.hollow.assets.Skeleton skel = null;
        com.hollow.assets.AnimClip clip = null;

        if(project != null && new File("projects/" + project).isDirectory()){
            NativeFS fs = Commands.projectFS(project);
            com.hollow.assets.AssetDB assets = new com.hollow.assets.AssetDB(fs, project);
            // scan imported models for one with skeleton + clips
            File assetsDir = new File("projects/" + project + "/assets");
            File[] hvm = assetsDir.listFiles((d, n) -> n.endsWith(".hvm"));
            if(hvm != null){
                for(File f : hvm){
                    try {
                        com.hollow.assets.ModelAsset m = assets.model("assets/" + f.getName());
                        if(m != null && !m.skins.isEmpty() && !m.clips.isEmpty()){
                            skel = m.skins.get(0);
                            if(clipName != null && m.clips.containsKey(clipName)) clip = m.clips.get(clipName);
                            else { var it = m.clips.values().iterator(); clip = it.next(); }
                            break;
                        }
                    } catch(Exception ignored){}
                }
            }
        }

        if(skel == null || clip == null){
            // synthetic demo rig: root -> spine -> head, 2s walk-in-place clip
            skel = syntheticSkeleton();
            clip = syntheticClip(skel);
            if(clipName != null) clip.name = clipName;
        }
        t = com.hollow.core.MathX.clamp(t, 0f, Math.max(0.01f, clip.duration - 1e-3f));
        String json = com.hollow.anim.AnimationV23.PoseDumper.dump(clip, skel, t, maxJoints);
        System.out.println(json);
        System.out.println("RESULT: pose '" + clip.name + "' @ " + String.format("%.3f", t)
            + "s (" + skel.jointCount() + " joints)");
    }

    static com.hollow.assets.Skeleton syntheticSkeleton(){
        com.hollow.assets.Skeleton s = new com.hollow.assets.Skeleton();
        com.hollow.assets.Skeleton.Joint root = new com.hollow.assets.Skeleton.Joint();
        root.name = "root"; root.parent = -1;
        root.restPos.set(0, 0, 0);
        root.restRot.set(0, 0, 0, 1);
        s.joints.add(root);
        com.hollow.assets.Skeleton.Joint spine = new com.hollow.assets.Skeleton.Joint();
        spine.name = "spine"; spine.parent = 0;
        spine.restPos.set(0, 0.9f, 0);
        spine.restRot.set(0, 0, 0, 1);
        s.joints.add(spine);
        com.hollow.assets.Skeleton.Joint head = new com.hollow.assets.Skeleton.Joint();
        head.name = "head"; head.parent = 1;
        head.restPos.set(0, 0.45f, 0);
        head.restRot.set(0, 0, 0, 1);
        s.joints.add(head);
        return s;
    }

    static com.hollow.assets.AnimClip syntheticClip(com.hollow.assets.Skeleton skel){
        com.hollow.assets.AnimClip c = new com.hollow.assets.AnimClip();
        c.name = "demo_bob";
        c.duration = 2f;
        c.loop = true;
        // root: slight bob
        com.hollow.assets.AnimClip.Channel rc = new com.hollow.assets.AnimClip.Channel();
        rc.times = new float[]{ 0f, 1f, 2f };
        rc.translations = new float[]{ 0,0,0,  0,0.08f,0,  0,0,0 };
        c.channels.put(0, rc);
        // spine: sway rotation
        com.hollow.assets.AnimClip.Channel sc = new com.hollow.assets.AnimClip.Channel();
        sc.times = new float[]{ 0f, 1f, 2f };
        float[] sway = new float[12];
        float[] q0 = quatY(0f), q1 = quatY(8f), q2 = quatY(0f);
        System.arraycopy(q0, 0, sway, 0, 4);
        System.arraycopy(q1, 0, sway, 4, 4);
        System.arraycopy(q2, 0, sway, 8, 4);
        sc.rotations = sway;
        c.channels.put(1, sc);
        // head: counter-sway
        com.hollow.assets.AnimClip.Channel hc = new com.hollow.assets.AnimClip.Channel();
        hc.times = new float[]{ 0f, 1f, 2f };
        float[] hs = new float[12];
        System.arraycopy(q0, 0, hs, 0, 4);
        System.arraycopy(quatY(-6f), 0, hs, 4, 4);
        System.arraycopy(q2, 0, hs, 8, 4);
        hc.rotations = hs;
        c.channels.put(2, hc);
        return c;
    }

    private static float[] quatY(float deg){
        float half = (float)Math.toRadians(deg) * 0.5f;
        return new float[]{ 0f, (float)Math.sin(half), 0f, (float)Math.cos(half) };
    }
}
