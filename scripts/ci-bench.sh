#!/usr/bin/env bash
# Mob-heavy server benchmark for CI, with a JFR profile taken in an unscored window.
#
# Boots the dev server (Lithium dropped into run/mods by the workflow),
# builds a scene over RCON, then measures /tick query in interleaved arms:
#   pen:  800 husks spread over a 28x28 pen (AI, pathing, movement)
#   pile: 200 husks in a 2x2 cell with cramming damage off (push pairs)
#   town: 60 villagers in a closed pen (brains, POI lookups)
#   boat: one parked boat, as on any real server (a hard collider in the level)
# Arms come from BENCH_ARMS: "name=cmd;cmd|name2=cmd" (empty cmd list is
# allowed). Each arm runs BENCH_ROUNDS times (4), in an order that rotates each round.
# Extra arguments go to gradle.
set -euo pipefail

LOG=bench-server.log
# Ferrite's own log; the console ($LOG) keeps only its warnings and errors.
FLOG=run/logs/ferrite.log
REPORT=bench-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-bench
SAMPLES=${BENCH_SAMPLES:-4}
ROUNDS=${BENCH_ROUNDS:-4}
# ON is the shipped defaults; each other arm flips one switch.
ON="ferrite raycast air-skip on;ferrite cramming on;ferrite entityquery index on;ferrite ai pathtype-bypass on"
BENCH_ARMS=${BENCH_ARMS:-"defaults=$ON|clip-vanilla=$ON;ferrite raycast air-skip off|cramming-vanilla=$ON;ferrite cramming off|pathtype-fabric=$ON;ferrite ai pathtype-bypass off"}

mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<PROPS
online-mode=false
# No players join, and the server pauses ticking after 60 s without any.
pause-when-empty-seconds=-1
view-distance=4
simulation-distance=4
level-seed=ferrite-bench
spawn-monsters=false
spawn-animals=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
PROPS

./gradlew runServer -x buildRustLib -x copyRustDll "$@" < /dev/null > "$LOG" 2>&1 &
PID=$!

fail() {
	echo "::error::$1"
	tail -n 150 "$LOG"
	echo "--- ferrite.log"
	tail -n 60 "$FLOG" 2>/dev/null || true
	kill "$PID" 2>/dev/null || true
	exit 1
}

wait_for() {
	local pattern=$1 limit=$2 waited=0
	until grep -q -- "$pattern" "$LOG"; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "$PID" 2>/dev/null || fail "server exited while waiting for: $pattern"
		[ "$waited" -lt "$limit" ] || fail "timed out waiting for: $pattern"
	done
}

