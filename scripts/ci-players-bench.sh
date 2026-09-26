#!/usr/bin/env bash
# A replica of the server this repo is tuned for, driven by bench players.
#
# The server: an i7-2640M laptop (2 cores / 4 threads), view and
# simulation distance 10, the worldgen mods in scripts/worldgen-mods.txt
# and the rest in scripts/server-mods.txt (spark among them, so Ferrite
# runs lean), players exploring on foot, on horses, with elytra and in
# fast boats, several at once far apart. Mods come from run/mods, fetched
# by the workflow.
#
# Players are real ServerPlayers on a connection with no socket
# (/ferrite bench players, FakeExplorers.java): player chunk loading,
# chunk packets built and paced by batch acknowledgements, entity
# tracking, natural spawning, mobs ticking around them.
#
# A 4-vCPU runner is 2 cores with 2 threads, like the laptop, but each is
# far faster. The laptop-speed model (scripts/cpu-share.py) runs a
# spinning process per CPU at a nice level chosen so that the noise bench
# (/ferrite bench noise, the same chunks on the same seed) takes FACTOR
# times what it takes on an EPYC 7763 runner (ANCHOR_MS). A single-thread
# gap of 1.5-2x is the published estimate between the two CPUs, so both
# are run. It models slower cores, measured on worldgen code, not the
# laptop's clock, memory or cooling.
#
# PB_SCENARIO picks the players, and the arms (each on fresh terrain):
#   walk | horse | elytra | boat | mixed    at runner speed and each factor
#   config   mixed at factor 2: defaults, simulation distance 6, and the
#            server thread on its own core (ferrite worldgen
#            isolate-server-core), in ABCCBA order
#   c2me     mixed at factor 2 on four fresh worlds (same seed, same
#            terrain): without C2ME, with it (run/c2me), without, with
#   compat   mixed at factor 2 with Ferrite's chunk-wait guards (/ferrite
#            compat all) off, on, on, off
#   pregen   an elytra at factor 2 for 60 s over fresh terrain, then over a
#            corridor pregenerated first (/ferrite pregen), twice
# Every start first runs a 60 s warm-up arm, not measured: the first
# player to land in ungenerated terrain stalls the server for tens of
# seconds (Lithostitched's structure check), which says nothing about
# exploring.
# Extra arguments go to gradle.
set -euo pipefail

SCENARIO=${PB_SCENARIO:-mixed}
LOG=players-server.log
FLOG=run/logs/ferrite.log
REPORT=players-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-players
CPUS=${PB_CPUS:-0-3}
DURATION=${PB_DURATION:-180}
FACTORS=${PB_FACTORS:-"1.5 2"}
# /ferrite bench noise 24 3 lazy-interp, "on" (every shipped switch on),
# ms per chunk on an AMD EPYC 7763 runner, seed ferrite-worldgen.
ANCHOR_MS=20.0

mkdir -p run
echo "eula=true" > run/eula.txt
write_properties() {
	cat > run/server.properties <<PROPS
online-mode=false
pause-when-empty-seconds=-1
view-distance=10
simulation-distance=10
level-seed=ferrite-worldgen
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
PROPS
}
write_properties

echo "=== cpu ==="
lscpu | grep -E '^(Model name|CPU\(s\)|Thread\(s\) per core|Core\(s\) per socket|Socket\(s\))' | tee cpu.txt || true
echo "pinned to CPUs $CPUS"
# Nice only divides a CPU between processes of one scheduler autogroup,
# and the game runs under Gradle's daemon, in another.
sudo -n sysctl -qw kernel.sched_autogroup_enabled=0 2>/dev/null || echo "could not turn scheduler autogroups off"

