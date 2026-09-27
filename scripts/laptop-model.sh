# The laptop-speed model shared by scripts/ci-players-bench.sh and
# scripts/ci-geyser.sh: busy processes on every CPU of the game (CPUS), at
# a nice level calibrated so that the noise bench runs FACTOR times slower
# than on the reference runner (ANCHOR_MS). Sourced; expects CPUS,
# FACTORS, ANCHOR_MS, REPORT and the functions rcon and fail.
SPIN_PID=

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
