# demo: hero cube spins forever; arrow keys (up/down/left/right)
# slide it across the ground; the sun tilts with time.

var spin_speed = 90
var move_speed = 2.0
var sun_tilt = 0

func on_start(node, scene):
    print("demo ready — hero spins at", spin_speed, "deg/s")

func on_update(node, scene, delta):
    var hero = scene.find("hero")
    if hero == null:
        return
    hero.rotation_y = hero.rotation_y + spin_speed * delta
    sun_tilt = sun_tilt + 5 * delta
    var sun = scene.find("sun")
    if sun != null:
        sun.rotation_x = -35 + 10 * sin(sun_tilt * 0.1)

func on_input(node, scene, key):
    var hero = scene.find("hero")
    if hero == null:
        return
    if key == "left":
        hero.position_x = hero.position_x - move_speed * 0.25
    elif key == "right":
        hero.position_x = hero.position_x + move_speed * 0.25
    elif key == "up":
        hero.position_z = hero.position_z - move_speed * 0.25
    elif key == "down":
        hero.position_z = hero.position_z + move_speed * 0.25
