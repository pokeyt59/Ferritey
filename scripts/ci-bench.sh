#!/usr/bin/env bash
# Mob-heavy server benchmark for CI, with a JFR profile of the first arm.
#
# Boots the dev server (Lithium dropped into run/mods by the workflow),
# builds a scene over RCON, then measures /tick query in interleaved arms:
#   pen:  800 husks spread over a 28x28 pen (AI, pathing, movement)
#   pile: 200 husks in a 2x2 cell with cramming damage off (push pairs)
#   town: 60 villagers in a closed pen (brains, POI lookups)
#   boat: one parked boat, as on any real server (a hard collider in the level)
# Arms come from BENCH_ARMS: "name=cmd;cmd|name2=cmd" (empty cmd list is
# allowed). Each arm runs twice, interleaved, to cancel drift.
# Extra arguments go to gradle.
set -euo pipefail

LOG=bench-server.log
REPORT=bench-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-bench
SAMPLES=${BENCH_SAMPLES:-8}
ON="ferrite raycast air-skip on;ferrite cramming on;ferrite entityquery index on"
BENCH_ARMS=${BENCH_ARMS:-"all-on=$ON|clip-vanilla=$ON;ferrite raycast air-skip off|cramming-vanilla=$ON;ferrite cramming off|index-off=$ON;ferrite entityquery index off"}

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
grep -E '\[hw\]|lean mode|Loading [0-9]+ mods' "$LOG" || true
grep -A3 'Loading [0-9]* mods' "$LOG" | head -5 || true

# --- Scene ---------------------------------------------------------------
rcon "forceload add -32 -32 31 31" \
	"gamerule maxEntityCramming 0" "gamerule max_entity_cramming 0" \
	"gamerule doMobSpawning false" "gamerule spawn_mobs false" \
	"time set midnight" > /dev/null || true
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
jfr_started=0

run_arm() {
	local spec=$1 name cmds
	name=${spec%%=*}
	cmds=${spec#*=}
	if [ -n "$cmds" ]; then
		IFS=';' read -r -a list <<< "$cmds"
		rcon "${list[@]}" > /dev/null
	fi
	sleep 10
	if [ "$jfr_started" = 0 ]; then
		# profile.jfc with Java execution sampling forced to 2 ms.
		sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>2 ms</setting>#' \
			"$JAVA_HOME/lib/jfr/profile.jfc" > bench.jfc
		grep -A4 '<event name="jdk.ExecutionSample">' bench.jfc || true
		jcmd "$GAME_PID" JFR.start name=bench settings="$PWD/bench.jfc" \
			duration=$((SAMPLES * 5))s filename="$PWD/bench.jfr" > /dev/null
		jfr_started=1
		echo "JFR recording arm $name"
	fi
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

for round in 1 2; do
	for spec in "${ARMS[@]}"; do
		echo "round $round arm ${spec%%=*}"
		run_arm "$spec"
	done
done

rcon "execute if entity @e[type=minecraft:husk]" "execute if entity @e[type=minecraft:villager]"
airskip=$(rcon "ferrite raycast air-skip status")
echo "$airskip"
rcon "stop" > /dev/null || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
	fail "mixin errors in the server log"
fi
grep -v 'Rcon:' "$LOG" | grep '\[entity-query-cache\] scanned\|\[collider-skip\] eligible' | tail -8 || true
if grep -q 'GRID MISMATCH\|filter skipped intersecting\|\[collider-skip\] MISMATCH\|\[clip-airskip\] MISMATCH' "$LOG"; then
	grep 'MISMATCH' "$LOG" | head -5
	fail "oracle mismatches"
fi
# The air-skip mixin is require = 0; make sure it applied and ran.
echo "$airskip" | grep -q 'rays=[1-9]' || fail "raycast air-skip never ran"

echo "=== /tick query samples ==="
cat "$REPORT"
echo "=== mspt per arm (mean of ${SAMPLES}x2 /tick query samples) ==="
for spec in "${ARMS[@]}"; do
	name=${spec%%=*}
	python3 -c "print('%-24s %6.2f ms  (n=%d)' % ('$name', ${SUM[$name]:-0} / max(${COUNT[$name]:-0}, 1), ${COUNT[$name]:-0}))"
done | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ -f bench.jfr ]; then
	jfr summary bench.jfr | head -40
	java scripts/JfrHot.java bench.jfr | tee bench-hot.txt
fi
