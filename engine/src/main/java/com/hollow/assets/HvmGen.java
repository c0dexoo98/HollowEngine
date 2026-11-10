package com.hollow.assets;

/** Procedural primitive mesh generators (engine's built-in "geometry tool"). */
public final class HvmGen {
    private HvmGen() {}

    // ---------------- box ----------------
    public static MeshData box(float w, float h, float d){
        return box(w, h, d, true);
    }
    public static MeshData box(float w, float h, float d, boolean centered){
        float x0 = centered ? -w/2 : 0, x1 = centered ? w/2 : w;
        float y0 = centered ? -h/2 : 0, y1 = centered ? h/2 : h;
        float z0 = centered ? -d/2 : 0, z1 = centered ? d/2 : d;
        // 6 faces, 4 verts each
        float[] pos = new float[24*3], nrm = new float[24*3], uv = new float[24*2];
        int[] idx = new int[36];
        int[][] faces = {
            {0,0,1},{0,0,-1},{1,0,0},{-1,0,0},{0,1,0},{0,-1,0}
        };
        int vi = 0, ii = 0;
        for(int f=0; f<6; f++){
            float[] n = {faces[f][0], faces[f][1], faces[f][2]};
            // build face tangent space
            float[] t = n[0]!=0 ? new float[]{0,1,0} : new float[]{1,0,0};
            float[] b = cross(n, t);
            // 4 corners (CCW from outside)
            float[][] corners = {
                add(add(scale(n, 0.5f), scale(t,-0.5f)), scale(b,-0.5f)),
                add(add(scale(n, 0.5f), scale(t, 0.5f)), scale(b,-0.5f)),
                add(add(scale(n, 0.5f), scale(t, 0.5f)), scale(b, 0.5f)),
                add(add(scale(n, 0.5f), scale(t,-0.5f)), scale(b, 0.5f)),
            };
            float[][] uvs = {{0,1},{1,1},{1,0},{0,0}};
            for(int c=0;c<4;c++){
                float px = x0 + (x1-x0)*(corners[c][0]+0.5f);
                float py = y0 + (y1-y0)*(corners[c][1]+0.5f);
                float pz = z0 + (z1-z0)*(corners[c][2]+0.5f);
                pos[vi*3]=px; pos[vi*3+1]=py; pos[vi*3+2]=pz;
                nrm[vi*3]=n[0]; nrm[vi*3+1]=n[1]; nrm[vi*3+2]=n[2];
                uv[vi*2]=uvs[c][0]; uv[vi*2+1]=uvs[c][1];
                vi++;
            }
            idx[ii++]=f*4+0; idx[ii++]=f*4+1; idx[ii++]=f*4+2;
            idx[ii++]=f*4+0; idx[ii++]=f*4+2; idx[ii++]=f*4+3;
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv; m.indices=idx;
        return m;
    }

    // ---------------- plane (XZ, +Y up) ----------------
    public static MeshData plane(float size, int segments){
        int seg = Math.max(1, segments);
        int vCount = (seg+1)*(seg+1);
        float[] pos = new float[vCount*3], nrm = new float[vCount*3], uv = new float[vCount*2];
        int[] idx = new int[seg*seg*6];
        int vi=0, ii=0;
        for(int z=0;z<=seg;z++){
            for(int x=0;x<=seg;x++){
                float u = x/(float)seg, v = z/(float)seg;
                pos[vi*3] = (u-0.5f)*size;
                pos[vi*3+1] = 0;
                pos[vi*3+2] = (v-0.5f)*size;
                nrm[vi*3]=0; nrm[vi*3+1]=1; nrm[vi*3+2]=0;
                uv[vi*2]=u; uv[vi*2+1]=v;
                vi++;
            }
        }
        for(int z=0;z<seg;z++){
            for(int x=0;x<seg;x++){
                int a=z*(seg+1)+x, b=a+1, c=a+seg+1, d=c+1;
                idx[ii++]=a; idx[ii++]=c; idx[ii++]=b;
                idx[ii++]=b; idx[ii++]=c; idx[ii++]=d;
            }
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv; m.indices=idx;
        return m;
    }

    // ---------------- sphere ----------------
    public static MeshData sphere(float radius, int lat, int lon){
        lat = Math.max(3, lat); lon = Math.max(3, lon);
        int vCount = (lat+1)*(lon+1);
        float[] pos = new float[vCount*3], nrm = new float[vCount*3], uv = new float[vCount*2];
        int[] idx = new int[lat*lon*6];
        int vi=0, ii=0;
        for(int la=0; la<=lat; la++){
            float theta = la*(float)Math.PI/lat;           // 0..PI
            float y = (float)Math.cos(theta);
            for(int lo=0; lo<=lon; lo++){
                float phi = lo*2f*(float)Math.PI/lon;
                float r = (float)Math.sin(theta);
                float x = r*(float)Math.cos(phi), z = r*(float)Math.sin(phi);
                pos[vi*3]=x*radius; pos[vi*3+1]=y*radius; pos[vi*3+2]=z*radius;
                nrm[vi*3]=x; nrm[vi*3+1]=y; nrm[vi*3+2]=z;
                uv[vi*2]=lo/(float)lon; uv[vi*2+1]=la/(float)lat;
                vi++;
            }
        }
        for(int la=0; la<lat; la++){
            for(int lo=0; lo<lon; lo++){
                int a=la*(lon+1)+lo, b=a+lon+1;
                // CCW when viewed from outside (glTF/OpenGL front-face convention)
                idx[ii++]=a; idx[ii++]=a+1; idx[ii++]=b;
                idx[ii++]=a+1; idx[ii++]=b+1; idx[ii++]=b;
            }
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv; m.indices=idx;
        return m;
    }

    // ---------------- capsule (Y-aligned) ----------------
    public static MeshData capsule(float radius, float height, int seg){
        // cylinder body + hemisphere caps; height = total including caps
        float bodyH = Math.max(0.01f, height - 2*radius);
        int lat = Math.max(4, seg), lon = Math.max(8, seg*2);
        int rows = lat*2;                       // top cap + bottom cap rings
        int vCount = (rows+1)*(lon+1) + 2;
        float[] pos = new float[vCount*3], nrm = new float[vCount*3], uv = new float[vCount*2];
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        int vi = 0;
        // rings: v in [0,1] maps from top pole to bottom pole
        for(int r=0; r<=rows; r++){
            float v = r/(float)rows;
            // piecewise: top cap (sphere) / cylinder body / bottom cap (sphere)
            float cy, cr;
            if(v < 0.25f){            // top cap: theta 0..PI/2
                float t = v/0.25f * (float)Math.PI/2;
                cy = (float)Math.cos(t) * radius + bodyH/2;
                cr = (float)Math.sin(t) * radius;
            } else if(v > 0.75f){     // bottom cap
                float t = (1-(v-0.75f)/0.25f) * (float)Math.PI/2;
                cy = -(float)Math.cos(t) * radius - bodyH/2;
                cr = (float)Math.sin(t) * radius;
            } else {                  // body
                float t = (v-0.25f)/0.5f;
                cy = (0.5f - t) * bodyH;
                cr = radius;
            }
            for(int lo=0; lo<=lon; lo++){
                float phi = lo*2f*(float)Math.PI/lon;
                float x = cr*(float)Math.cos(phi), z = cr*(float)Math.sin(phi);
                float nx = x, ny = (cy > 0 ? Math.max(cy - bodyH/2, 0) : Math.min(cy + bodyH/2, 0)), nz = z;
                float nl = (float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                if(nl < 1e-6f){ nx=0; ny=1; nz=0; nl=1; }
                pos[vi*3]=x; pos[vi*3+1]=cy; pos[vi*3+2]=z;
                nrm[vi*3]=nx/nl; nrm[vi*3+1]=ny/nl; nrm[vi*3+2]=nz/nl;
                uv[vi*2]=lo/(float)lon; uv[vi*2+1]=v;
                vi++;
            }
        }
        for(int r=0; r<rows; r++){
            for(int lo=0; lo<lon; lo++){
                int a=r*(lon+1)+lo, b=a+lon+1;
                // CCW when viewed from outside (glTF/OpenGL front-face convention)
                indices.add(a); indices.add(a+1); indices.add(b);
                indices.add(a+1); indices.add(b+1); indices.add(b);
            }
        }
        // poles
        int top = vi, bot = vi+1;
        pos[top*3]=0; pos[top*3+1]=bodyH/2+radius; pos[top*3+2]=0;
        nrm[top*3]=0; nrm[top*3+1]=1; nrm[top*3+2]=0;
        uv[top*2]=0.5f; uv[top*2+1]=0;
        pos[bot*3]=0; pos[bot*3+1]=-bodyH/2-radius; pos[bot*3+2]=0;
        nrm[bot*3]=0; nrm[bot*3+1]=-1; nrm[bot*3+2]=0;
        uv[bot*2]=0.5f; uv[bot*2+1]=1;
        for(int lo=0; lo<lon; lo++){
            indices.add(top); indices.add(lo+1); indices.add(lo);
            int base = rows*(lon+1);
            indices.add(bot); indices.add(base+lo); indices.add(base+lo+1);
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv;
        m.indices = indices.stream().mapToInt(Integer::intValue).toArray();
        return m;
    }

    // ---------------- cylinder ----------------
    public static MeshData cylinder(float radius, float height, int seg){
        seg = Math.max(3, seg);
        int ring = seg+1;
        int vCount = ring*4 + 2;                // 2 side rings + 2 cap rings + 2 centers
        float[] pos = new float[vCount*3], nrm = new float[vCount*3], uv = new float[vCount*2];
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        float h = height/2;
        int vi=0;
        // side rings (radial normals)
        for(int side=0; side<2; side++){
            float y = side==0 ? h : -h;
            for(int lo=0; lo<=seg; lo++){
                float phi = lo*2f*(float)Math.PI/seg;
                float x = (float)Math.cos(phi)*radius, z = (float)Math.sin(phi)*radius;
                pos[vi*3]=x; pos[vi*3+1]=y; pos[vi*3+2]=z;
                float l=(float)Math.sqrt(x*x+z*z);
                nrm[vi*3]= l>0 ? x/l : 1f; nrm[vi*3+1]=0; nrm[vi*3+2]= l>0 ? z/l : 0f;
                uv[vi*2]=lo/(float)seg; uv[vi*2+1]=side;
                vi++;
            }
        }
        // cap rings (flat normals)
        for(int side=0; side<2; side++){
            float y = side==0 ? h : -h;
            for(int lo=0; lo<=seg; lo++){
                float phi = lo*2f*(float)Math.PI/seg;
                float x = (float)Math.cos(phi)*radius, z = (float)Math.sin(phi)*radius;
                pos[vi*3]=x; pos[vi*3+1]=y; pos[vi*3+2]=z;
                nrm[vi*3]=0; nrm[vi*3+1]=side==0?1:-1; nrm[vi*3+2]=0;
                uv[vi*2]=0.5f+ (float)Math.cos(phi)*0.5f; uv[vi*2+1]=0.5f+(float)Math.sin(phi)*0.5f;
                vi++;
            }
        }
        // cap centers
        int centerTop = vi++;
        pos[centerTop*3]=0; pos[centerTop*3+1]=h; pos[centerTop*3+2]=0;
        nrm[centerTop*3]=0; nrm[centerTop*3+1]=1; nrm[centerTop*3+2]=0;
        uv[centerTop*2]=0.5f; uv[centerTop*2+1]=0.5f;
        int centerBot = vi++;
        pos[centerBot*3]=0; pos[centerBot*3+1]=-h; pos[centerBot*3+2]=0;
        nrm[centerBot*3]=0; nrm[centerBot*3+1]=-1; nrm[centerBot*3+2]=0;
        uv[centerBot*2]=0.5f; uv[centerBot*2+1]=0.5f;
        int topRing = ring*2, botRing = ring*3;
        for(int lo=0; lo<seg; lo++){
            int a=lo, b=lo+1, c=ring+lo, d=ring+lo+1;
            // side quad: CCW when viewed from outside
            indices.add(a); indices.add(b); indices.add(c);
            indices.add(b); indices.add(d); indices.add(c);
            // cap fans: top +Y, bottom -Y
            indices.add(centerTop); indices.add(topRing+lo+1); indices.add(topRing+lo);
            indices.add(centerBot); indices.add(botRing+lo); indices.add(botRing+lo+1);
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv;
        m.indices = indices.stream().mapToInt(Integer::intValue).toArray();
        return m;
    }

    // ---------------- terrain from heights ----------------
    public static MeshData gridTerrain(float[][] heights, float worldSize, float heightScale){
        int rows = heights.length, cols = heights[0].length;
        float[] pos = new float[rows*cols*3], nrm = new float[rows*cols*3], uv = new float[rows*cols*2];
        int[] idx = new int[(rows-1)*(cols-1)*6];
        int vi=0, ii=0;
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                float u = c/(float)(cols-1), v = r/(float)(rows-1);
                pos[vi*3] = (u-0.5f)*worldSize;
                pos[vi*3+1] = heights[r][c]*heightScale;
                pos[vi*3+2] = (v-0.5f)*worldSize;
                uv[vi*2]=u; uv[vi*2+1]=v;
                vi++;
            }
        }
        // normals via central differences
        for(int r=0;r<rows;r++){
            for(int c=0;c<cols;c++){
                float hl = heights[r][Math.max(0,c-1)]*heightScale;
                float hr = heights[r][Math.min(cols-1,c+1)]*heightScale;
                float hd = heights[Math.max(0,r-1)][c]*heightScale;
                float hu = heights[Math.min(rows-1,r+1)][c]*heightScale;
                float dx = (hr-hl) * worldSize/(cols-1) * 0.5f;
                float dz = (hu-hd) * worldSize/(rows-1) * 0.5f;
                com.hollow.core.Vec3 n = new com.hollow.core.Vec3(-dx, 1, -dz).norm();
                int i = r*cols+c;
                nrm[i*3]=n.x; nrm[i*3+1]=n.y; nrm[i*3+2]=n.z;
            }
        }
        for(int r=0;r<rows-1;r++){
            for(int c=0;c<cols-1;c++){
                int a=r*cols+c, b=a+1, d=a+cols, e=d+1;
                idx[ii++]=a; idx[ii++]=d; idx[ii++]=b;
                idx[ii++]=b; idx[ii++]=d; idx[ii++]=e;
            }
        }
        MeshData m = new MeshData();
        m.positions=pos; m.normals=nrm; m.uvs=uv; m.indices=idx;
        return m;
    }

    // ---- helpers ----
    private static float[] add(float[] a, float[] b){ return new float[]{a[0]+b[0], a[1]+b[1], a[2]+b[2]}; }
    private static float[] scale(float[] a, float s){ return new float[]{a[0]*s, a[1]*s, a[2]*s}; }
    private static float[] cross(float[] a, float[] b){
        return new float[]{ a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0] };
    }
}
