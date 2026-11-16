# Orb Runner — HollowEngine 2 sample game.
# Shows the full engine: classes, physics (gravity + collisions),
# spawning, input, shadows and fog.
#
# Headless (`hollow run`) the player chases orbs automatically so the
# demo is deterministic. In the editor (F5) arrows/WASD move the
# player and on_input drives it.

srand(7)

class Orb:
    var value = 1
    var node_name = ""

    func _init(node_name):
        self.node_name = node_name

var orbs = []
var score = 0
var tick = 0
var next_spawn = 30
var total_frames = 300

func on_start(node, scene):
    print("orb-runner: collect the orbs! (", total_frames, " frames)")

func spawn_orb(scene):
    var idx = len(orbs) + 1
    var name = "orb_" + idx
    if scene.find(name) != null:
        return
    var player = scene.find("player")
    var px = 0
    var pz = 0
    if player != null:
        px = player.position_x
        pz = player.position_z
    var orb_node = scene.spawn("mesh", name)
    orb_node.color = "teal"
    orb_node.scale_x = 0.5
    orb_node.scale_y = 0.5
    orb_node.scale_z = 0.5
    orb_node.position_x = px + rand(-3, 3)
    orb_node.position_y = 0.3
    orb_node.position_z = pz + rand(-3, 3)
    orbs.append(Orb.new(name))

func on_update(node, scene, delta):
    tick = tick + 1
    if tick > total_frames:
        return
    if tick == total_frames:
        print("FINAL SCORE: " + score)
        return
    if tick >= next_spawn:
        spawn_orb(scene)
        next_spawn = next_spawn + 45

    var player = scene.find("player")
    if player == null:
        return

    # headless: auto-chase the nearest orb; editor: keyboard moves the player
    var target = nearest_orb(player, scene)
    if target != null:
        var t = scene.find(target.node_name)
        if t == null:
            remove_orb(target.node_name)
        else:
            seek(player, t.position_x, t.position_z, 8.0)
    if scene.find("camera") != null:
        scene.find("camera").position_x = player.position_x * 0.5
        scene.find("camera").position_z = player.position_z + 10

func seek(player, tx, tz, speed):
    var dx = tx - player.position_x
    var dz = tz - player.position_z
    var d = sqrt(dx * dx + dz * dz)
    if d > 0.2:
        player.position_x = player.position_x + dx / d * speed * 0.016
        player.position_z = player.position_z + dz / d * speed * 0.016
func nearest_orb(player, scene):
    var best = null
    var best_d = 999999
    for orb in orbs:
        var m = scene.find(orb.node_name)
        if m == null:
            continue
        var dx = m.position_x - player.position_x
        var dz = m.position_z - player.position_z
        var d = dx * dx + dz * dz
        if d < best_d:
            best_d = d
            best = orb
    return best

func on_collision(node, scene, a, b, axis):
    var other = null
    if a.name == "player":
        other = b
    elif b.name == "player":
        other = a
    if other == null:
        return
    if other.name.contains("orb_"):
        collect(scene, other.name)

func collect(scene, name):
    for i in range(len(orbs)):
        if orbs[i].node_name == name:
            score = score + orbs[i].value
            print("collected", name, "— score:", score)
            break
    scene.remove(name)
    remove_orb(name)

func remove_orb(name):
    var kept = []
    for o in orbs:
        if o.node_name != name:
            kept.append(o)
    orbs = kept

func on_input(node, scene, key):
    var player = scene.find("player")
    if player == null:
        return
    var step = 0.4
    if key == "left":
        player.position_x = player.position_x - step
    elif key == "right":
        player.position_x = player.position_x + step
    elif key == "up":
        player.position_z = player.position_z - step
    elif key == "down":
        player.position_z = player.position_z + step
