#!/usr/bin/env bash
# Live chunk generation benchmark for CI, on the worldgen mods placed in
# run/mods (scripts/worldgen-mods.txt, fetched by the workflow).
#
# Stands in for players exploring fresh terrain on a small server: a
# corridor of chunk tickets advances through ungenerated land
# (scripts/worldgen-drive.py) in phases that A/B a setting (WG_PHASES),
# while a mob pen keeps the server thread busy. Reports tick time with
# and without exploring, chunks delivered per second, request latency,
# backlog, worldgen worker CPU per chunk, and, from a JFR recording of
# every thread, which worldgen stage the CPU went to
# (scripts/JfrStages.java).
#
# The JVM is pinned with taskset (WG_CPUS, default 0-3). A 4-vCPU CI
# runner is 2 cores with 2 threads each; lscpu below shows the topology.
# Extra arguments go to gradle.
set -euo pipefail

LOG=worldgen-server.log
REPORT=worldgen-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-worldgen
CPUS=${WG_CPUS:-0-3}
START_X=${WG_START_X:-2000}
WIDTH=${WG_WIDTH:-9}
STEP=${WG_STEP:-1}
DURATION=${WG_DURATION:-60}

mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<PROPS
online-mode=false
# No players join, and the server pauses ticking after 60 s without any.
pause-when-empty-seconds=-1
view-distance=4
simulation-distance=4
level-seed=ferrite-worldgen
spawn-monsters=false
spawn-animals=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
PROPS

echo "=== cpu ==="
lscpu | grep -E '^(Model name|CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket\(s\))' || true
grep . /sys/devices/system/cpu/cpu*/topology/thread_siblings_list || true
echo "pinned to CPUs $CPUS"

taskset -c "$CPUS" ./gradlew runServer -x buildRustLib -x copyRustDll "$@" < /dev/null > "$LOG" 2>&1 &
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

wait_for 'Done (' 1800
wait_for 'RCON running' 60
: > "$REPORT"
grep -E '\[hw\]|lean mode' "$LOG" | tee -a "$REPORT" || true
echo "=== mods loaded ==="
sed -n '/Loading [0-9]* mods/,/^\[/p' "$LOG" | head -150

# --- Mob load at spawn, as on a live server --------------------------------
rcon "forceload add -32 -32 31 31" \
	"gamerule maxEntityCramming 0" "gamerule max_entity_cramming 0" \
	"gamerule doMobSpawning false" "gamerule spawn_mobs false" \
	"time set midnight" "gamerule doDaylightCycle false" "gamerule advance_time false" > /dev/null || true
sleep 15
rcon "fill -31 150 -31 -1 150 -1 minecraft:stone" \
	"fill -31 151 -31 -1 155 -1 minecraft:glass hollow" | sort | uniq -c
pen=()
for _ in $(seq 300); do
	x=$(( -29 + RANDOM % 27 ))
	z=$(( -29 + RANDOM % 27 ))
	pen+=("summon minecraft:husk $x.5 152 $z.5 {PersistenceRequired:1b}")
done
rcon "${pen[@]}" | sort | uniq -c
echo "warming up"
sleep 45

# Height queries (a whole NoiseChunk per column) with MappingMemo on and
# off, on the server thread, which they stall meanwhile: before the
# measured window.
echo "=== height queries ===" | tee -a "$REPORT"
rcon "ferrite bench columns 100 6" | tee -a "$REPORT"

# Setup (the pen's forceload) stalls the server by design; only later
# "Can't keep up" warnings are reported.
measured_from=$(wc -l < "$LOG")
python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" baseline 6 | tee -a "$REPORT"


