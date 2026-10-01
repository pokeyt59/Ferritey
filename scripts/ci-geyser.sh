#!/usr/bin/env bash
# Ferrite with Geyser and Floodgate: Bedrock players on the target server
# (the user's mods from scripts/server-mods.txt and worldgen-mods.txt,
# Krypton included), joined by a Bedrock client (scripts/test-clients) over
# RakNet, as a Bedrock player joins through Floodgate.
#
# Scenarios (GEYSER_SCENARIO):
#   full          every Ferrite feature on (the defaults). The bot must:
#                 join and spawn; receive the command list with /ferrite
#                 once opped; see a pen of husks cram and die and husks
#                 move; be flown 90 s through fresh terrain by the server
#                 (/ferrite bench players drive), receiving chunks all the
#                 way; and stay connected throughout. No mixin error from
#                 any mod, and no oracle mismatch.
#   features-off  the same with Ferrite's runtime features off (cramming,
#                 entity query index, line-of-sight air skip, path type
#                 bypass, chunk-wait guards): if only full fails, the
#                 failure is Ferrite's.
#   cost          what Bedrock players cost on the laptop model (factor 2,
#                 as in scripts/ci-players-bench.sh): three players flown
#                 over a pregenerated corridor, as Java-equivalent bench
#                 players (/ferrite bench players add) or as Bedrock bots
#                 through Geyser, arms A B B A of 60 s after a baseline.
#                 Per arm: tick time, CPU per thread group (/ferrite bench
#                 cpu: server, worldgen, geyser, network, other) and chunks
#                 received per player.
#   cost-c1       cost with Geyser's Bedrock compression-level at 1 instead
#                 of its default: about half of Geyser's CPU is its RakNet
#                 threads, which compress what goes to Bedrock clients.
#   bandwidth     bytes per player on the wire, on the laptop model, for
#                 three players standing at spawn next to husks (idle),
#                 flying at walking speed (walk) or at elytra speed
#                 (elytra) over a pregenerated corridor:
#                   - Bedrock players through Geyser at compression-level
#                     6, 1, 3 and 6 again (a restart per level, same world):
#                     UDP bytes to and from port 19132 on loopback, counted
#                     by iptables (IP and UDP headers included);
#                   - Java-equivalent bench players (blocks with level 6):
#                     what a real Java connection would carry, measured by
#                     the bench's wire meter (WireMeter: the game's own
#                     encoding and compression; no Java client library
#                     supports this version). TCP/IP headers not included.
#                 Also chunks per player, bytes per chunk, Geyser's CPU and
#                 tick time; a summary against a 20 Mbit/s upload.
#   goml          Get Off My Lawn ReServed, built from the server's fork,
#                 with Polymer (which it needs), beside Geyser and
#                 Floodgate. The Bedrock bot claims a glass platform in the
#                 air (a claim anchor it places, through /ferrite bench
#                 players use); a bench player who does not own it is the
#                 stranger. Each check has a control outside the claim:
#                 the owner can place a block in it and the stranger can't;
#                 TNT breaks stone outside but not in the claim (checked
#                 after a restart with Lithium's explosion raycast off,
#                 which adds broken blocks after GOML's filter; with it on,
#                 the result is recorded); water from outside does not flow in but flows
#                 the other way; a pen of husks in the claim crams; the bot
#                 stays connected and then gets chunks all through a 90 s
#                 flight over fresh terrain. No mixin error from any mod.
#   goml-cost     what GOML and Polymer cost on the laptop model: blocks
#                 none, goml, none, goml, each from a copy of one world
#                 (a pregenerated corridor and husks at spawn), with 15
#                 claims around spawn in goml blocks. Arms of 60 s with
#                 three players: Java-equivalent players standing at spawn
#                 among the husks, flying at elytra speed over the corridor
#                 and over fresh terrain (the same terrain in every block),
#                 and Bedrock players at elytra speed over the corridor.
#                 Per arm: tick time, CPU per thread group, chunks. A JFR
#                 recording per block gives the share of samples in GOML
#                 (with the libraries it bundles), in Polymer and in Ferrite.
#
# Geyser and Floodgate are fetched into run/geyser by the workflow, GOML
# and Polymer into run/goml (goml scenarios). The
# first start writes their configs; the script then sets Geyser's auth
# type to floodgate and lets it accept the bot's offline login
# (validate-bedrock-login: false), and starts again.
# Extra arguments go to gradle.
set -euo pipefail

SCENARIO=${GEYSER_SCENARIO:-full}
LOG=geyser-server.log
FLOG=run/logs/ferrite.log
REPORT=geyser-report.txt
RCON_PORT=25575
RCON_PASSWORD=ferrite-geyser
BEDROCK_PORT=19132
CPUS=${GEYSER_CPUS:-0-3}
FACTORS=2
# /ferrite bench noise 24 3 lazy-interp on an AMD EPYC 7763 runner (the
# players bench's anchor).
ANCHOR_MS=20.0
BOTS=bots
GRADLE_ARGS=("$@")

mkdir -p run/mods "$BOTS"
echo "eula=true" > run/eula.txt
cat > run/server.properties <<PROPS
online-mode=false
pause-when-empty-seconds=-1
view-distance=10
simulation-distance=10
level-seed=ferrite-worldgen
allow-flight=true
spawn-protection=0
enforce-secure-profile=false
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASSWORD
PROPS
: > "$REPORT"
echo "scenario $SCENARIO, runner $(lscpu | sed -n 's/^Model name: *//p')" | tee -a "$REPORT"

