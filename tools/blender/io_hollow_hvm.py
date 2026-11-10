bl_info = {
    "name": "HollowEngine HVM Exporter",
    "author": "HollowEngine",
    "version": (1, 0, 0),
    "blender": (3, 0, 0),
    "location": "File > Export > HollowEngine Mesh (.hvm)",
    "category": "Import-Export",
}
"""EXT-007 — exports the ACTIVE OBJECT's evaluated mesh to the engine's
binary .hvm format: int32 magic 'HVM1', int32 flags(bit0 normals, bit1 uvs),
int32 vertexCount, float32 positions, [float32 normals], [float32 uvs],
int32 indexCount, int32 indices (triangle list)."""

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
