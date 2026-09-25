#!/usr/bin/env bash
# Prints javap -c -p for vanilla methods listed in scripts/inspect-targets.txt,
# so mixins can be written against the exact bytecode of this Minecraft
# version without a local copy of the game.
set -euo pipefail

# Loom sets up the Minecraft jars before compiling, so a compile error in
# the mod still leaves the jars to inspect; print it and carry on.
./gradlew compileJava -x buildRustLib -x copyRustDll > inspect-gradle.log 2>&1 || {
	echo "compileJava failed:"
	grep -E 'error:|warning:' -A3 inspect-gradle.log | head -60 || tail -50 inspect-gradle.log
}
mapfile -t jars < <(find "$HOME/.gradle/caches" .gradle -name '*.jar' 2>/dev/null | grep -i 'minecraft' | grep -v -- '-sources' || true)
# Every other jar (Fabric API modules and the like), searched after the game.
mapfile -t more < <(find "$HOME/.gradle/caches" .gradle -name '*.jar' 2>/dev/null | grep -vi 'minecraft' | grep -v -- '-sources' || true)
jars+=("${more[@]}")
# Mods the inspect job fetched from Modrinth (scripts/worldgen-mods.txt).
if [ -d run/mods ]; then
	mapfile -t mods < <(find run/mods -name '*.jar' 2>/dev/null)
	jars+=("${mods[@]}")
fi
echo "candidate jars: ${#jars[@]}"

# "@list <jar-name-regex> <entry-regex>" lines print javap -c -p of every
# matching class in every matching jar.
{ grep '^@list ' scripts/inspect-targets.txt || true; } | while read -r _ jre ere; do
	for j in "${jars[@]}"; do
		[[ "$(basename "$j")" =~ $jre ]] || continue
		{ unzip -Z1 "$j" | grep -E "$ere" || true; } | while read -r entry; do
			echo
			echo "######## $entry  ($j)"
			javap -c -p -classpath "$j" "${entry%.class}" || true
		done
	done
done

# "@sig <jar-name-regex> <entry-regex> <member-regex>" prints, for every
# matching class, only the member signatures (javap -p) matching the regex.
{ grep '^@sig ' scripts/inspect-targets.txt || true; } | while read -r _ jre ere mre; do
	for j in "${jars[@]}"; do
		[[ "$(basename "$j")" =~ $jre ]] || continue
		{ unzip -Z1 "$j" | grep -E "$ere" || true; } | while read -r entry; do
			hits=$(javap -p -classpath "$j" "${entry%.class}" 2>/dev/null | grep -E "$mre" || true)
			if [ -n "$hits" ]; then printf '#sig %s\n%s\n' "${entry%.class}" "$hits"; fi
		done
	done
done

{ grep -v '^#\|^@' scripts/inspect-targets.txt || true; } | while read -r cls methods; do
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