PID=
declare -A BOT_PID
fail() {
	echo "::error::$1"
	echo "::error::$1" >> "$REPORT"
	tail -n 150 "$LOG" 2>/dev/null || true
	echo "--- ferrite.log"
	tail -n 60 "$FLOG" 2>/dev/null || true
	for f in "$BOTS"/*; do
		[ -f "$f" ] || continue
		echo "--- $f"
		tail -n 20 "$f"
	done
	stop_spin
	stop_bots
	kill "$PID" 2>/dev/null || true
	exit 1
}

wait_for() {
	local pattern=$1 limit=$2 file=${3:-$LOG} waited=0
	until grep -q -- "$pattern" "$file" 2>/dev/null; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "$PID" 2>/dev/null || fail "server exited while waiting for: $pattern"
		if [ "$waited" -ge "$limit" ]; then
			diagnose_hang
			fail "timed out waiting for: $pattern"
		fi
	done
}

# What a server that does not get on is doing: the processes, the end of
# its log, and the stacks of its main threads.
diagnose_hang() {
	echo "=== processes ==="
	ps -eo pid,ppid,etime,args --forest | grep -E 'java|xvfb|Xvfb|gradle' | grep -v grep | cut -c1-300 || true
	echo "=== end of the server log ==="
	tail -n 60 "$LOG" | cut -c1-300 || true
	local pid
	pid=$(jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}')
	if [ -n "$pid" ]; then
		echo "=== threads of $pid ==="
		jcmd "$pid" Thread.print 2>/dev/null | awk '/^"(main|Server thread|Worker-Main|IO-Worker|Render|AWT)/,/^$/' | head -200 || true
	fi
}

rcon() { python3 scripts/rcon.py "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# spin, stop_spin, noise_ms, calibrate and NICE: the laptop-speed model.
source scripts/laptop-model.sh

start_server() {
	taskset -c "$CPUS" ./gradlew runServer -x buildRustLib -x copyRustDll "${GRADLE_ARGS[@]}" < /dev/null > "$LOG" 2>&1 &
	PID=$!
	wait_for 'Done (' 600
	wait_for 'RCON running' 60
	rcon "ferrite tickwatch 100" > /dev/null
}

# Any mod's mixin failure counts: Geyser, Floodgate and Krypton hook the
# network code, and Ferrite must not be what breaks them.
MIXIN_ERRORS='Mixin apply for mod [A-Za-z0-9_.-]+ failed|InvalidInjectionException|Critical injection failure|MixinApplyError|InvalidMixinException'

stop_server() {
	rcon "stop" > /dev/null || true
	wait "$PID" || true
	cat "$LOG" >> geyser-server-all.log
	if grep -E -q "$MIXIN_ERRORS" "$LOG"; then
		grep -E "$MIXIN_ERRORS" "$LOG" | head -5
		fail "mixin errors in the server log"
	fi
}

# bot_start <name> <duration-seconds> [status-every] [bedrock|java]: a test
# client (scripts/test-clients) in the background, its JSON lines in
# bots/<name>.jsonl. Bedrock clients join through Geyser, Java ones
# directly.
bot_start() {
	local client=${4:-bedrock} port=$BEDROCK_PORT
	[ "$client" = java ] && port=25565
	node "scripts/test-clients/$client.js" --name "$1" --duration "$2" --every "${3:-10}" --port "$port" \
		> "$BOTS/$1.jsonl" 2> "$BOTS/$1.err" &
	BOT_PID[$1]=$!
}

bot_wait_spawn() {
	local name=$1 limit=$2 waited=0
	until grep -q '"bot":"spawned"' "$BOTS/$name.jsonl" 2>/dev/null; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "${BOT_PID[$name]}" 2>/dev/null || fail "test client $name exited before spawning: $(tail -n 3 "$BOTS/$name.jsonl" "$BOTS/$name.err" 2>/dev/null)"
		[ "$waited" -lt "$limit" ] || fail "test client $name did not spawn in $limit s"
	done
}

# bot_field <name> <field>: the field from the bot's latest JSON line.
bot_field() {
	python3 - "$BOTS/$1.jsonl" "$2" <<'PY'
import json, sys
for line in reversed(open(sys.argv[1]).read().splitlines()):
    try:
        print(json.loads(line).get(sys.argv[2]))
        break
    except ValueError:
        continue
else:
    print("None")
PY
}

# bot_stop <name>: ends it (SIGTERM, it prints its summary); fails the job
# if it had been disconnected.
bot_stop() {
	local name=$1 code=0
	kill -TERM "${BOT_PID[$name]}" 2>/dev/null || true
	wait "${BOT_PID[$name]}" || code=$?
	unset "BOT_PID[$name]"
	grep '"bot":"summary"' "$BOTS/$name.jsonl" | tail -n 1 | sed "s/^/[$SCENARIO] /" | tee -a "$REPORT"
	[ "$code" -eq 0 ] || fail "test client $name ended with code $code (disconnected: $(bot_field "$name" disconnected))"
}

stop_bots() {
	local name
	for name in "${!BOT_PID[@]}"; do
		kill -TERM "${BOT_PID[$name]}" 2>/dev/null || true
	done
}

# The Java name Floodgate gave a bot (its prefix, then the Bedrock name).
java_name() {
	rcon "list" | tr ' ,:' '\n\n\n' | grep -m 1 -- "$1\$" || true
}

# First start: Geyser and Floodgate write their configs.
configure_geyser() {
	cp run/geyser/*.jar run/mods/ || fail "no Geyser or Floodgate jar in run/geyser"
	start_server
	sleep 10
	stop_server
	GEYSER_CFG=$(find run/config -maxdepth 2 -iname 'config.yml' -ipath '*geyser*' | head -1)
	local cfg=$GEYSER_CFG
	[ -n "$cfg" ] || fail "Geyser wrote no config under run/config"
	echo "Geyser config: $cfg" | tee -a "$REPORT"
	GEYSER_COMPRESSION=${GEYSER_COMPRESSION:-} python3 - "$cfg" <<'PY' | tee -a "$REPORT"
import os, re, sys
path = sys.argv[1]
lines = open(path).read().split("\n")
want = {"auth-type": "floodgate", "validate-bedrock-login": "false"}
if os.environ.get("GEYSER_COMPRESSION"):
    want["compression-level"] = os.environ["GEYSER_COMPRESSION"]
done = set()
for i, line in enumerate(lines):
    m = re.match(r"^(\s*)([a-z-]+):(\s*)(.*)$", line)
    if m and m.group(2) in want:
        lines[i] = f"{m.group(1)}{m.group(2)}: {want[m.group(2)]}"
        done.add(m.group(2))
if "validate-bedrock-login" not in done:
    # Not written out: put it in the advanced bedrock section, where
    # Geyser reads it (GeyserConfig$AdvancedBedrockConfig).
    adv = next((i for i, l in enumerate(lines) if re.match(r"^advanced:\s*$", l)), None)
    if adv is None:
        lines += ["advanced:", "  bedrock:", "    validate-bedrock-login: false"]
    else:
        bed = next((i for i in range(adv + 1, len(lines))
                    if re.match(r"^  bedrock:\s*$", lines[i]) or re.match(r"^\S", lines[i])), None)
        if bed is not None and lines[bed].startswith("  bedrock:"):
            lines.insert(bed + 1, "    validate-bedrock-login: false")
        else:
            lines[adv + 1:adv + 1] = ["  bedrock:", "    validate-bedrock-login: false"]
    done.add("validate-bedrock-login (added)")
open(path, "w").write("\n".join(lines))
print("geyser config set:", ", ".join(sorted(done)))
if "auth-type" not in done:
    sys.exit("no auth-type in the Geyser config")
if "compression-level" in want and "compression-level" not in done:
    sys.exit("no compression-level in the Geyser config")
PY
	grep -nE 'auth-type|validate-bedrock-login|port:|compression' "$cfg" | tee -a "$REPORT" || true
}

compat() {
	configure_geyser
	start_server
	grep -iE 'geyser|floodgate' "$LOG" | grep -viE 'debug' | head -20 | tee -a "$REPORT" || true
	if [ "$SCENARIO" = features-off ]; then
		rcon "ferrite cramming off" "ferrite entityquery index off" "ferrite entityquery typed-grid off" \
			"ferrite entityquery collider-sections off" "ferrite raycast air-skip off" \
			"ferrite ai pathtype-bypass off" "ferrite compat all off" | tee -a "$REPORT"
	fi

	bot_start FerriteBot1 600
	bot_wait_spawn FerriteBot1 120
	local player
	player=$(java_name FerriteBot1)
	[ -n "$player" ] || fail "FerriteBot1 spawned but is not in /list"
	echo "[$SCENARIO] Bedrock bot joined as $player, protocol $(bot_field FerriteBot1 version)" | tee -a "$REPORT"

	# Commands: the /ferrite tree, once the bot may use it.
	rcon "gamemode creative $player" "op $player" > /dev/null
	local waited=0
	until grep -q '"has_ferrite_command":true' "$BOTS/FerriteBot1.jsonl"; do
		sleep 1
		waited=$((waited + 1))
		[ "$waited" -lt 30 ] || fail "the bot's command list has no /ferrite after op"
	done
	echo "[$SCENARIO] command list after op: $(bot_field FerriteBot1 commands) commands, /ferrite included" | tee -a "$REPORT"

	# Mobs around a Bedrock player: a pen of 30 husks that crams, and 20
	# husks free nearby.
	local cmds=("execute at $player run fill ~4 ~ ~4 ~6 ~4 ~6 minecraft:glass hollow")
	for _ in $(seq 30); do
		cmds+=("execute at $player run summon minecraft:husk ~5 ~1 ~5 {Tags:[\"geyser_pile\"],PersistenceRequired:1b}")
	done
	for _ in $(seq 20); do
		cmds+=("execute at $player run summon minecraft:husk ~-6 ~ ~-6 {Tags:[\"geyser_free\"],PersistenceRequired:1b}")
	done
	rcon "${cmds[@]}" | sort | uniq -c
	local added0 moves0
	added0=$(bot_field FerriteBot1 entities_added)
	moves0=$(bot_field FerriteBot1 entity_moves)
	sleep 45
	local out count
	out=$(rcon "execute if entity @e[type=minecraft:husk,tag=geyser_pile]")
	count=$(echo "$out" | sed -n 's/.*[Cc]ount: \([0-9]*\).*/\1/p')
	echo "$out" | grep -q 'Test failed' && count=0
	echo "[$SCENARIO] husk pile: ${count:-?} of 30 left after 45 s" | tee -a "$REPORT"
	[ -n "$count" ] && [ "$count" -lt 30 ] || fail "no cramming deaths next to the Bedrock player"
	local added1 moves1
	added1=$(bot_field FerriteBot1 entities_added)
	moves1=$(bot_field FerriteBot1 entity_moves)
	echo "[$SCENARIO] entities added $added0 -> $added1, entity moves $moves0 -> $moves1" | tee -a "$REPORT"
	[ "$((added1 - added0))" -ge 40 ] || fail "the bot saw fewer than 40 of the 50 husks"
	[ "$((moves1 - moves0))" -gt 0 ] || fail "the bot saw no entity move"

	# Fluids: water on a glass pad 40 blocks above the bot spreads.
	local bpos water bx by bz
	bpos=$(entity_pos "$player")
	read -r bx by bz <<< "$bpos"
	water=$(water_probe "$bx" "$((by + 40))" "$bz")
	echo "[$SCENARIO] water placed above the bot: $water" | tee -a "$REPORT"
	[ "$water" = spread ] || fail "water placed above the bot did not spread"

	# Flight through fresh terrain, with the chunk-wait guards (full).
	local chunks0 chunks1
	chunks0=$(bot_field FerriteBot1 chunks)
	rcon "ferrite bench players drive $player elytra 20000 0 -90" | tee -a "$REPORT"
	sleep 90
	rcon "ferrite bench players status" | sed "s/^/[$SCENARIO] /" | tee -a "$REPORT"
	rcon "ferrite bench players clear" > /dev/null
	sleep 10
	chunks1=$(bot_field FerriteBot1 chunks)
	echo "[$SCENARIO] chunks received: $chunks0 before the flight, $chunks1 after" | tee -a "$REPORT"
	[ "$((chunks1 - chunks0))" -ge 100 ] || fail "fewer than 100 chunks reached the Bedrock player in a 90 s flight"
	rcon "ferrite compat status" | sed "s/^/[$SCENARIO] /" | tee -a "$REPORT"

	bot_stop FerriteBot1
	stop_server
}

