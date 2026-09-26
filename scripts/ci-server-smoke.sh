#!/usr/bin/env bash
# Headless dedicated-server smoke test, run by CI on every push.
#
# Boots the dev server with the mod, drives it over RCON, and fails on any
# mixin error. Then checks:
#   - cramming damage happens in both the Overworld and the Nether
#     (a pile of 30 husks in a 1x1 glass cell must thin out in each);
#   - the Rust worldgen state is deferred at boot and built on demand;
#   - Ferrite logs to run/logs/ferrite.log, and the console gets only its
#     pointer line and its warnings and errors.
# Extra arguments go to gradle, e.g. -Pferrite.diagnostics=false.
# Expects the Linux native already in src/main/resources/assets/ferrite/natives/linux.
set -euo pipefail

LOG=server-smoke.log
# Ferrite's own log; the console ($LOG) keeps only its warnings and errors.
FLOG=run/logs/ferrite.log
PILE=30
RCON_PORT=25575
RCON_PASSWORD=ferrite-smoke

mkdir -p run
echo "eula=true" > run/eula.txt
cat > run/server.properties <<PROPS
online-mode=false
# No players join, and the server pauses ticking after 60 s without any.
pause-when-empty-seconds=-1
view-distance=4
simulation-distance=4
level-seed=ferrite-smoke
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
	local pattern=$1 limit=$2 file=${3:-$LOG} waited=0
	until grep -q -- "$pattern" "$file" 2>/dev/null; do
		sleep 1
		waited=$((waited + 1))
		kill -0 "$PID" 2>/dev/null || fail "server exited while waiting for: $pattern"
		[ "$waited" -lt "$limit" ] || fail "timed out waiting for: $pattern"
	done
}

rcon() { python3 scripts/rcon.py "$RCON_PORT" "$RCON_PASSWORD" "$@"; }

wait_for 'Done (' 1200
wait_for 'RCON running' 60

grep -q 'Rust worldgen state deferred' "$FLOG" || fail "worldgen state was not deferred at boot"
if grep -q 'Rust worldgen state ready' "$FLOG"; then
	fail "worldgen state was built at boot"
fi

rcon "ferrite diagnostics status" "ferrite prechunk status" \
	"forceload add -16 -16 15 15" \
	"execute in minecraft:the_nether run forceload add -16 -16 15 15"
sleep 15

for dim in overworld the_nether; do
	cmds=("execute in minecraft:$dim run fill -1 99 -1 1 103 1 minecraft:glass hollow")
	for _ in $(seq "$PILE"); do
		cmds+=("execute in minecraft:$dim run summon minecraft:husk 0.5 100 0.5 {Tags:[\"smoke_$dim\"],PersistenceRequired:1b}")
	done
	rcon "${cmds[@]}" | sort | uniq -c
done
sleep 30

for dim in overworld the_nether; do
	out=$(rcon "execute if entity @e[type=minecraft:husk,tag=smoke_$dim]")
	echo "$dim: $out"
	if echo "$out" | grep -q 'Test failed'; then
		count=0
	else
		count=$(echo "$out" | sed -n 's/.*[Cc]ount: \([0-9]*\).*/\1/p')
	fi
	[ -n "$count" ] || fail "no husk count for $dim"
	[ "$count" -lt "$PILE" ] || fail "no cramming deaths in $dim ($count of $PILE husks left)"
done

rcon "ferrite worldgen status"
wait_for 'Rust worldgen state ready' 120 "$FLOG"
rcon "stop" || true
wait "$PID" || true

if grep -E -q 'Mixin apply for mod ferrite failed|InvalidInjectionException|Critical injection failure|MixinApplyError|InvalidMixinException' "$LOG"; then
	fail "mixin errors in the server log"
fi
grep -q 'native=true' "$FLOG" || fail "native library did not load"
grep -E '\[hw\]|lean mode' "$FLOG" || true

# Ferrite's own log file: its info lines there, not in the console.
grep -q 'Ferrite logs to logs/ferrite.log' "$LOG" || fail "no pointer to ferrite.log in the server log"
leaked=$(grep -E -c '/INFO\] \(ferrite\)' "$LOG" || true)
if [ "$leaked" -gt 0 ]; then
	grep -E -m 5 '/INFO\] \(ferrite\)' "$LOG"
	fail "$leaked Ferrite info lines in the server log"
fi
echo "ferrite.log: $(wc -l < "$FLOG") lines; Ferrite lines in the server log: $(grep -c '(ferrite)' "$LOG" || true)"
echo "server smoke test passed"
