#!/usr/bin/env bash
# Ferrite with Geyser and Floodgate: Bedrock players on the target server
# (the user's mods from scripts/server-mods.txt and worldgen-mods.txt,
# Krypton included), joined by a Bedrock client (scripts/bedrock-bot) over
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
#
# Geyser and Floodgate are fetched into run/geyser by the workflow. The
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
		[ "$waited" -lt "$limit" ] || fail "timed out waiting for: $pattern"
	done
}

rcon() { python3 scripts/rcon.py "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

# spin, stop_spin, noise_ms, calibrate and NICE: the laptop-speed model.
source scripts/laptop-model.sh

start_server() {
	taskset -c "$CPUS" ./gradlew runServer -x buildRustLib -x copyRustDll "${GRADLE_ARGS[@]}" < /dev/null > "$LOG" 2>&1 &
	PID=$!
	wait_for 'Done (' 1800
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

# bot_start <name> <duration-seconds> [status-every]: a Bedrock client in
# the background, its JSON lines in bots/<name>.jsonl.
bot_start() {
	node scripts/bedrock-bot/bot.js --name "$1" --duration "$2" --every "${3:-10}" --port "$BEDROCK_PORT" \
		> "$BOTS/$1.jsonl" 2> "$BOTS/$1.err" &
	BOT_PID[$1]=$!
}

bot_wait_spawn() {
	local name=$1 limit=$2 waited=0
	until grep -q '"bot":"spawned"' "$BOTS/$name.jsonl" 2>/dev/null; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "${BOT_PID[$name]}" 2>/dev/null || fail "Bedrock bot $name exited before spawning: $(tail -n 3 "$BOTS/$name.jsonl" "$BOTS/$name.err" 2>/dev/null)"
		[ "$waited" -lt "$limit" ] || fail "Bedrock bot $name did not spawn in $limit s"
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
	[ "$code" -eq 0 ] || fail "Bedrock bot $name ended with code $code (disconnected: $(bot_field "$name" disconnected))"
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
	local cfg
	cfg=$(find run/config -maxdepth 2 -iname 'config.yml' -ipath '*geyser*' | head -1)
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

case "$SCENARIO" in
	full|features-off) compat ;;
	cost) cost ;;
	cost-c1) GEYSER_COMPRESSION=1 cost ;;
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
