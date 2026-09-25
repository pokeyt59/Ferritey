package me.apika.apikaprobe.entity;

/**
 * Duck interface mixed into Mob: per-mob bookkeeping for the batched
 * cramming dispatcher. Ticks are server tick counts; Long.MIN_VALUE
 * means "never".
 */
public interface CrammingState {
	/** Server tick of this mob's last pushEntities call. */
	long ferrite$pushTick();

	void ferrite$setPushTick(long tick);

	/** Server tick of the batch that took this mob as a caller. */
	long ferrite$batchTick();

	/** Crowded count the batch computed for this mob. */
	int ferrite$crowded();

	void ferrite$setBatched(long tick, int crowded);
}
