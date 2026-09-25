#!/usr/bin/env bash
# Mob-heavy server benchmark for CI, with a JFR profile of the first arm.
#
# Boots the dev server (Lithium dropped into run/mods by the workflow),
# builds a scene over RCON, then measures /tick query in interleaved arms:
#   pen:  400 husks spread over a 28x28 pen (AI, pathing, movement)
#   pile: 150 husks in a 2x2 cell with cramming damage off (push pairs)
#   town: 60 villagers in a closed pen (brains, POI lookups)
# Arms come from BENCH_ARMS: "name=cmd;cmd|name2=cmd" (empty cmd list is
# allowed). Each arm runs twice, interleaved, to cancel drift.
# Extra arguments go to gradle.
set -euo pipefail

LOG=bench-server.log
REPORT=bench-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-bench
SAMPLES=${BENCH_SAMPLES:-8}
BENCH_ARMS=${BENCH_ARMS:-"typed-grid=ferrite entityquery typed-grid on|typed-linear=ferrite entityquery typed-grid off"}

mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<PROPS
online-mode=false
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
for _ in $(seq 400); do
	x=$(( -29 + RANDOM % 27 ))
	z=$(( -29 + RANDOM % 27 ))
	pen+=("summon minecraft:husk $x.5 152 $z.5 {PersistenceRequired:1b}")
done
rcon "${pen[@]}" | sort | uniq -c
pile=()
for _ in $(seq 150); do
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
rcon "execute if entity @e[type=minecraft:husk]" "execute if entity @e[type=minecraft:villager]"

echo "warming up (JIT, pathing)"
sleep 60

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
		jcmd "$GAME_PID" JFR.start name=bench settings=profile \
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
rcon "stop" > /dev/null || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
	fail "mixin errors in the server log"
fi
grep '\[entity-query-cache\]' "$LOG" | tail -4 || true
if grep -q 'GRID MISMATCH\|filter skipped intersecting' "$LOG"; then
	grep 'MISMATCH' "$LOG" | head -5
	fail "entity query oracle mismatches"
fi

echo "=== mspt per arm (mean of ${SAMPLES}x2 /tick query samples) ==="
for spec in "${ARMS[@]}"; do
	name=${spec%%=*}
	python3 -c "print('%-24s %6.2f ms  (n=%d)' % ('$name', ${SUM[$name]:-0} / max(${COUNT[$name]:-0}, 1), ${COUNT[$name]:-0}))"
done | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

if [ -f bench.jfr ]; then
	java scripts/JfrHot.java bench.jfr | tee bench-hot.txt
fi