# --- Exploring ----------------------------------------------------------------
# Phases over fresh terrain, each "label=setup": the setup runs first.
# "pin" puts the server thread on a physical core of its own and the
# worldgen workers on the others (scripts/pin-threads.py); anything else
# is a server command. The first phase is recorded with JFR.
ISO="ferrite worldgen isolate-server-core"
# The first phase warms up the JIT on worldgen code and is not an arm.
MEMO="ferrite worldgen map-memo"
# Shipped defaults throughout: this run looks for the tick spikes seen late
# in earlier runs (the whole run is recorded, below).
PHASES=${WG_PHASES:-"warmup=|run-1=|run-2=|run-3=|run-4=|run-5=|run-6="}
GAME_PID=$(jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}')
[ -n "$GAME_PID" ] || fail "game JVM not found"
# profile.jfc with Java execution sampling at 5 ms, every thread.
sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>5 ms</setting>#' \
	"$JAVA_HOME/lib/jfr/profile.jfc" > worldgen.jfc
x=$START_X
recorded=
# Every phase, with JFR's default settings, to find stalls and their time.
jcmd "$GAME_PID" JFR.start name=whole settings=default filename="$PWD/worldgen-whole.jfr" > /dev/null
IFS='|' read -r -a phase_list <<< "$PHASES"
for spec in "${phase_list[@]}"; do
	label=${spec%%=*}
	setup=${spec#*=}
	pinner=
	if [ "$setup" = pin ]; then
		python3 scripts/pin-threads.py "$GAME_PID" split "$CPUS" verbose | tee -a "$REPORT"
		( while sleep 2; do python3 scripts/pin-threads.py "$GAME_PID" split "$CPUS"; done ) &
		pinner=$!
	elif [ -n "$setup" ]; then
		rcon "$setup" > /dev/null
	fi
	echo "[$label] starts at $(date -u +%H:%M:%S) UTC" | tee -a "$REPORT"
	[ -n "$recorded" ] || jcmd "$GAME_PID" JFR.start name=worldgen settings="$PWD/worldgen.jfc" filename="$PWD/worldgen.jfr" > /dev/null
	python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" explore \
		"$x" "$WIDTH" "$STEP" "$DURATION" "$label" | tee -a "$REPORT" || explore_failed=1
	[ -n "$recorded" ] || { jcmd "$GAME_PID" JFR.stop name=worldgen > /dev/null || true; recorded=1; }
	if [ -n "$pinner" ]; then
		kill "$pinner"; wait "$pinner" 2>/dev/null || true
		python3 scripts/pin-threads.py "$GAME_PID" reset "$CPUS"
	fi
	rcon "ferrite worldgen height-cache status" "$ISO status" "$MEMO status" | sed "s/^/[$label] /" | tee -a "$REPORT"
	x=$((x + 200))
done
jcmd "$GAME_PID" JFR.stop name=whole > /dev/null || true

rcon "stop" > /dev/null || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
	fail "mixin errors in the server log"
fi

echo "=== server log: ticks running behind ==="
tail -n +"$((measured_from + 1))" "$LOG" | grep -E "Can't keep up|ticks behind" | tail -20 | tee -a "$REPORT" || true

echo "=== worldgen report ==="
cat "$REPORT" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [ -f worldgen.jfr ]; then
	echo "=== CPU by thread group and worldgen stage ==="
	java scripts/JfrStages.java worldgen.jfr | tee worldgen-stages.txt
	java scripts/JfrHot.java worldgen.jfr 150 > worldgen-hot.txt
	head -c 20000 worldgen-stages.txt | sed -n '1,16p' >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
if [ -f worldgen-whole.jfr ]; then
	echo "=== whole run: the server thread's busiest seconds, stalls and GC ==="
	java scripts/JfrStages.java worldgen-whole.jfr > worldgen-whole.txt
	sed -n "/busy second/,/collections\$/p" worldgen-whole.txt
fi
[ -z "${explore_failed:-}" ] || { echo "::error::exploring failed"; exit 1; }
if grep -q 'oracleMismatches=[1-9]' "$REPORT"; then echo "::error::oracle mismatches"; exit 1; fi
grep -q 'heights differing 0;' "$REPORT" || { echo "::error::height queries differ with the mapping memo"; exit 1; }