rcon() { python3 scripts/rcon.py "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

wait_for 'Done (' 1200
wait_for 'RCON running' 60
grep -E 'Loading [0-9]+ mods' "$LOG" || true
grep -E '\[hw\]|lean mode' "$FLOG" || true
grep -A3 'Loading [0-9]* mods' "$LOG" | head -5 || true

# --- Scene ---------------------------------------------------------------
rcon "forceload add -32 -32 31 31" \
	"gamerule maxEntityCramming 0" "gamerule max_entity_cramming 0" \
	"gamerule doMobSpawning false" "gamerule spawn_mobs false" \
	"time set midnight" \
	"gamerule doDaylightCycle false" "gamerule advance_time false" > /dev/null || true
# Villager schedules follow the time of day; a frozen clock keeps the
# workload the same from the first arm to the last.
sleep 20

rcon "fill -31 150 -31 -1 150 -1 minecraft:stone" \
	"fill -31 151 -31 -1 155 -1 minecraft:glass hollow" \
	"fill 3 150 3 8 150 8 minecraft:stone" \
	"fill 3 151 3 8 156 8 minecraft:glass hollow" \
	"fill 4 151 4 7 155 7 minecraft:glass hollow" \
	"fill 3 150 -31 31 150 -3 minecraft:stone" \
	"fill 3 151 -31 31 155 -3 minecraft:glass hollow" | sort | uniq -c

pen=()
for _ in $(seq 800); do
	x=$(( -29 + RANDOM % 27 ))
	z=$(( -29 + RANDOM % 27 ))
	pen+=("summon minecraft:husk $x.5 152 $z.5 {PersistenceRequired:1b}")
done
rcon "${pen[@]}" | sort | uniq -c
pile=()
for _ in $(seq 200); do
	pile+=("summon minecraft:husk 6.0 152 6.0 {PersistenceRequired:1b}")
done
rcon "${pile[@]}" | sort | uniq -c
town=()
for _ in $(seq 60); do
	x=$(( 5 + RANDOM % 25 ))
	z=$(( -29 + RANDOM % 25 ))
	town+=("summon minecraft:villager $x.5 152 $z.5 {PersistenceRequired:1b}")
done
rcon "${town[@]}" | sort | uniq -c
rcon "summon minecraft:oak_boat -15.5 152 -15.5" \
	"execute if entity @e[type=minecraft:husk]" "execute if entity @e[type=minecraft:villager]"

echo "warming up (JIT, pathing)"
sleep 60

# The numbers mean nothing unless the world is really ticking.
t0=$(rcon "time query gametime" | sed -n 's/[^0-9]*\([0-9][0-9]*\).*/\1/p')
sleep 5
t1=$(rcon "time query gametime" | sed -n 's/[^0-9]*\([0-9][0-9]*\).*/\1/p')
echo "game time advanced $((t1 - t0)) ticks in 5 s"
[ "$((t1 - t0))" -ge 50 ] || fail "the world is not ticking (game time $t0 -> $t1)"

# --- Arms ----------------------------------------------------------------
jcmd -l
GAME_PID=$(jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}')
if [ -z "$GAME_PID" ]; then
	GAME_PID=$(jcmd -l | awk '!/Gradle|jcmd|JCmd/ {print $1; exit}')
fi
[ -n "$GAME_PID" ] || fail "game JVM not found"

IFS='|' read -r -a ARMS <<< "$BENCH_ARMS"
declare -A SUM COUNT
: > "$REPORT"

# Profile in its own window, shipped defaults, so the recording's
# overhead lands on no scored arm. profile.jfc with Java execution
# sampling forced to 2 ms.
IFS=';' read -r -a on_cmds <<< "$ON"
rcon "${on_cmds[@]}" > /dev/null
sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>2 ms</setting>#' \
	"$JAVA_HOME/lib/jfr/profile.jfc" > bench.jfc
grep -A4 '<event name="jdk.ExecutionSample">' bench.jfc || true
jcmd "$GAME_PID" JFR.start name=bench settings="$PWD/bench.jfc" \
	duration=$((SAMPLES * 5))s filename="$PWD/bench.jfr" > /dev/null
echo "JFR recording $((SAMPLES * 5)) s with the shipped defaults (unscored)"
sleep $((SAMPLES * 5 + 5))

# Profile A/B: equal JFR windows on the same server, one switch off per
# window, for effects smaller than the MSPT noise. "defaults" is
# recorded twice to show the window-to-window spread.
PROFILES=${BENCH_PROFILES:-"defaults=|pathtype-off=ferrite ai pathtype-bypass off|defaults-again=|clip-off=ferrite raycast air-skip off"}
PROFILE_SECONDS=${BENCH_PROFILE_SECONDS:-20}
IFS='|' read -r -a PROFS <<< "$PROFILES"
for spec in "${PROFS[@]}"; do
	pname=${spec%%=*}
	pcmds=${spec#*=}
	rcon "${on_cmds[@]}" > /dev/null
	if [ -n "$pcmds" ]; then
		IFS=';' read -r -a plist <<< "$pcmds"
		rcon "${plist[@]}" > /dev/null
	fi
	sleep 5
	jcmd "$GAME_PID" JFR.start name="prof-$pname" settings="$PWD/bench.jfc" \
		duration=${PROFILE_SECONDS}s filename="$PWD/prof-$pname.jfr" > /dev/null
	echo "JFR profile window $pname"
	sleep $((PROFILE_SECONDS + 3))
done
rcon "${on_cmds[@]}" > /dev/null

run_arm() {
	local spec=$1 name cmds
	name=${spec%%=*}
	cmds=${spec#*=}
	if [ -n "$cmds" ]; then
		IFS=';' read -r -a list <<< "$cmds"
		rcon "${list[@]}" > /dev/null
	fi
	sleep 10
	for _ in $(seq "$SAMPLES"); do
		sleep 5
		out=$(rcon "tick query")
		ms=$(echo "$out" | sed -n 's/.*[Aa]verage time per tick: \([0-9.]*\) *ms.*/\1/p' | head -1)
		[ -n "$ms" ] || { echo "unparsed tick query: $out"; continue; }
		SUM[$name]=$(python3 -c "print(${SUM[$name]:-0} + $ms)")
		COUNT[$name]=$(( ${COUNT[$name]:-0} + 1 ))
		echo "$name $ms" >> "$REPORT"
	done
}

# The order rotates each round, so with as many rounds as arms every arm
# runs once in every position: an arm that leaves the scene in a state
# (vanilla cramming reshapes the pile) biases no single other arm.
for round in $(seq "$ROUNDS"); do
	for k in $(seq 0 $((${#ARMS[@]} - 1))); do
		spec=${ARMS[$(( (k + round - 1) % ${#ARMS[@]} ))]}
		echo "round $round arm ${spec%%=*}"
		run_arm "$spec"
	done
done

rcon "execute if entity @e[type=minecraft:husk]" "execute if entity @e[type=minecraft:villager]"
airskip=$(rcon "ferrite raycast air-skip status")
echo "$airskip"
pathtype=$(rcon "ferrite ai pathtype-bypass status")
echo "$pathtype"
rcon "stop" > /dev/null || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
	fail "mixin errors in the server log"
fi
grep '\[entity-query-cache\] scanned\|\[collider-skip\] eligible' "$FLOG" | tail -8 || true
if grep -q 'GRID MISMATCH\|filter skipped intersecting\|\[collider-skip\] MISMATCH\|\[clip-airskip\] MISMATCH' "$FLOG"; then
	grep -m 5 'MISMATCH' "$FLOG"
	fail "oracle mismatches"
fi

echo "=== /tick query samples ==="
cat "$REPORT"
echo "=== mspt per arm (mean of ${SAMPLES}x${ROUNDS} /tick query samples) ==="
for spec in "${ARMS[@]}"; do
	name=${spec%%=*}
	python3 -c "print('%-24s %6.2f ms  (n=%d)' % ('$name', ${SUM[$name]:-0} / max(${COUNT[$name]:-0}, 1), ${COUNT[$name]:-0}))"
done | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ -f bench.jfr ]; then
	jfr summary bench.jfr | head -40
	java scripts/JfrHot.java bench.jfr | tee bench-hot.txt
fi

echo "=== profile A/B: inclusive server-thread samples per ${PROFILE_SECONDS} s window ==="
for spec in "${PROFS[@]}"; do
	pname=${spec%%=*}
	# A long inclusive list, so the methods below are found whatever their rank.
	[ -f "prof-$pname.jfr" ] && java scripts/JfrHot.java "prof-$pname.jfr" 2000 > "hot-$pname.txt"
done
python3 - "${PROFS[@]}" <<'PY' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
import re, sys
methods = [
    "net.minecraft.world.entity.npc.villager.Villager.tick",
    "net.minecraft.world.entity.ai.Brain.tick",
    "net.minecraft.world.entity.ai.Brain.startEachNonRunningBehavior",
    "net.minecraft.world.level.pathfinder.PathFinder.findPath",
    "net.minecraft.world.level.pathfinder.PathfindingContext.getPathTypeFromState",
    "net.minecraft.world.entity.LivingEntity.hasLineOfSight",
]
names = [spec.split("=", 1)[0] for spec in sys.argv[1:]]
table = {}
for name in names:
    try:
        text = open(f"hot-{name}.txt").read()
    except OSError:
        continue
    total = re.search(r"server thread samples: (\d+)", text)
    incl = text.split("=== inclusive ===", 1)[-1].split("===", 1)[0]
    counts = {m: 0 for m in methods}
    for line in incl.splitlines():
        parts = line.split()
        if len(parts) == 3 and parts[2] in counts:
            counts[parts[2]] = int(parts[1])
    table[name] = (int(total.group(1)) if total else 0, counts)
labels = ["Villager.tick", "Brain.tick", "Brain.start", "findPath", "getPathType", "lineOfSight"]
print("%-14s %7s " % ("window", "total") + " ".join("%13s" % l for l in labels))
for name, (total, counts) in table.items():
    print("%-14s %7d " % (name, total) + " ".join("%13d" % counts[m] for m in methods))
PY

# Last, so the numbers above always print: the air-skip mixin is
# require = 0, so make sure it applied and ran.
echo "$airskip" | grep -q 'rays=[1-9]' || { echo "::error::raycast air-skip never ran"; exit 1; }
echo "$pathtype" | grep -q 'bypassed=[1-9]' || { echo "::error::path type bypass never ran"; exit 1; }