# pregen_corridor <block x> <squares>: chunks east from x, 21 wide (z
# centred on 0), in squares of 21x21 by /ferrite pregen.
pregen_corridor() {
	local cx=$(( $1 >> 4 )) k before waited
	echo "[pregen] corridor from chunk x $cx, $2 squares, $(date -u +%H:%M:%S) UTC" | tee -a "$REPORT"
	for k in $(seq 0 $(($2 - 1))); do
		before=$(grep -c "ferrite-pregen\] complete" "$FLOG" || true)
		rcon "ferrite pregen at $((cx + 10 + 21 * k)) 0 10" > /dev/null
		waited=0
		until [ "$(grep -c "ferrite-pregen\] complete" "$FLOG" || true)" -gt "$before" ]; do
			sleep 2
			waited=$((waited + 2))
			[ "$waited" -lt 600 ] || fail "pregen square $k did not finish"
		done
	done
	echo "[pregen] corridor done $(date -u +%H:%M:%S) UTC" | tee -a "$REPORT"
}

# Three players on one pregenerated corridor, 64 blocks apart along it,
# flying east (elytra, 33 b/s, y 200) for 60 s.
COST_X=40000
COST_START=$((COST_X + 176))
COST_SECONDS=60

cost_java() {
	local label=$1
	spin "${NICE[2]}"
	rcon "ferrite bench cpu reset" > /dev/null
	python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" players "$label" "$COST_SECONDS" \
		"elytra:$COST_START:0:-90" "elytra:$((COST_START + 64)):0:-90" "elytra:$((COST_START + 128)):0:-90" \
		| grep -vE "rcon round trip|removed|joined" | tee -a "$REPORT"
	rcon "ferrite bench cpu status" | sed "s/^/[$label] /" | tee -a "$REPORT"
	spin off
	sleep 30
}

cost_bedrock() {
	local label=$1 i name player c0 c1
	for i in 1 2 3; do bot_start "CostBot$i" 400 2; done
	for i in 1 2 3; do bot_wait_spawn "CostBot$i" 120; done
	sleep 5
	spin "${NICE[2]}"
	rcon "ferrite bench cpu reset" > /dev/null
	declare -A c0s
	for i in 1 2 3; do
		name="CostBot$i"
		player=$(java_name "$name")
		[ -n "$player" ] || fail "$name is not in /list"
		rcon "gamemode creative $player" > /dev/null
		c0s[$name]=$(bot_field "$name" chunks)
		rcon "ferrite bench players drive $player elytra $((COST_START + 64 * (i - 1))) 0 -90" > /dev/null
	done
	python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" sample "$label" "$COST_SECONDS" | tee -a "$REPORT"
	rcon "ferrite bench cpu status" | sed "s/^/[$label] /" | tee -a "$REPORT"
	sleep 2
	for i in 1 2 3; do
		name="CostBot$i"
		c1=$(bot_field "$name" chunks)
		echo "[$label] $name chunks received in the arm: $((c1 - ${c0s[$name]})) ($(python3 -c "print(round(($c1 - ${c0s[$name]}) / $COST_SECONDS, 1))")/s)" | tee -a "$REPORT"
	done
	rcon "ferrite bench players clear" > /dev/null
	spin off
	for i in 1 2 3; do bot_stop "CostBot$i"; done
	sleep 24
}

cost() {
	sudo -n sysctl -qw kernel.sched_autogroup_enabled=0 2>/dev/null || echo "could not turn scheduler autogroups off"
	configure_geyser
	start_server
	calibrate
	pregen_corridor "$COST_X" 8
	# Warm-up, not measured: the first Bedrock join (Geyser's own start-up
	# work) and the JIT.
	bot_start WarmBot 120
	bot_wait_spawn WarmBot 120
	sleep 20
	bot_stop WarmBot
	spin "${NICE[2]}"
	rcon "ferrite bench cpu reset" > /dev/null
	python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" sample baseline "$COST_SECONDS" | tee -a "$REPORT"
	rcon "ferrite bench cpu status" | sed "s/^/[baseline] /" | tee -a "$REPORT"
	spin off
	cost_java java-a
	cost_bedrock bedrock-a
	cost_bedrock bedrock-b
	cost_java java-b
	stop_server
}

# --- bandwidth -------------------------------------------------------------
BW_SECONDS=60
BW_X=40000
BW_START=$((BW_X + 176))
BW_SPAWN_X=
BW_SPAWN_Z=
# iptables counting rules (no target: they only count), on loopback.
BW_RULES=(
	"bedrock-down -p udp --sport $BEDROCK_PORT"
	"bedrock-up -p udp --dport $BEDROCK_PORT"
)

