#!/usr/bin/env bash
# Prints javap -c -p for vanilla methods listed in scripts/inspect-targets.txt,
# so mixins can be written against the exact bytecode of this Minecraft
# version without a local copy of the game.
set -euo pipefail

./gradlew compileJava -x buildRustLib -x copyRustDll > inspect-gradle.log 2>&1 || {
	tail -50 inspect-gradle.log
	exit 1
}
mapfile -t jars < <(find "$HOME/.gradle/caches" .gradle -name '*.jar' 2>/dev/null | grep -i 'minecraft' | grep -v -- '-sources' || true)
echo "candidate jars: ${#jars[@]}"

grep -v '^#' scripts/inspect-targets.txt | while read -r cls methods; do
	[ -n "$cls" ] || continue
	entry="${cls//.//}.class"
	jar=""
	for j in "${jars[@]}"; do
		if unzip -l "$j" "$entry" > /dev/null 2>&1; then jar=$j; break; fi
	done
	echo
	echo "######## $cls  ($jar)"
	[ -n "$jar" ] || { echo "class not found"; continue; }
	javap -c -p -classpath "$jar" "$cls" | awk -v re="$methods" '
		/^  [^ ]/ {
			if ($0 !~ /\(/) { print; next }   # fields: always shown, for names and types
			show = ($0 ~ ("[ .](" re ")\\("))
		}
		show { print }
	'
done