PID=
SPIN_PID=
fail() {
	echo "::error::$1"
	tail -n 150 "$LOG"
	echo "--- ferrite.log"
	tail -n 60 "$FLOG" 2>/dev/null || true
	stop_spin
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

start_server() {
	taskset -c "$CPUS" ./gradlew runServer -x buildRustLib -x copyRustDll "$@" < /dev/null > "$LOG" 2>&1 &
	PID=$!
	wait_for 'Done (' 1800
	wait_for 'RCON running' 60
	grep -E '\[hw\]|lean mode' "$FLOG" | tee -a "$REPORT" || true
	# Log what the server thread does in any tick gap over 100 ms.
	rcon "ferrite tickwatch 100" > /dev/null
}

stop_server() {
	rcon "stop" > /dev/null || true
	wait "$PID" || true
	if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError' "$LOG"; then
		fail "mixin errors in the server log"
	fi
}

spin() {
	stop_spin
	[ "$1" = off ] && return
	if sudo -n true 2>/dev/null; then
		sudo -n python3 scripts/cpu-share.py "$CPUS" "$1" 2>/dev/null &
	else
		python3 scripts/cpu-share.py "$CPUS" "$1" 2>/dev/null &
	fi
	SPIN_PID=$!
	sleep 1
}

stop_spin() {
	if [ -n "$SPIN_PID" ]; then
		sudo -n pkill -f scripts/cpu-share.py 2>/dev/null || pkill -f scripts/cpu-share.py 2>/dev/null || true
		wait "$SPIN_PID" 2>/dev/null || true
		SPIN_PID=
	fi
}

noise_ms() {
	rcon "ferrite bench noise 24 3 lazy-interp" | sed -n 's/.*lazy-interp on \([0-9.]*\),.*/\1/p' | head -1
}

declare -A NICE
# For each factor, the nice level at which the spinners bring the noise
# bench closest to FACTOR x ANCHOR_MS. Slowdown falls as nice rises; it
# is more than CFS weights alone give, since a spinner also shares its
# physical core with the sibling hyperthread, so it is bisected, measured.
calibrate() {
	local base target lo hi mid m best bestdiff
	stop_spin
	noise_ms > /dev/null   # JIT warm-up
	base=$(noise_ms)
	[ -n "$base" ] || fail "noise bench gave no timing"
	echo "=== laptop-speed model ===" | tee -a "$REPORT"
	echo "runner speed: noise bench $base ms/chunk (EPYC 7763: $ANCHOR_MS)" | tee -a "$REPORT"
	for f in $FACTORS; do
		target=$(python3 -c "print(round($ANCHOR_MS * $f, 2))")
		if python3 -c "import sys; sys.exit(0 if $base >= $target * 0.95 else 1)"; then
			NICE[$f]=off
			echo "factor $f: runner already at $base ms/chunk (target $target), no spinners" | tee -a "$REPORT"
			continue
		fi
		lo=-10
		hi=19
		best=19
		bestm=$base
		bestdiff=
		while [ $((hi - lo)) -gt 1 ]; do
			mid=$(( (lo + hi) / 2 ))
			spin "$mid"
			m=$(noise_ms)
			echo "factor $f: nice $mid gives $m ms/chunk (target $target)" | tee -a "$REPORT"
			d=$(python3 -c "print(abs($m - $target))")
			if [ -z "$bestdiff" ] || python3 -c "import sys; sys.exit(0 if $d < $bestdiff else 1)"; then
				best=$mid
				bestm=$m
				bestdiff=$d
			fi
			if python3 -c "import sys; sys.exit(0 if $m > $target else 1)"; then lo=$mid; else hi=$mid; fi
		done
		NICE[$f]=$best
		echo "factor $f: nice $best, $bestm ms/chunk: modelled factor $(python3 -c "print(round($bestm / $ANCHOR_MS, 2))")" | tee -a "$REPORT"
		stop_spin
	done
}

# Players for this scenario, travelling east from x = $1 on fresh terrain.
players_at() {
	local x=$1
	case "$SCENARIO" in
		walk|horse|elytra|boat) echo "$SCENARIO:$x:0:-90" ;;
		pregen) echo "elytra:$x:0:-90" ;;
		*) echo "walk:$x:0:-90 horse:$x:3000:-90 elytra:$x:-3000:-90" ;;
	esac
}

