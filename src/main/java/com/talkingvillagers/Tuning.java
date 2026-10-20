package com.talkingvillagers;

import java.util.List;

/**
 * Fixed gameplay values.
 *
 * <p>The config file deliberately exposes only the handful of settings a server owner actually
 * needs to change — where the model lives, what the villagers are called, and which systems are
 * switched on. Everything else that shapes how the village behaves lives here instead of in
 * TOML, so the mod has one balance rather than a thousand.
 *
 * <p>These are grouped to mirror the config sections they used to live under, so it stays
 * obvious what is fixed and where to look for it.
 */
public final class Tuning {
	private Tuning() {
	}

	public static final class Ollama {
		/**
		 * Requests allowed in flight at once. Raising this against a small local model mostly
		 * trades villager responsiveness for queueing inside Ollama, which is why it is not a
		 * config knob.
		 */
		public static final int MAX_CONCURRENT = 2;

		/**
		 * How long Ollama should keep the model in memory between requests.
		 *
		 * <p>Sent with every request because Ollama's own default is five minutes, after which
		 * it evicts the model and the next villager to speak pays the full load cost — measured
		 * at tens of seconds on a cold start, easily enough to blow {@code timeout_ms} and leave
		 * a villager mysteriously silent. Villages talk in bursts with long gaps, which is
		 * exactly the pattern that default punishes.
		 */
		public static final String KEEP_ALIVE = "30m";

		/**
		 * Extra tokens granted on top of {@code max_tokens} while thinking is enabled.
		 *
		 * <p>Ollama's token limit covers the model's reasoning as well as its answer, so a
		 * thinking model left with only the speech budget spends all of it reasoning and the
		 * answer comes back empty. Measured on a 2B thinking model, a one-sentence villager
		 * reply cost over 1300 tokens of deliberation first — prompt instructions to think
		 * briefly trimmed that by barely a quarter — so the headroom is sized to let even a
		 * ruminative model finish. It is deliberately not configurable: it is what keeps
		 * {@code max_tokens} meaning "the reply", whatever the model does first.
		 */
		public static final int THINKING_HEADROOM = 2048;

		private Ollama() {
		}
	}

	public static final class Identity {
		/**
		 * Relative orientation weights, used only when {@code sexuality.enable_sexuality} is on.
		 * They need not sum to 100 — only the ratios matter — and approximate real-world survey
		 * proportions.
		 */
		public static final double HETEROSEXUAL_WEIGHT = 89.0;
		public static final double BISEXUAL_WEIGHT = 6.0;
		public static final double HOMOSEXUAL_WEIGHT = 3.0;
		public static final double PANSEXUAL_WEIGHT = 1.0;
		public static final double ASEXUAL_WEIGHT = 1.0;

		/**
		 * Chance per villager, rolled at adulthood. Independent of the sexuality toggle: gender
		 * identity and orientation are separate things, and switching orientation modelling off
		 * does not switch this off.
		 */
		public static final double TRANSGENDER_CHANCE = 0.01;

		/**
		 * Whether a villager's name hangs above their head permanently.
		 *
		 * <p>False, so names behave like vanilla ones: the client draws a named entity's label
		 * only while it is the entity under the crosshair, which means no reading names through
		 * walls and no village that is a wall of floating text from a distance. The cost is that
		 * a vanilla client only ever draws it within its own entity-interaction reach, about
		 * three blocks — close enough to talk to, which is the point at which the name matters.
		 */
		public static final boolean NAME_TAGS_ALWAYS_VISIBLE = false;

		private Identity() {
		}
	}

	public static final class Conversation {
		/** Height of floating text above the speaker's feet, in blocks. */
		public static final double OVERHEAD_HEIGHT_OFFSET = 2.4;

		/** Ticks between steps of the "thinking" dots shown while a villager composes a reply. */
		public static final int THINKING_FLICK_INTERVAL_TICKS = 10;

		/** A conversation with no messages for this long ends by itself. */
		public static final int SESSION_TIMEOUT_TICKS = 600;

		/**
		 * Villager replies before a conversation wraps up on its own, regardless of what either
		 * side says. A backstop against a small model that never reaches for a goodbye on its
		 * own, so a chat cannot ramble on indefinitely.
		 */
		public static final int MAX_VILLAGER_REPLIES = 10;

		/**
		 * Regard gained by the villager each time a conversation with a player actually goes
		 * somewhere. Small on purpose: talking to someone often should warm them to you over many
		 * meetings, not buy friendship in an afternoon. Its more important job is bookkeeping —
		 * it stamps the relationship as recently used, which is what keeps a player from being
		 * trimmed out of a busy villager's memory.
		 */
		public static final int PLAYER_CONVERSATION_RAPPORT = 1;

		/**
		 * Extra regard when the model judges the player treated the villager kindly in a
		 * conversation — compliments, warmth, offers of help. Close to a gift in weight: words
		 * are how a player who has nothing to give still makes friends.
		 */
		public static final int KIND_CONVERSATION_DELTA = 7;

		/**
		 * Regard lost when the model judges the player was rude — insults, threats, mockery.
		 * Heavier than kindness is warm, and big enough to cross {@code MEMORABLE_DELTA}, so
		 * being insulted is something a villager remembers and can bring up later.
		 */
		public static final int RUDE_CONVERSATION_DELTA = -12;

		/** Minimum ticks before the same villager starts another conversation. */
		public static final int VILLAGER_TO_VILLAGER_INTERVAL_TICKS = 600;

