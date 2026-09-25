#!/usr/bin/env bash
# Live chunk generation benchmark for CI, on the worldgen mods placed in
# run/mods (scripts/worldgen-mods.txt, fetched by the workflow).
#
# Stands in for players exploring fresh terrain on a small server: a
# corridor of forceloaded chunks advances through ungenerated land
# (scripts/worldgen-drive.py) while a mob pen keeps the server thread
# busy. Reports tick time with and without exploring, chunks delivered
# per second, how far generation falls behind, and, from a JFR recording
# of every thread, which worldgen stage the CPU went to
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
DURATION=${WG_DURATION:-120}

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

python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" baseline 6 | tee -a "$REPORT"

# --- Exploring, recorded ----------------------------------------------------
GAME_PID=$(jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}')
[ -n "$GAME_PID" ] || fail "game JVM not found"
# profile.jfc with Java execution sampling at 5 ms, every thread.
sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>5 ms</setting>#' \
	"$JAVA_HOME/lib/jfr/profile.jfc" > worldgen.jfc
jcmd "$GAME_PID" JFR.start name=worldgen settings="$PWD/worldgen.jfc" filename="$PWD/worldgen.jfr" > /dev/null
python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" explore \
	"$START_X" "$WIDTH" "$STEP" "$DURATION" | tee -a "$REPORT" || explore_failed=1
jcmd "$GAME_PID" JFR.stop name=worldgen > /dev/null || true

rcon "stop" > /dev/null || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
	fail "mixin errors in the server log"
fi

echo "=== server log: ticks running behind ==="
grep -E "Can't keep up|ticks behind" "$LOG" | tail -20 | tee -a "$REPORT" || true

echo "=== worldgen report ==="
cat "$REPORT" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [ -f worldgen.jfr ]; then
	echo "=== CPU by thread group and worldgen stage ==="
	java scripts/JfrStages.java worldgen.jfr | tee worldgen-stages.txt
	java scripts/JfrHot.java worldgen.jfr 150 > worldgen-hot.txt
	head -c 20000 worldgen-stages.txt | sed -n '1,16p' >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
[ -z "${explore_failed:-}" ] || { echo "::error::exploring failed"; exit 1; }