ARM=0
# run_arm <label> <factor|1> [setup command] [teardown command]
# ARM_X overrides where the players start, ARM_DURATION how long they travel.
run_arm() {
	local label=$1 factor=$2 setup=${3:-} teardown=${4:-} x from fromlog specs duration
	x=${ARM_X:-$((10000 + ARM * 12000))}
	duration=${ARM_DURATION:-$DURATION}
	ARM=$((ARM + 1))
	if [ "$factor" = 1 ]; then spin off; else spin "${NICE[$factor]}"; fi
	[ -z "$setup" ] || rcon "$setup" | sed "s/^/[$label] /" | tee -a "$REPORT"
	from=$(wc -l < "$FLOG")
	fromlog=$(wc -l < "$LOG")
	read -r -a specs <<< "$(players_at "$x")"
	echo "[$label] starts at $(date -u +%H:%M:%S) UTC, laptop factor $factor, nice ${NICE[$factor]:-off}" | tee -a "$REPORT"
	if [ "$label" = "$RECORD" ]; then
		jcmd "$(game_pid)" JFR.start name=players settings="$PWD/players.jfc" filename="$PWD/players.jfr" > /dev/null || true
	fi
	python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" players "$label" "$duration" "${specs[@]}" \
		| tee -a "$REPORT" || arm_failed=1
	if [ "$label" = "$RECORD" ]; then jcmd "$(game_pid)" JFR.stop name=players > /dev/null || true; fi
	# Tick gaps over 100 ms (the tick watchdog), and what the server thread
	# was doing in the three longest.
	tail -n +"$((from + 1))" "$FLOG" | python3 -c '
import re, sys
found = [(int(m.group(1)), line) for line in sys.stdin
         for m in [re.search(r"\[slow-tick\] tick \d+: (\d+) ms between ticks", line)] if m]
gaps = [g for g, _ in found]
print(f"[{sys.argv[1]}] tick gaps over 100 ms: {len(gaps)}, longest {max(gaps) if gaps else 0} ms, "
      f"total {sum(gaps) / 1000:.1f} s")
for g, line in sorted(found, key=lambda f: -f[0])[:3]:
    print(f"[{sys.argv[1]}]   " + line.strip()[line.find("[slow-tick]"):][:1500])' "$label" | tee -a "$REPORT"
	echo "[$label] \"Can't keep up\" warnings: $(tail -n +"$((fromlog + 1))" "$LOG" | grep -c "Can't keep up" || true)" | tee -a "$REPORT"
	[ -z "$teardown" ] || rcon "$teardown" > /dev/null
	spin off
	# Let generation left over from this arm finish before the next.
	sleep 30
}

game_pid() { jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}'; }

# profile.jfc with Java execution sampling at 5 ms, every thread.
sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>5 ms</setting>#' \
	"$JAVA_HOME/lib/jfr/profile.jfc" > players.jfc

: > "$REPORT"
echo "scenario $SCENARIO, $DURATION s per arm, runner $(sed -n 's/^Model name: *//p' cpu.txt)" | tee -a "$REPORT"
arm_failed=
RECORD=

warmup() { ARM_DURATION=60 run_arm warmup 1; }