		/**
		 * Ceiling on conversations opened per scheduling pass. The scheduler runs on the mod's
		 * sweep rather than every tick, so this is a limit per sweep, not per tick.
		 */
		public static final int VILLAGER_TO_VILLAGER_MAX_PER_PASS = 2;

		/**
		 * How long two villagers stand and talk to each other. While a conversation is running
		 * both of them hold still and face each other, so this is also how long they are taken
		 * out of their normal routine.
		 */
		public static final int VILLAGER_TO_VILLAGER_DURATION_TICKS = 120;

		private Conversation() {
		}
	}

	public static final class Family {
		/**
		 * Number of children rolled once per marriage: index 0 is the weight of having none,
		 * index 1 of one child, and so on.
		 */
		public static final List<Integer> CHILDREN_WEIGHTS = List.of(8, 30, 40, 15, 5, 2);

		/** Mutual regard needed to become lovers, then to get engaged (0-100). */
		public static final int LOVERS_THRESHOLD = 75;
		public static final int ENGAGEMENT_THRESHOLD = 90;

		/** How often courtship and births are evaluated. */
		public static final int COURTSHIP_INTERVAL_TICKS = 1200;

		/**
		 * Time of day (0-24000) when engaged couples are automatically married and anyone
		 * grieving stops. Late afternoon, before dusk — a day's-end rhythm rather than something
		 * a player has to go trigger.
		 */
		public static final long DAILY_CEREMONY_TICK = 11000L;

		/**
		 * Time of day (0-24000) the village's day-to-day record — gossip, and today's events —
		 * is wiped clean, ready for the next day. After the ceremony, once villagers have
		 * finished mingling and headed home for the night.
		 */
		public static final long DAILY_RESET_TICK = 13000L;

		/** Ticks between a married couple's children. */
		public static final int BIRTH_INTERVAL_TICKS = 6000;

		private Family() {
		}
	}

	public static final class Needs {
		/**
		 * Food points one working farmer, fisherman, butcher or shepherd contributes per
		 * in-game day. Also sets the ceiling: a village can stockpile at most this much per
		 * farmer it currently has.
		 */
		public static final int FOOD_PER_FARMER_PER_DAY = 8;

		/** Meals a villager eats per day: one in the morning, one at night. */
		public static final int MEALS_PER_DAY = 2;

		/** Food points one villager eats at each meal. */
		public static final int FOOD_PER_VILLAGER_PER_MEAL = 1;

		private Needs() {
		}
	}

	public static final class Ui {
		/** Title timing, in ticks, for the banner shown on walking into a village. */
		public static final int TITLE_FADE_IN_TICKS = 10;
		public static final int TITLE_STAY_TICKS = 40;
		public static final int TITLE_FADE_OUT_TICKS = 15;

		/**
		 * How far a player must leave a village before arriving again re-announces it. A margin
		 * beyond the settlement radius, so standing on the boundary does not flash the title over
		 * and over as they drift a block back and forth.
		 */
		public static final double VILLAGE_TITLE_HYSTERESIS = 16.0;

		private Ui() {
		}
	}

	public static final class Book {
		/**
		 * How long the mod waits for a village name after the player clicks the book's rename
		 * control, in ticks — a minute.
		 *
		 * <p>This is a safety limit rather than a balance figure. While the prompt is open the
		 * player's chat is being swallowed, so it must not be possible to click the control, forget
		 * about it, and spend the evening wondering why nobody can hear you.
		 */
		public static final int RENAME_PROMPT_TIMEOUT_TICKS = 1200;

		private Book() {
		}
	}

	public static final class Settlement {
		/**
		 * Settlements are anchored on their bell; villagers within this radius are members. This
		 * scopes needs, elections, gossip and reports, and matches vanilla's own bell logic.
		 */
		public static final double BELL_RADIUS = 96.0;

		/**
		 * Two bells closer together than this are treated as one village.
		 *
		 * <p>Villages do generate with a second bell a few blocks from the first, and each one
		 * discovered separately became its own settlement — same residents, two names, two mayors.
		 *
		 * <p>Equal to {@link #BELL_RADIUS} on purpose: any bell standing inside a village's
		 * influence is that village's bell, so a second bell can never found a rival next door —
		 * it extends the village it lands in.
		 */
		public static final double BELL_MERGE_RADIUS = 96.0;

		/**
		 * Gossip entries kept on a settlement's own record before the oldest is dropped. Wiped
		 * clean daily regardless, so this only guards against one very busy day overflowing it.
		 */
		public static final int GOSSIP_LOG_LIMIT = 20;

		/** Village-wide event entries kept before the oldest is dropped. Also wiped clean daily. */
		public static final int EVENT_LOG_LIMIT = 20;

		/**
		 * Model-invented conversation topics a village accumulates per day. Once full, every
		 * villager-to-villager conversation draws from these until the nightly wipe.
		 */
		public static final int DAILY_GOSSIP_TOPICS = 5;

		/**
		 * Time of day the nightly bed check runs — deep night, when everyone who has a bed is
		 * asleep in it, so whoever is still on their feet genuinely has nowhere to sleep.
		 */
		public static final long BED_CHECK_TICK = 17000L;

		/** Nights in a row without a bed before a villager gives up on the village and leaves. */
		public static final int NIGHTS_WITHOUT_BED_BEFORE_LEAVING = 2;

		private Settlement() {
		}
	}
}