bw_setup() {
	sudo -n iptables -L OUTPUT -n > /dev/null 2>&1 || fail "iptables is not usable here (it needs sudo)"
	local rule
	for rule in "${BW_RULES[@]}"; do
		# shellcheck disable=SC2086
		sudo -n iptables -I OUTPUT -o lo ${rule#* } -m comment --comment "ferrite-bw-${rule%% *}"
	done
	sudo -n iptables -L OUTPUT -v -x -n | tee -a "$REPORT"
}

bw_zero() { sudo -n iptables -Z OUTPUT; }

# bw_bytes <rule name>: bytes counted since the last bw_zero.
bw_bytes() {
	sudo -n iptables -L OUTPUT -v -x -n | python3 -c '
import sys
want = "/* ferrite-bw-" + sys.argv[1] + " */"
for line in sys.stdin:
    if want in line:
        print(line.split()[1])
        break
else:
    print(0)' "$1"
}

set_geyser_level() {
	sed -i -E "s/^([[:space:]]*compression-level:).*/\1 $1/" "$GEYSER_CFG"
	grep -n 'compression-level' "$GEYSER_CFG" | sed "s/^/[bandwidth] /" | tee -a "$REPORT"
}

# One machine-readable line per arm, for the summaries:
# bw_record <client> <level> <activity> <down bytes> <up bytes> <chunks>
# <worldgen-drive.py sample output> <ferrite bench cpu status output>.
# BW_SET tags the line (goml-cost: none or goml).
bw_record() {
	local mspt p95
	mspt=$(echo "$7" | sed -n 's/.*mspt mean \([0-9.]*\).*/\1/p')
	p95=$(echo "$7" | sed -n 's/.*p95 mean \([0-9.]*\).*/\1/p')
	echo "[bw] set=${BW_SET:-} client=$1 level=$2 activity=$3 players=3 seconds=$BW_SECONDS down_bytes=$4 up_bytes=$5 chunks=$6" \
		"mspt=${mspt:-0} p95=${p95:-0} server_ms_s=$(echo "$8" | cpu_field server) worldgen_ms_s=$(echo "$8" | cpu_field worldgen)" \
		"geyser_ms_s=$(echo "$8" | cpu_field geyser) total_ms_s=$(echo "$8" | cpu_field total)" | tee -a "$REPORT"
}

cpu_field() { sed -n "s/.* $1=[0-9]* ms (\([0-9.]*\) ms\/s).*/\1/p" | head -1; }

# bot_try_spawn <name> <limit>: as bot_wait_spawn, but returns 1 instead of
# failing the job.
bot_try_spawn() {
	local name=$1 limit=$2 waited=0
	until grep -q '"bot":"spawned"' "$BOTS/$name.jsonl" 2>/dev/null; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "${BOT_PID[$name]}" 2>/dev/null || return 1
		[ "$waited" -lt "$limit" ] || return 1
	done
}

# bot_wait_or_retry <name> <duration> <every>: waits for a started client to
# spawn; if it could not join, records why (the report counts them) and
# starts it once more, which must then join.
JOIN_RETRIES=0
bot_wait_or_retry() {
	bot_try_spawn "$1" 120 && return 0
	JOIN_RETRIES=$((JOIN_RETRIES + 1))
	echo "::warning::$1 could not join: $(grep -hE 'disconnect|Error|error' "$BOTS/$1.jsonl" | tail -n 1 | cut -c1-300)" | tee -a "$REPORT"
	kill -TERM "${BOT_PID[$1]}" 2>/dev/null || true
	wait "${BOT_PID[$1]}" 2>/dev/null || true
	unset "BOT_PID[$1]"
	mv "$BOTS/$1.jsonl" "$BOTS/$1.failed-$JOIN_RETRIES.jsonl"
	bot_start "$1" "$2" "$3" bedrock
	bot_wait_spawn "$1" 120
}

bw_warmup() {
	bot_start WarmBot 60 10 bedrock
	bot_wait_or_retry WarmBot 60 10
	sleep 20
	bot_stop WarmBot
	sleep 5
}

# bw_arm_bedrock <label> <level> <idle|walk|elytra>
bw_arm_bedrock() {
	local label=$1 level=$2 activity=$3 i name player out cpu down up chunks=0 c
	declare -A c0
	for i in 1 2 3; do bot_start "BwBot$i" 400 2 bedrock; done
	for i in 1 2 3; do bot_wait_or_retry "BwBot$i" 400 2; done
	for i in 1 2 3; do
		name="BwBot$i"
		player=$(java_name "$name")
		[ -n "$player" ] || fail "$name is not in /list"
		rcon "gamemode creative $player" > /dev/null
		if [ -z "$BW_SPAWN_X" ]; then
			read -r BW_SPAWN_X BW_SPAWN_Z < <(rcon "data get entity $player Pos" | python3 -c '
import re, sys
v = re.findall(r"(-?[0-9.]+)d", sys.stdin.read())
print(int(float(v[0])), int(float(v[2])))')
			echo "[bandwidth] spawn at $BW_SPAWN_X $BW_SPAWN_Z" | tee -a "$REPORT"
		fi
		if [ "$activity" != idle ]; then
			rcon "ferrite bench players drive $player $activity $((BW_START + 32 * (i - 1))) 0 -90" > /dev/null
		fi
	done
	# Arrival: the view around the start loads before the count starts.
	sleep 12
	spin "${NICE[2]}"
	for i in 1 2 3; do
		c0[$i]=$(( $(bot_field "BwBot$i" chunks) - $(bot_field "BwBot$i" chunks_empty) ))
	done
	bw_zero
	rcon "ferrite bench cpu reset" > /dev/null
	out=$(python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" sample "$label" "$BW_SECONDS")
	down=$(bw_bytes bedrock-down)
	up=$(bw_bytes bedrock-up)
	cpu=$(rcon "ferrite bench cpu status")
	echo "$out" | tee -a "$REPORT"
	echo "[$label] $cpu" | cut -c1-400 | tee -a "$REPORT"
	sleep 2
	for i in 1 2 3; do
		c=$(( $(bot_field "BwBot$i" chunks) - $(bot_field "BwBot$i" chunks_empty) ))
		chunks=$((chunks + c - ${c0[$i]}))
	done
	spin off
	rcon "ferrite bench players clear" > /dev/null
	for i in 1 2 3; do bot_stop "BwBot$i"; done
	bw_record bedrock "$level" "$activity" "$down" "$up" "$chunks" "$out" "$cpu"
	sleep 15
}

# bw_arm_java <label> <idle|walk|elytra> [x z [tag]]: Java-equivalent bench
# players, measured by the wire meter, from x z (default: the corridor; idle
# stands at spawn). tag names the arm in the record (default the activity).
# BW_METER=off leaves the meter off (it encodes on the server thread, which
# would weigh on a CPU comparison); chunks are then counted from status.
bw_arm_java() {
	local label=$1 activity=$2 sx=${3:-$BW_START} sz=${4:-0} tag=${5:-$2} meter=${BW_METER:-on}
	local i x z out status cpu total c0 bytes chunks
	if [ "$meter" = on ]; then rcon "ferrite bench players meter on" > /dev/null; fi
	for i in 1 2 3; do
		if [ "$activity" = idle ]; then
			x=$BW_SPAWN_X
			z=$BW_SPAWN_Z
		else
			x=$((sx + 32 * (i - 1)))
			z=$sz
		fi
		rcon "ferrite bench players add $activity $x $z -90" > /dev/null
	done
	sleep 12
	spin "${NICE[2]}"
	if [ "$meter" = on ]; then rcon "ferrite bench players meter reset" > /dev/null; fi
	c0=$(rcon "ferrite bench players status" | sed -n 's/.*total chunks_sent=\([0-9]*\).*/\1/p')
	rcon "ferrite bench cpu reset" > /dev/null
	out=$(python3 scripts/worldgen-drive.py "$RCON_PORT" "$RCON_PASSWORD" sample "$label" "$BW_SECONDS")
	status=$(rcon "ferrite bench players status")
	cpu=$(rcon "ferrite bench cpu status")
	spin off
	echo "$out" | tee -a "$REPORT"
	echo "$status" | sed "s/^/[$label] /" | tee -a "$REPORT"
	echo "[$label] $cpu" | cut -c1-400 | tee -a "$REPORT"
	total=$(echo "$status" | grep 'total chunks_sent')
	if [ "$meter" = on ]; then
		echo "$total" | grep -q 'encode_failures=0' || echo "::warning::wire meter could not encode some packets: $total"
		bytes=$(echo "$total" | sed -n 's/.*wire_bytes=\([0-9]*\).*/\1/p')
		chunks=$(echo "$total" | sed -n 's/.*wire_chunks=\([0-9]*\).*/\1/p')
		rcon "ferrite bench players clear" "ferrite bench players meter off" > /dev/null
	else
		bytes=0
		chunks=$(( $(echo "$total" | sed -n 's/.*total chunks_sent=\([0-9]*\).*/\1/p') - ${c0:-0} ))
		rcon "ferrite bench players clear" > /dev/null
	fi
	bw_record java - "$tag" "$bytes" 0 "$chunks" "$out" "$cpu"
	sleep 15
}

bw_block() {
	local label=$1 level=$2 java=$3 act
	for act in idle walk elytra; do bw_arm_bedrock "$label-bedrock-L$level-$act" "$level" "$act"; done
	if [ "$java" = yes ]; then
		for act in idle walk elytra; do bw_arm_java "$label-java-$act" "$act"; done
	fi
}

bw_summary() {
	echo "=== bandwidth per player (Mbit/s down to the player), against a 20 Mbit/s upload ===" | tee -a "$REPORT"
	python3 - "$REPORT" <<'PY' | tee -a "$REPORT"
import re, sys
from collections import defaultdict
rows = defaultdict(list)
for line in open(sys.argv[1]):
    if line.startswith("[bw] "):
        f = dict(re.findall(r"(\w+)=(\S*)", line))
        rows[(f["client"], f["level"], f["activity"])].append(f)
order = {"idle": 0, "walk": 1, "elytra": 2}
print("client   level activity   Mbit/s  up kbit/s  chunks/s  KB/chunk  fit in 20  geyser ms/s   mspt  runs (Mbit/s)")
for key in sorted(rows, key=lambda k: (k[0] != "bedrock", k[1], order.get(k[2], 9))):
    rs = rows[key]
    n = len(rs)
    secs = float(rs[0]["seconds"])
    players = int(rs[0]["players"])
    down = sum(int(r["down_bytes"] or 0) for r in rs) / n
    up = sum(int(r["up_bytes"] or 0) for r in rs) / n
    chunks = sum(int(r["chunks"] or 0) for r in rs) / n
    mbit = down * 8 / secs / players / 1e6
    upk = up * 8 / secs / players / 1e3 if key[0] == "bedrock" else float("nan")
    cps = chunks / secs / players
    kbc = down / chunks / 1000 if chunks else float("nan")
    fit = int(20 / mbit) if mbit > 0 else 0
    gey = sum(float(r["geyser_ms_s"] or 0) for r in rs) / n / players if key[0] == "bedrock" else float("nan")
    mspt = sum(float(r["mspt"] or 0) for r in rs) / n
    runs = " ".join("%.2f" % (int(r["down_bytes"] or 0) * 8 / secs / players / 1e6) for r in rs)
    print("%-8s %-5s %-8s %8.2f %10.1f %9.1f %9.1f %10d %12.1f %6.1f  %s"
          % (key[0], key[1], key[2], mbit, upk, cps, kbc, fit, gey, mspt, runs))
PY
}

bandwidth() {
	sudo -n sysctl -qw kernel.sched_autogroup_enabled=0 2>/dev/null || echo "could not turn scheduler autogroups off"
	GEYSER_COMPRESSION=6 configure_geyser
	start_server
	calibrate
	pregen_corridor "$BW_X" 9
	bw_setup
	# Husks at spawn for the idle arms (the console runs at world spawn).
	local cmds=("fill ~4 ~ ~4 ~6 ~4 ~6 minecraft:glass hollow")
	for _ in $(seq 30); do
		cmds+=("summon minecraft:husk ~5 ~1 ~5 {PersistenceRequired:1b}")
	done
	for _ in $(seq 20); do
		cmds+=("summon minecraft:husk ~-6 ~ ~-6 {PersistenceRequired:1b}")
	done
	rcon "${cmds[@]}" | sort | uniq -c | tee -a "$REPORT"
	bw_warmup
	bw_block A 6 yes
	stop_server
	local spec label level java
	for spec in "B 1 no" "C 3 no" "D 6 yes"; do
		read -r label level java <<< "$spec"
		set_geyser_level "$level"
		start_server
		bw_warmup
		bw_block "$label" "$level" "$java"
		stop_server
	done
	bw_summary
}

# --- Get Off My Lawn ---------------------------------------------------------
GOML_DIR=run/goml
GOML_ANCHOR=goml:makeshift_claim_anchor
# JfrShare groups: GOML with the libraries its jar bundles, Polymer, and
# Ferrite for reference. "$goml$" and the like catch mixin handlers, which
# are merged into game classes under the mod's id.
JFR_GROUPS=(
	'goml=draylar.goml,$goml$,com.jamieswhiteshirt.rtree3i,org.ladysnake.cca,io.github.ladysnake.pal,eu.pb4.sgui,eu.pb4.placeholders,eu.pb4.common.protection,xyz.nucleoid.server.translations'
	'polymer=eu.pb4.polymer,$polymer'
	'ferrite=me.apika,$ferrite$'
)
GC_FRESH_Z=3000
GC_SPAWN_Y=

game_pid() { jcmd -l | awk '/devlaunchinjector|KnotServer|knot/ {print $1; exit}'; }

# goml_mods on|off: GOML and Polymer in run/mods, or not.
goml_mods() {
	local j
	for j in "$GOML_DIR"/*.jar; do
		[ -f "$j" ] || fail "no GOML or Polymer jar in $GOML_DIR"
		if [ "$1" = on ]; then cp "$j" run/mods/; else rm -f "run/mods/$(basename "$j")"; fi
	done
}

# The mods in run/goml, the jars nested in them, and their common and
# server mixins.
goml_report() {
	python3 - "$GOML_DIR" <<'PY' | tee -a "$REPORT"
import io, json, os, sys, zipfile
def walk(name, zf, depth):
    pad = "  " * depth
    try:
        meta = json.loads(zf.read("fabric.mod.json").decode("utf-8", "replace"), strict=False)
    except (KeyError, ValueError):
        meta = None
    if meta:
        print(f"[goml] {pad}{meta.get('id')} {meta.get('version')} ({name})")
        for m in meta.get("mixins", []):
            c = m if isinstance(m, str) else m.get("config")
            if isinstance(m, dict) and m.get("environment") == "client":
                continue
            try:
                cfg = json.loads(zf.read(c).decode("utf-8", "replace"), strict=False)
            except (KeyError, ValueError, TypeError):
                continue
            ms = sorted(cfg.get("mixins", []) + cfg.get("server", []))
            print(f"[goml] {pad}  {c} ({cfg.get('package')}, {len(ms)}): {', '.join(ms)}")
    for n in zf.namelist():
        if n.startswith("META-INF/jars/") and n.endswith(".jar"):
            walk(os.path.basename(n), zipfile.ZipFile(io.BytesIO(zf.read(n))), depth + 1)
for f in sorted(os.listdir(sys.argv[1])):
    if f.endswith(".jar"):
        walk(f, zipfile.ZipFile(os.path.join(sys.argv[1], f)), 0)
PY
}

block_is() { rcon "execute if block $1 $2 $3 $4" | grep -q 'Test passed'; }

# water_probe <x> <y> <z>: a water source on a 5x5 glass pad at x y z (the
# water one block above it); prints "spread" if it reached a neighbour
# within 10 s, else "stuck".
water_probe() {
	rcon "fill $(($1 - 2)) $2 $(($3 - 2)) $(($1 + 2)) $2 $(($3 + 2)) minecraft:glass" \
		"fill $(($1 - 2)) $(($2 + 1)) $(($3 - 2)) $(($1 + 2)) $(($2 + 2)) $(($3 + 2)) minecraft:air" \
		"setblock $1 $(($2 + 1)) $3 minecraft:water" > /dev/null
	sleep 10
	if block_is $(($1 + 1)) $(($2 + 1)) "$3" minecraft:water || block_is $(($1 - 1)) $(($2 + 1)) "$3" minecraft:water; then
		echo spread
	else
		echo stuck
	fi
}

# entity_pos <name or selector>: its block position, "x y z".
entity_pos() {
	rcon "data get entity $1 Pos" | python3 -c '
import math, re, sys
v = re.findall(r"(-?[0-9.]+)d", sys.stdin.read())
print(*(math.floor(float(x)) for x in v[:3]))'
}

# spawn_pos: the world spawn, where the console's commands run, "x y z".
spawn_pos() {
	rcon 'summon minecraft:marker ~ ~ ~ {Tags:["ferrite_spawn"]}' > /dev/null
	entity_pos '@e[type=minecraft:marker,tag=ferrite_spawn,limit=1]'
	rcon 'kill @e[type=minecraft:marker,tag=ferrite_spawn]' > /dev/null
}

# bench_player <x> <z>: a bench player standing at x z; prints its name.
bench_player() {
	rcon "ferrite bench players add idle $1 $2 0" | sed -n 's/.*\] \(bench[0-9]*\) joined.*/\1/p'
}

# claim_at <player> <x> <y> <z>: the player places a makeshift claim anchor
# (radius 10) on the block at x y z. The anchor stands at y+1, and the
# claim covers 10 blocks around it every way. An anchor without a claim
# removes itself, so one still there 3 s later is a claim.
claim_at() {
	local out
	out=$(rcon "ferrite bench players use $1 $2 $3 $4 $GOML_ANCHOR")
	echo "[$SCENARIO] $out" | tee -a "$REPORT"
	sleep 3
	block_is "$2" "$(($3 + 1))" "$4" "$GOML_ANCHOR" || fail "no claim anchor at $2 $(($3 + 1)) $4 after placing it: $out"
}

# tnt_test <bx> <y> <z inside> <z outside>: primed TNT in a ring of stone
# 6 blocks east of the claim's anchor column (inside the claim) and 30
# blocks east (outside), at the given z; prints what became of the stone
# beside each.
tnt_test() {
	local bx=$1 y=$2 spot cx cz inside outside
	for spot in "$(($1 + 6)) $3" "$(($1 + 30)) $4"; do
		read -r cx cz <<< "$spot"
		rcon "fill $((cx - 1)) $((y + 1)) $((cz - 1)) $((cx + 1)) $((y + 1)) $((cz + 1)) minecraft:stone" \
			"setblock $cx $((y + 1)) $cz minecraft:air" \
			"summon minecraft:tnt $cx $((y + 1)) $cz {fuse:0}" > /dev/null
	done
	sleep 3
	inside=broken
	if block_is $((bx + 5)) $((y + 1)) "$3" minecraft:stone && block_is $((bx + 7)) $((y + 1)) "$3" minecraft:stone; then
		inside=kept
	fi
	outside=broken
	if block_is $((bx + 29)) $((y + 1)) "$4" minecraft:stone && block_is $((bx + 31)) $((y + 1)) "$4" minecraft:stone; then
		outside=kept
	fi
	echo "inside $inside, outside $outside"
}

goml() {
	goml_mods on
	goml_report
	configure_geyser
	start_server
	grep -iE 'goml|get off my lawn|polymer' "$LOG" | grep -viE 'debug' | head -20 | tee -a "$REPORT" || true

	bot_start FerriteBot1 900
	bot_wait_spawn FerriteBot1 120
	local player stranger bx by bz y out count errs chunks0 chunks1 tnt_lithium tnt_raycast_off
	player=$(java_name FerriteBot1)
	[ -n "$player" ] || fail "FerriteBot1 spawned but is not in /list"
	rcon "op $player" "gamemode creative $player" > /dev/null
	read -r bx by bz < <(entity_pos "$player")
	echo "[goml] Bedrock bot $player at $bx $by $bz" | tee -a "$REPORT"

	# A glass platform 40 blocks above the bot, the air above it cleared.
	# The bot claims its west part: x and z within 10 of bx bz, y within 10
	# of the anchor at y+1.
	y=$((by + 40))
	rcon "fill $((bx - 14)) $((y + 1)) $((bz - 14)) $((bx + 40)) $((y + 6)) $((bz + 14)) minecraft:air" \
		"fill $((bx - 14)) $y $((bz - 14)) $((bx + 40)) $y $((bz + 14)) minecraft:glass" | sed "s/^/[goml] /" | tee -a "$REPORT"
	# Fluids before any claim exists (GOML and Polymer loaded).
	out=$(water_probe "$((bx - 30))" "$y" "$bz")
	echo "[goml] water before any claim: $out" | tee -a "$REPORT"
	claim_at "$player" "$bx" "$y" "$bz"
	out=$(water_probe "$((bx - 30))" "$y" "$((bz + 12))")
	echo "[goml] water after the claim: $out" | tee -a "$REPORT"
	stranger=$(bench_player "$((bx + 30))" "$bz")
	[ -n "$stranger" ] || fail "no bench player to be the stranger"
	out=$(water_probe "$((bx - 30))" "$y" "$((bz - 12))")
	echo "[goml] water after a bench player joined: $out" | tee -a "$REPORT"

	# Placing a block: the owner can, a stranger can't; outside, the stranger can.
	rcon "ferrite bench players use $player $((bx + 3)) $y $((bz - 3)) minecraft:stone" \
		"ferrite bench players use $stranger $((bx + 3)) $y $((bz + 3)) minecraft:stone" \
		"ferrite bench players use $stranger $((bx + 20)) $y $((bz + 8)) minecraft:stone" | sed "s/^/[goml] /" | tee -a "$REPORT"
	block_is $((bx + 3)) $((y + 1)) $((bz - 3)) minecraft:stone || fail "the claim's owner could not place a block in it"
	block_is $((bx + 3)) $((y + 1)) $((bz + 3)) minecraft:stone && fail "a stranger placed a block in the claim"
	block_is $((bx + 20)) $((y + 1)) $((bz + 8)) minecraft:stone || fail "the stranger could not place a block outside the claim (the control)"
	echo "[goml] placing: the owner in the claim yes, a stranger in it no, the stranger outside yes" | tee -a "$REPORT"

	# Water 2 blocks east of the claim's edge (x bx+10), in the platform's
	# middle row: water runs only toward a drop within four blocks, so it
	# must be farther than that from the platform's edges (and before any
	# TNT, whose craters are drops too) to spread every way.
	rcon "setblock $((bx + 12)) $((y + 1)) $bz minecraft:water" > /dev/null
	sleep 15
	local cells="" dx
	for dx in 10 11 12 13 14; do
		if block_is $((bx + dx)) $((y + 1)) "$bz" minecraft:water; then cells+=" bx+$dx:water"; else cells+=" bx+$dx:-"; fi
	done
	echo "[goml] water by the claim's edge (x bx+10 is the last claimed):$cells" | tee -a "$REPORT"
	block_is $((bx + 11)) $((y + 1)) "$bz" minecraft:water || fail "water did not spread toward the claim (the control)"
	block_is $((bx + 14)) $((y + 1)) "$bz" minecraft:water || fail "water did not spread away from the claim (the control)"
	block_is $((bx + 10)) $((y + 1)) "$bz" minecraft:water && fail "water flowed into the claim"
	echo "[goml] water: stopped at the claim's edge, spread outside" | tee -a "$REPORT"

	# TNT inside the claim and outside it. Lithium's explosion raycast adds
	# the blocks an explosion breaks at the end of the same method where
	# GOML removes claimed ones, after GOML's hook (priority 800) has run:
	# recorded here, and checked again below with that Lithium mixin off.
	tnt_lithium=$(tnt_test "$bx" "$y" "$((bz + 6))" "$((bz - 8))")
	echo "[goml] TNT with Lithium's explosion raycast on: $tnt_lithium" | tee -a "$REPORT"
	out=$(water_probe "$((bx - 30))" "$y" "$((bz - 24))")
	echo "[goml] water after the TNT: $out" | tee -a "$REPORT"

	# A pen of 30 husks in the claim.
	local cmds=("fill $((bx - 7)) $((y + 1)) $((bz - 7)) $((bx - 5)) $((y + 5)) $((bz - 5)) minecraft:glass hollow")
	for _ in $(seq 30); do
		cmds+=("summon minecraft:husk $((bx - 6)) $((y + 2)) $((bz - 6)) {Tags:[\"goml_pile\"],PersistenceRequired:1b}")
	done
	rcon "${cmds[@]}" | sort | uniq -c
	sleep 45
	out=$(rcon "execute if entity @e[type=minecraft:husk,tag=goml_pile]")
	count=$(echo "$out" | sed -n 's/.*[Cc]ount: \([0-9]*\).*/\1/p')
	echo "$out" | grep -q 'Test failed' && count=0
	echo "[goml] husk pile in the claim: ${count:-?} of 30 left after 45 s" | tee -a "$REPORT"
	[ -n "$count" ] && [ "$count" -lt 30 ] || fail "no cramming deaths in the claim"

	# The Bedrock player, next to the claim's anchor (a Polymer block).
	errs=$(bot_field FerriteBot1 errors)
	echo "[goml] bot: chunks $(bot_field FerriteBot1 chunks), entities added $(bot_field FerriteBot1 entities_added), errors $errs" | tee -a "$REPORT"
	[ "$(bot_field FerriteBot1 disconnected)" = None ] || fail "the bot was disconnected next to the claim"
	[ "$errs" = "[]" ] || fail "the Bedrock client logged errors: $errs"

	# Flight through fresh terrain.
	chunks0=$(bot_field FerriteBot1 chunks)
	rcon "ferrite bench players drive $player elytra 20000 0 -90" | tee -a "$REPORT"
	sleep 90
	rcon "ferrite bench players status" | sed "s/^/[goml] /" | tee -a "$REPORT"
	rcon "ferrite bench players clear" > /dev/null
	sleep 10
	chunks1=$(bot_field FerriteBot1 chunks)
	echo "[goml] chunks received: $chunks0 before the flight, $chunks1 after" | tee -a "$REPORT"
	[ "$((chunks1 - chunks0))" -ge 100 ] || fail "fewer than 100 chunks reached the Bedrock player in a 90 s flight"
	rcon "ferrite compat status" | sed "s/^/[goml] /" | tee -a "$REPORT"

	bot_stop FerriteBot1
	stop_server

	# TNT again with Lithium's explosion raycast off: the claim must hold.
	echo "mixin.world.explosions.block_raycast=false" >> run/config/lithium.properties
	start_server
	rcon "forceload add $((bx - 14)) $((bz - 14)) $((bx + 40)) $((bz + 14))" | sed "s/^/[goml] /" | tee -a "$REPORT"
	sleep 5
	tnt_raycast_off=$(tnt_test "$bx" "$y" "$((bz - 6))" "$((bz + 8))")
	echo "[goml] TNT with Lithium's explosion raycast off: $tnt_raycast_off" | tee -a "$REPORT"
	[ "$tnt_raycast_off" = "inside kept, outside broken" ] || fail "with Lithium's explosion raycast off: $tnt_raycast_off"
	rcon "forceload remove all" > /dev/null
	stop_server
}

# 15 claims around spawn, 24 blocks apart (a 4x4 grid less a corner), owned
# by a bench player. One covers the spawn point and the husk pen.
gc_claims() {
	local owner i j x z n=0 flog0 slow
	flog0=$(wc -l < "$FLOG")
	owner=$(bench_player "$BW_SPAWN_X" "$BW_SPAWN_Z")
	[ -n "$owner" ] || fail "no bench player to own the claims"
	# Its view loads around spawn.
	sleep 8
	for i in 0 1 2 3; do
		for j in 0 1 2 3; do
			[ "$i$j" != 33 ] || continue
			x=$((BW_SPAWN_X - 22 + 24 * i))
			z=$((BW_SPAWN_Z - 22 + 24 * j))
			rcon "setblock $x $GC_SPAWN_Y $z minecraft:glass" "setblock $x $((GC_SPAWN_Y + 1)) $z minecraft:air" \
				"ferrite bench players use $owner $x $GC_SPAWN_Y $z $GOML_ANCHOR" > /dev/null
		done
	done
	sleep 3
	for i in 0 1 2 3; do
		for j in 0 1 2 3; do
			[ "$i$j" != 33 ] || continue
			if block_is $((BW_SPAWN_X - 22 + 24 * i)) $((GC_SPAWN_Y + 1)) $((BW_SPAWN_Z - 22 + 24 * j)) "$GOML_ANCHOR"; then
				n=$((n + 1))
			fi
		done
	done
	echo "[goml-cost] $n of 15 claims placed around spawn" | tee -a "$REPORT"
	[ "$n" -eq 15 ] || fail "only $n of 15 claims were placed"
	# Ticks over 100 ms while the claims were placed (Ferrite's tick watch),
	# and those with GOML's web-map marker in the stack.
	slow=$(tail -n "+$((flog0 + 1))" "$FLOG" | grep 'slow-tick' || true)
	echo "[goml-cost] slow ticks while placing claims: $(echo "$slow" | grep -c 'between ticks' || true)," \
		"of which with GOML's web-map marker: $(echo "$slow" | grep -cE 'WebmapCompat|PlayerRecord|PlayerHeadRenderer' || true)," \
		"longest $(echo "$slow" | sed -n 's/.*: \([0-9]*\) ms between ticks.*/\1/p' | sort -n | tail -1) ms" | tee -a "$REPORT"
	rcon "ferrite bench players clear" > /dev/null
}

# gc_block <none|goml> <run>: a copy of the base world, GOML on or off, then
# the four arms under JFR.
gc_block() {
	local set=$1 label="$1$2"
	rm -rf run/world run/config run/polymer
	cp -a run/world-base run/world
	cp -a run/config-base run/config
	goml_mods "$([ "$set" = goml ] && echo on || echo off)"
	start_server
	if [ "$set" = goml ]; then gc_claims; fi
	bw_warmup
	jcmd "$(game_pid)" JFR.start name=goml settings="$PWD/goml.jfc" filename="$PWD/goml-cost-$label.jfr" > /dev/null \
		|| echo "JFR did not start for $label"
	BW_SET=$set BW_METER=off bw_arm_java "$label-java-idle" idle
	BW_SET=$set BW_METER=off bw_arm_java "$label-java-elytra" elytra
	BW_SET=$set BW_METER=off bw_arm_java "$label-java-fresh" elytra "$BW_START" "$GC_FRESH_Z" fresh
	BW_SET=$set bw_arm_bedrock "$label-bedrock-elytra" 6 elytra
	jcmd "$(game_pid)" JFR.stop name=goml > /dev/null || true
	stop_server
}

gc_summary() {
	echo "=== GOML and Polymer: none against goml, per arm (mean of runs; each run in brackets) ===" | tee -a "$REPORT"
	python3 - "$REPORT" <<'PY' | tee -a "$REPORT"
import re, sys
from collections import defaultdict
rows = defaultdict(list)
for line in open(sys.argv[1]):
    if line.startswith("[bw] ") and " set=none " in line or line.startswith("[bw] ") and " set=goml " in line:
        f = dict(re.findall(r"(\w+)=(\S*)", line))
        rows[(f["client"], f["activity"], f["set"])].append(f)
def num(r, k):
    try:
        return float(r.get(k) or 0)
    except ValueError:
        return 0.0
metrics = [
    ("tick ms mean", lambda r: num(r, "mspt")),
    ("tick ms p95", lambda r: num(r, "p95")),
    ("server ms/s", lambda r: num(r, "server_ms_s")),
    ("worldgen ms/s", lambda r: num(r, "worldgen_ms_s")),
    ("geyser ms/s", lambda r: num(r, "geyser_ms_s")),
    ("total ms/s", lambda r: num(r, "total_ms_s")),
    ("chunks/s/player", lambda r: num(r, "chunks") / num(r, "seconds") / num(r, "players")),
    ("Mbit/s/player", lambda r: num(r, "down_bytes") * 8 / num(r, "seconds") / num(r, "players") / 1e6),
]
arms = sorted({(c, a) for c, a, _ in rows}, key=lambda k: (k[0] != "java", ["idle", "elytra", "fresh"].index(k[1]) if k[1] in ("idle", "elytra", "fresh") else 9))
print("%-15s %-16s %-24s %-24s %9s" % ("arm", "metric", "none", "goml", "goml-none"))
for client, act in arms:
    none, goml = rows.get((client, act, "none"), []), rows.get((client, act, "goml"), [])
    for name, fn in metrics:
        if name.startswith("Mbit") and client != "bedrock":
            continue
        vn, vg = [fn(r) for r in none], [fn(r) for r in goml]
        mn = sum(vn) / len(vn) if vn else float("nan")
        mg = sum(vg) / len(vg) if vg else float("nan")
        fmt = lambda m, v: "%.2f [%s]" % (m, " ".join("%.2f" % x for x in v))
        print("%-15s %-16s %-24s %-24s %+9.2f" % (client + " " + act, name, fmt(mn, vn), fmt(mg, vg), mg - mn))
PY
}

gc_jfr() {
	local f
	echo "=== JFR: share of execution samples with a frame in GOML (and its bundled libraries), Polymer, Ferrite ===" | tee -a "$REPORT"
	for f in goml-cost-*.jfr; do
		[ -f "$f" ] || continue
		echo "[jfr] $f" | tee -a "$REPORT"
		java scripts/JfrShare.java "$f" "${JFR_GROUPS[@]}" 2> /dev/null | sed "s/^/[jfr] /" | tee -a "$REPORT" || echo "[jfr] could not read $f"
	done
}

goml_cost() {
	sudo -n sysctl -qw kernel.sched_autogroup_enabled=0 2>/dev/null || echo "could not turn scheduler autogroups off"
	# profile.jfc with Java execution sampling at 5 ms, every thread.
	sed -E '/<event name="jdk.ExecutionSample">/,/<\/event>/ s#<setting name="(period|throttle)"([^>]*)>[^<]*</setting>#<setting name="\1"\2>5 ms</setting>#' \
		"$JAVA_HOME/lib/jfr/profile.jfc" > goml.jfc
	goml_report
	goml_mods off
	configure_geyser
	start_server
	calibrate
	pregen_corridor "$BW_X" 9
	bw_setup
	# Husks at spawn for the idle arms (the console runs at world spawn). No
	# player is there, so the spawn area is loaded for this.
	rcon "forceload add ~-48 ~-48 ~48 ~48" | tee -a "$REPORT"
	sleep 5
	local cmds=("fill ~4 ~ ~4 ~6 ~4 ~6 minecraft:glass hollow") spec set n
	for _ in $(seq 30); do
		cmds+=("summon minecraft:husk ~5 ~1 ~5 {PersistenceRequired:1b}")
	done
	for _ in $(seq 20); do
		cmds+=("summon minecraft:husk ~-6 ~ ~-6 {PersistenceRequired:1b}")
	done
	rcon "${cmds[@]}" | sort | uniq -c | tee -a "$REPORT"
	read -r BW_SPAWN_X GC_SPAWN_Y BW_SPAWN_Z < <(spawn_pos)
	[ -n "$BW_SPAWN_Z" ] || fail "could not read the world spawn"
	echo "[goml-cost] spawn at $BW_SPAWN_X $GC_SPAWN_Y $BW_SPAWN_Z" | tee -a "$REPORT"
	rcon "forceload remove all" > /dev/null
	stop_server
	# Every block starts from these: the world and the mods' configs.
	rm -rf run/world-base run/config-base
	cp -a run/world run/world-base
	cp -a run/config run/config-base
	for spec in "none 1" "goml 1" "none 2" "goml 2"; do
		read -r set n <<< "$spec"
		gc_block "$set" "$n"
	done
	gc_summary
	gc_jfr
	echo "[goml-cost] Bedrock joins that failed and were retried: $JOIN_RETRIES" | tee -a "$REPORT"
}

case "$SCENARIO" in
	full|features-off) compat ;;
	cost) cost ;;
	cost-c1) GEYSER_COMPRESSION=1 cost ;;
	bandwidth) bandwidth ;;
	goml) goml ;;
	goml-cost) goml_cost ;;
	*) fail "unknown scenario $SCENARIO" ;;
esac
stop_spin

if grep -q 'MISMATCH' "$FLOG" 2>/dev/null; then
	grep 'MISMATCH' "$FLOG" | head -5
	fail "oracle mismatch in ferrite.log"
fi
# Errors from the Bedrock side, reported (not fatal unless a check above failed).
echo "=== errors and exceptions mentioning geyser, floodgate or ferrite ===" | tee -a "$REPORT"
grep -iE 'error|exception' geyser-server-all.log | grep -iE 'geyser|floodgate|ferrite|bedrock' | sort | uniq -c | sort -rn | head -20 | tee -a "$REPORT" || true
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
	{ echo '```'; cat "$REPORT"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi
echo "geyser $SCENARIO: done"