# boot <with-c2me: yes|no>: a fresh world with the same seed (the same terrain).
boot() {
	[ -z "$PID" ] || stop_server
	[ ! -f "$FLOG" ] || cat "$FLOG" >> players-ferrite-all.log
	cat "$LOG" >> players-server-all.log
	rm -rf run/world
	rm -f run/mods/c2me-*.jar
	if [ "$1" = yes ]; then cp run/c2me/*.jar run/mods/ || fail "no C2ME jar in run/c2me"; fi
	ARM=0
	start_server "${GRADLE_ARGS[@]}"
	sleep 20
}

# pregen_corridor <block x>: chunks along an elytra's 60 s path east from
# x (about 125 chunks, plus its view distance ahead), 21 wide, generated
# first by /ferrite pregen in squares of 21x21.
pregen_corridor() {
	local cx=$(( $1 >> 4 )) k before
	echo "[pregen] corridor from chunk x $cx, $(date -u +%H:%M:%S) UTC" | tee -a "$REPORT"
	for k in 0 1 2 3 4 5 6; do
		before=$(grep -c "ferrite-pregen\] complete" "$FLOG" || true)
		rcon "ferrite pregen at $((cx + 10 + 21 * k)) 0 10" > /dev/null
		local waited=0
		until [ "$(grep -c "ferrite-pregen\] complete" "$FLOG" || true)" -gt "$before" ]; do
			sleep 2
			waited=$((waited + 2))
			[ "$waited" -lt 600 ] || fail "pregen square $k did not finish"
		done
	done
	echo "[pregen] corridor done $(date -u +%H:%M:%S) UTC" | tee -a "$REPORT"
	sleep 20
}

GRADLE_ARGS=("$@")
start_server "${GRADLE_ARGS[@]}"
echo "warming up"
sleep 30
calibrate
warmup

case "$SCENARIO" in
	walk|horse|elytra|boat|mixed)
		[ "$SCENARIO" = mixed ] && RECORD=laptop-2x
		run_arm runner 1
		run_arm laptop-2x 2
		run_arm laptop-1.5x 1.5
		stop_server
		;;
	config)
		RECORD=defaults-a
		SIM6_ON="ferrite bench distances 10 6"
		SIM6_OFF="ferrite bench distances 10 10"
		ISO_ON="ferrite worldgen isolate-server-core on"
		ISO_OFF="ferrite worldgen isolate-server-core off"
		run_arm defaults-a 2
		run_arm sim6-a 2 "$SIM6_ON" "$SIM6_OFF"
		run_arm isolate-a 2 "$ISO_ON" "$ISO_OFF"
		run_arm isolate-b 2 "$ISO_ON" "$ISO_OFF"
		run_arm sim6-b 2 "$SIM6_ON" "$SIM6_OFF"
		run_arm defaults-b 2
		stop_server
		;;
	compat)
		RECORD=guards-on-a
		run_arm guards-off-a 2 "ferrite compat all off" "ferrite compat status"
		run_arm guards-on-a 2 "ferrite compat all on" "ferrite compat status"
		run_arm guards-on-b 2 "ferrite compat all on" "ferrite compat status"
		run_arm guards-off-b 2 "ferrite compat all off" "ferrite compat all on"
		rcon "ferrite compat status" | tee -a "$REPORT"
		stop_server
		;;
	c2me)
		run_arm without-c2me-a 2
		boot yes
		warmup
		run_arm with-c2me-a 2
		boot no
		warmup
		run_arm without-c2me-b 2
		boot yes
		warmup
		run_arm with-c2me-b 2
		stop_server
		;;
	pregen)
		ARM_DURATION=60 ARM_X=40000 run_arm fresh-a 2
		pregen_corridor 60000
		ARM_DURATION=60 ARM_X=60000 run_arm pregenerated-a 2
		pregen_corridor 80000
		ARM_DURATION=60 ARM_X=80000 run_arm pregenerated-b 2
		ARM_DURATION=60 ARM_X=100000 run_arm fresh-b 2
		stop_server
		;;
	*) fail "unknown scenario $SCENARIO" ;;
esac
[ ! -f "$FLOG" ] || cat "$FLOG" >> players-ferrite-all.log
stop_spin

if grep -q 'MISMATCH' players-ferrite-all.log 2>/dev/null; then
	grep -h -m 5 'MISMATCH' players-ferrite-all.log 2>/dev/null || true
	fail "oracle mismatches"
fi

echo "=== players report ==="
cat "$REPORT" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [ -f players.jfr ]; then
	echo "=== CPU by thread group and worldgen stage ($RECORD) ==="
	java scripts/JfrStages.java players.jfr | tee players-stages.txt
	head -c 20000 players-stages.txt | sed -n '1,16p' >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
fi
[ -z "$arm_failed" ] || fail "an arm failed (see above)"
echo "players bench done"
