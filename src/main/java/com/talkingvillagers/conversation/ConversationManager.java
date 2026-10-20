package com.talkingvillagers.conversation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.talkingvillagers.TalkingVillagers;
import com.talkingvillagers.Tuning;
import com.talkingvillagers.config.TalkingVillagersConfig;
import com.talkingvillagers.data.Attachments;
import com.talkingvillagers.identity.VillagerSouls;
import com.talkingvillagers.llm.LlmPriority;
import com.talkingvillagers.llm.LlmRequest;
import com.talkingvillagers.llm.PromptBuilder;
import com.talkingvillagers.settlement.Settlements;
import com.talkingvillagers.social.MemoryEntry;
import com.talkingvillagers.social.Relationship;
import com.talkingvillagers.social.Relationships;
import com.talkingvillagers.social.VillagerSoul;
import com.talkingvillagers.ui.Notify;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Runs conversations between players and villagers.
 *
 * <p>A session captures the player's chat: while one is open, everything they type is routed
 * to the villager and suppressed from global chat. That is what makes talking to a villager
 * feel like talking rather than like issuing commands, and it is why sessions end eagerly —
 * on distance, on silence, on a goodbye — since a player whose chat is being swallowed
 * without them realising would be a genuinely bad bug.
 */
public final class ConversationManager {
	/** Words that end a conversation once the villager has answered. */
	private static final Set<String> FAREWELLS = Set.of(
		"bye", "goodbye", "good bye", "farewell", "see you", "see ya", "cya", "later", "goodnight",
		"good night", "take care");

	/** How often the "we are talking" particles are emitted, in ticks. */
	private static final int PARTICLE_INTERVAL_TICKS = 15;

	/** How much of a player's line is quoted back in the villager's memory of it. */
	private static final int REMEMBERED_LINE_LENGTH = 60;

	private final Map<UUID, ConversationSession> sessions = new HashMap<>();
	private final SpeechBubbles bubbles;
	private int particleCounter;

	public ConversationManager(SpeechBubbles bubbles) {
		this.bubbles = bubbles;
	}

	public boolean inConversation(UUID playerId) {
		return this.sessions.containsKey(playerId);
	}

	public Optional<ConversationSession> session(UUID playerId) {
		return Optional.ofNullable(this.sessions.get(playerId));
	}

	public int activeCount() {
		return this.sessions.size();
	}

	/**
	 * Whether this villager is currently mid-conversation with a player, in which case other
	 * systems should leave them alone rather than pulling them into village gossip.
	 */
	public boolean isTalkingToPlayer(UUID villagerId) {
		for (ConversationSession session : this.sessions.values()) {
			if (session.villager().getUUID().equals(villagerId)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Opens a conversation, replacing any the player already had.
	 *
	 * <p>Only a villager who walked up on their own initiative speaks first — that is the whole
	 * point of them approaching. A player who sneak-clicks a villager instead gets a quiet
	 * session and has to open with something themselves, the way starting a conversation with a
	 * stranger actually works.
	 *
	 * @param villagerInitiated true when the villager walked up to the player rather than the
	 *                          player interacting with the villager
	 */
	public void start(ServerPlayer player, Villager villager, boolean villagerInitiated) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		end(player.getUUID(), EndReason.REPLACED);

		VillagerSoul soul = VillagerSouls.ensureSoul(villager, level);
		ConversationSession session = new ConversationSession(
			player.getUUID(), player.getName().getString(), villager, level.getGameTime());
		this.sessions.put(player.getUUID(), session);

		if (!villagerInitiated) {
			Notify.actionBar(player, Component.literal("Talking to " + soul.identity().fullName())
				.withStyle(ChatFormatting.GOLD));
			return;
		}

		Notify.actionBar(player, Component.literal(soul.identity().firstName() + " wants a word")
			.withStyle(ChatFormatting.GOLD));

		Optional<Relationship> standing = soul.relationship(player.getUUID());
		submitReply(session, soul, level,
			PromptBuilder.greetPlayer(player.getName().getString(), standing));
	}

	/**
	 * Routes a chat message to the villager the player is talking to.
	 *
	 * @return true if the message was consumed and must not reach global chat
	 */
	public boolean handlePlayerMessage(ServerPlayer player, String message) {
		ConversationSession session = this.sessions.get(player.getUUID());
		if (session == null) {
			return false;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return false;
		}
		if (!session.villagerAvailable()) {
			end(player.getUUID(), EndReason.VILLAGER_GONE);
			return false;
		}

		String trimmed = message.strip();
		if (trimmed.isEmpty()) {
			return true;
		}

		session.touch(level.getGameTime());
		VillagerSoul soul = VillagerSouls.ensureSoul(session.villager(), level);

		// One outstanding request per session. Without this, a player typing quickly could
		// queue a dozen generations and starve every other villager in the world.
		if (session.awaitingReply()) {
			Notify.actionBar(player, Component.literal(
					soul.identity().firstName() + " hasn't answered yet")
				.withStyle(ChatFormatting.GRAY));
			return true;
		}

		// Show the player their own line back, since it never reaches chat.
		player.sendSystemMessage(Component.literal("You: ").withStyle(ChatFormatting.AQUA)
			.append(Component.literal(trimmed).withStyle(ChatFormatting.WHITE)));

		// The prompt quotes the current line on its own, so the transcript handed to it must be
		// captured from before the line is recorded — including it twice reads as two questions.
		List<String> transcriptBefore = session.transcript();
		String playerName = player.getName().getString();
		session.recordLine(playerName, trimmed);
		// The conversation ends on the player's own goodbye immediately, with no further reply —
		// a farewell is a stop sign, not one more prompt to answer.
		if (isFarewell(trimmed)) {
			end(player.getUUID(), EndReason.FAREWELL);
			return true;
		}

		// Recorded here rather than for a goodbye: a goodbye tells the villager nothing about
		// the player, but anything else might be the whole point of the conversation.
		session.recordPlayerLine(trimmed);
		submitReply(session, soul, level, PromptBuilder.playerSaid(
			soul.identity().firstName(), playerName, trimmed, transcriptBefore));
		return true;
	}

	/**
	 * Asks the model for the villager's next line.
	 *
	 * <p>The callbacks run later, on the server thread, by which time the session may have
	 * ended and the villager may be gone — so both are re-checked before anything is said.
	 */
	private void submitReply(
		ConversationSession session, VillagerSoul soul, ServerLevel level, String situation
	) {
		session.setAwaitingReply(true);
		// Show what the silence is while they compose their answer — but only overhead, since
		// the indicator is floating text and a player who chose chat-only display asked for
		// exactly that.
		if (TalkingVillagers.config().conversation.display.overhead()) {
			this.bubbles.showThinking(session.villager(), soul.identity().firstName());
		}

		UUID playerId = session.playerId();
		String system = PromptBuilder.personaCard(session.villager(), soul, level);

		TalkingVillagers.ollama().submit(new LlmRequest(
			LlmPriority.PLAYER_CONVERSATION,
			system,
			situation,
			reply -> onReply(playerId, reply),
			() -> onReplyFailed(playerId)
		));
	}

	private void onReply(UUID playerId, String reply) {
		ConversationSession session = this.sessions.get(playerId);
		if (session == null) {
			return;
		}
		session.setAwaitingReply(false);

		ServerPlayer player = playerOf(playerId);
		if (player == null || !session.villagerAvailable()) {
			end(playerId, EndReason.VILLAGER_GONE);
			return;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}

		// The thinking dots come down now, whatever the display preference: the reply's own
		// bubble replaces them when speech is shown overhead, and a chat-only display must
		// not leave an orphaned indicator over a villager who is no longer thinking.
		this.bubbles.remove(session.villager().getUUID());

		VillagerSoul soul = VillagerSouls.ensureSoul(session.villager(), level);
		session.recordLine(soul.identity().firstName(), reply);
		session.touch(level.getGameTime());
		speak(session.villager(), soul, reply, player);
		session.incrementReplyCount();

		// The villager can end things too, not just the player: a farewell in their own reply
		// closes the conversation the same way one from the player does. And regardless of what
		// either side says, a chat cannot ramble on forever — a small model rarely reaches for a
		// goodbye on its own.
		if (isFarewell(reply)) {
			end(playerId, EndReason.FAREWELL);
		} else if (session.replyCount() >= Tuning.Conversation.MAX_VILLAGER_REPLIES) {
			end(playerId, EndReason.TOO_LONG);
		}
	}

	private void onReplyFailed(UUID playerId) {
		ConversationSession session = this.sessions.get(playerId);
		if (session == null) {
			return;
		}
		session.setAwaitingReply(false);
		// Whatever the display preference, the dots were promise-keeping, not speech: with
		// nobody about to answer, they must not hang over the villager indefinitely.
		this.bubbles.remove(session.villager().getUUID());

		ServerPlayer player = playerOf(playerId);
		if (player == null) {
			return;
		}

		// Deliberately not a canned line of dialogue: the villager stays silent, and the
		// player gets an interface note explaining why nothing happened.
		String message = session.villagerAvailable() && player.level() instanceof ServerLevel level
			? VillagerSouls.ensureSoul(session.villager(), level).identity().firstName() + " says nothing"
			: "Nobody answers";
		Notify.actionBar(player, Component.literal(message).withStyle(ChatFormatting.GRAY));
	}

	/**
	 * Presents a villager's line: floating above them, in the player's chat, or both, per config.
	 *
	 * <p>The chat form is prefixed with the villager's name and the floating form is not — in
	 * chat there is nothing else to say who is speaking, whereas a bubble is already attached to
	 * its speaker.
	 */
	public void speak(Villager villager, VillagerSoul soul, String text, ServerPlayer audience) {
		TalkingVillagersConfig.Conversation settings = TalkingVillagers.config().conversation;

		if (settings.display.chat()) {
			audience.sendSystemMessage(
				Component.literal(soul.identity().firstName() + ": ").withStyle(ChatFormatting.YELLOW)
					.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
		}
		if (settings.display.overhead()) {
			this.bubbles.show(villager, text, settings.overheadDurationTicks);
		}
	}

	/**
	 * Per-tick upkeep: ends sessions that have drifted apart or gone quiet, keeps villagers
	 * looking at whoever they are talking to, and emits the "in conversation" particles.
	 */
	public void tick(MinecraftServer server) {
		if (this.sessions.isEmpty()) {
			return;
		}

		TalkingVillagersConfig config = TalkingVillagers.config();
		boolean emitParticles = config.conversation.particlesWhileTalking
			&& ++this.particleCounter >= PARTICLE_INTERVAL_TICKS;
		if (emitParticles) {
			this.particleCounter = 0;
		}

		double maxDistance = config.conversation.maxDistance;
		double maxDistanceSqr = maxDistance * maxDistance;

		Iterator<Map.Entry<UUID, ConversationSession>> iterator = this.sessions.entrySet().iterator();
		Map<UUID, EndReason> toEnd = new java.util.LinkedHashMap<>();
		while (iterator.hasNext()) {
			ConversationSession session = iterator.next().getValue();
			ServerPlayer player = playerOf(session.playerId());

			if (player == null) {
				iterator.remove();
				continue;
			}
			if (!session.villagerAvailable()) {
				toEnd.put(session.playerId(), EndReason.VILLAGER_GONE);
				continue;
			}

			Villager villager = session.villager();
			if (player.level() != villager.level() || player.distanceToSqr(villager) > maxDistanceSqr) {
				toEnd.put(session.playerId(), EndReason.DRIFTED);
				continue;
			}
			if (!(player.level() instanceof ServerLevel level)) {
				continue;
			}
			if (level.getGameTime() - session.lastActivityGameTime() > Tuning.Conversation.SESSION_TIMEOUT_TICKS) {
				toEnd.put(session.playerId(), EndReason.TIMED_OUT);
				continue;
			}

			// Stand still and pay attention for as long as the conversation lasts. The walk
			// target is cleared every tick rather than once, because the villager's own brain
			// keeps setting a new one — strolling off mid-sentence otherwise.
			holdAttention(villager, player);

			if (emitParticles) {
				level.sendParticles(ParticleTypes.NOTE,
					villager.getX(), villager.getEyeY() + 0.6, villager.getZ(), 1, 0.2, 0.1, 0.2, 0.0);
			}
		}

		toEnd.forEach(this::end);
	}

	/**
	 * Makes a villager stop where it is and look at whoever it is talking to.
	 *
	 * <p>Erasing the walk target leaves the villager's brain otherwise untouched, so it resumes
	 * its routine by itself the moment the conversation ends — no state to restore, and nothing
	 * breaks if the conversation is cut short by the villager unloading.
	 */
	public static void holdAttention(Villager villager, net.minecraft.world.entity.LivingEntity target) {
		Brain<Villager> brain = villager.getBrain();
		brain.eraseMemory(MemoryModuleType.WALK_TARGET);
		BehaviorUtils.lookAtEntity(villager, target);
	}

	/** Ends a player's conversation, telling them why. */
	public void end(UUID playerId, EndReason reason) {
		ConversationSession session = this.sessions.remove(playerId);
		if (session == null) {
			return;
		}

		remember(session);
		this.bubbles.remove(session.villager().getUUID());

		ServerPlayer player = playerOf(playerId);
		if (player == null || reason == EndReason.REPLACED) {
			return;
		}

		// One universal message regardless of why: the reason matters for the mod's own logic,
		// but not enough to a player to be worth a different line for each of them.
		Notify.actionBar(player, Component.literal("Conversation ended").withStyle(ChatFormatting.GRAY));
	}

	/**
	 * Folds a finished conversation into the villager's lasting memory, so the next one with this
	 * player does not start from nothing.
	 *
	 * <p>Two things are written. The memory is what the player said, which is where the content
	 * lives — a name they gave, a promise they made, a question they asked — and it reaches the
	 * next conversation through the persona card's recent events. The relationship is what makes
	 * the villager <em>know</em> them at all: without it they are greeted as a stranger every
	 * time, and, because it is stamped with the current time, it also survives the trimming that
	 * bounds how many people one villager can hold in mind.
	 *
	 * <p>A conversation the player never spoke in is deliberately left unrecorded. Villagers walk
	 * up to players unprompted, and a village where every ignored hello burned one of a villager's
	 * twelve memory slots would push out everything that actually happened to them.
	 */
	private void remember(ConversationSession session) {
		List<String> said = session.playerLines();
		if (said.isEmpty() || !session.villagerAvailable()) {
			return;
		}
		if (!(session.villager().level() instanceof ServerLevel level)) {
			return;
		}
		VillagerSoul soul = session.villager().getAttached(Attachments.SOUL);
		if (soul == null) {
			return;
		}

		StringBuilder memory = new StringBuilder(96)
			.append("talked with ").append(session.playerName()).append(", who said ");
		for (int i = 0; i < said.size(); i++) {
			if (i > 0) {
				memory.append(" and ");
			}
			memory.append('"').append(shorten(said.get(i))).append('"');
		}

		long gameTime = level.getGameTime();
		MemoryEntry templated = soul.remember(gameTime, memory.toString());
		soul.adjustRelationship(
			session.playerId(), Tuning.Conversation.PLAYER_CONVERSATION_RAPPORT, gameTime);
		// One log entry per player-villager pair per day; a later chat replaces the earlier one.
		String gossipKey = "player-chat:" + session.playerName() + "+" + soul.identity().firstName();
		Settlements.recordVillageGossip(soul, level, gameTime, gossipKey,
			session.playerName(), soul.identity().firstName(), "talked about " + shorten(said.get(0)));

		summarise(session, soul.identity().firstName(), level, templated, gossipKey);
		judgeTone(session, soul.identity().firstName(), level);
	}

	/**
	 * Asks the model whether the player was kind or rude, and moves the relationship by more
	 * than the flat rapport when they were either.
	 *
	 * <p>This is what makes what a player <em>says</em> matter: without it a conversation full
	 * of insults and one full of compliments both land as the same +1. The flat rapport was
	 * already applied in {@link #remember}, so like {@link #summarise} this is pure addition —
	 * a dropped request or an unparseable answer just means the conversation counts as neutral.
	 */
	private void judgeTone(ConversationSession session, String villagerName, ServerLevel level) {
		if (!TalkingVillagers.ollama().healthy()) {
			return;
		}

		TalkingVillagers.ollama().submit(LlmRequest.of(
			LlmPriority.BACKGROUND,
			"You judge how people treated each other in conversations for a village-life game. "
				+ "Answer with exactly one word.",
			PromptBuilder.judgePlayerTone(
				villagerName, session.playerName(), session.transcript()),
			verdict -> {
				// The reply lands seconds later; the villager may be gone or in another world.
				Villager villager = session.villager();
				if (!villager.isAlive() || villager.isRemoved() || villager.level() != level) {
					return;
				}
				VillagerSoul soul = villager.getAttached(Attachments.SOUL);
				if (soul == null) {
					return;
				}

				// First word only, since the model was told to answer with one; anything that is
				// not clearly one of the two extremes counts as neutral, so a rambling answer
				// cannot swing a relationship.
				String word = verdict.strip().split("\\s+")[0]
					.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
				if (word.equals("kind")) {
					Relationships.apply(soul, session.playerId(),
						Tuning.Conversation.KIND_CONVERSATION_DELTA, level.getGameTime(), level,
						session.playerName() + " was kind to me");
				} else if (word.equals("rude")) {
					Relationships.apply(soul, session.playerId(),
						Tuning.Conversation.RUDE_CONVERSATION_DELTA, level.getGameTime(), level,
						session.playerName() + " was rude to me");
				}
			}));
	}

	/**
	 * Asks the model to compress the finished conversation into one proper line, upgrading the
	 * templated memory and gossip entry in place when it answers.
	 *
	 * <p>The templated versions were already written above, so this is pure improvement: a
	 * dropped request, a failed request, or a villager gone by the time the reply lands all
	 * cost nothing. Which is also why the priority is {@code BACKGROUND} — nobody is waiting.
	 */
	private void summarise(
		ConversationSession session, String villagerName, ServerLevel level,
		MemoryEntry templated, String gossipKey
	) {
		if (!TalkingVillagers.ollama().healthy()) {
			return;
		}

		TalkingVillagers.ollama().submit(LlmRequest.of(
			LlmPriority.BACKGROUND,
			"You summarise conversations for a village-life game. "
				+ "Answer with exactly one short plain line.",
			PromptBuilder.summariseConversation(
				villagerName, session.playerName(), session.transcript()),
			summary -> {
				// The reply lands seconds later; the villager may be gone or in another world.
				Villager villager = session.villager();
				if (!villager.isAlive() || villager.isRemoved() || villager.level() != level) {
					return;
				}
				VillagerSoul soul = villager.getAttached(Attachments.SOUL);
				if (soul == null) {
					return;
				}
				soul.replaceMemory(templated, summary);
				Settlements.recordVillageGossip(soul, level, level.getGameTime(), gossipKey,
					session.playerName(), villagerName, summary);
			}));
	}

	/** Trims a quoted line to something that fits in a prompt beside eleven other memories. */
	private static String shorten(String line) {
		if (line.length() <= REMEMBERED_LINE_LENGTH) {
			return line;
		}
		String window = line.substring(0, REMEMBERED_LINE_LENGTH);
		int lastSpace = window.lastIndexOf(' ');
		return (lastSpace > REMEMBERED_LINE_LENGTH / 2 ? window.substring(0, lastSpace) : window) + "…";
	}

	/**
	 * Drops every session, e.g. on server stop.
	 *
	 * <p>Each is ended properly rather than discarded, so a player who was mid-conversation when
	 * the server went down is still remembered. This runs before the final save, which is what
	 * makes those memories reach the disk.
	 */
	public void clear() {
		for (UUID playerId : List.copyOf(this.sessions.keySet())) {
			end(playerId, EndReason.REPLACED);
		}
		this.sessions.clear();
	}

	private static ServerPlayer playerOf(UUID playerId) {
		MinecraftServer server = TalkingVillagers.server();
		return server == null ? null : server.getPlayerList().getPlayer(playerId);
	}

	private static boolean isFarewell(String message) {
		// Checked against the last sentence, not the whole message: the model is told to write
		// full sentences and sign off with a farewell word inside one ("...back to my forge now,
		// goodbye"), so gating on the total message length would miss almost every real goodbye.
		String[] sentences = message.split("[.!?]+");
		String lastSentence = sentences.length == 0 ? message : sentences[sentences.length - 1];
		String normalised = lastSentence.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").strip();
		if (FAREWELLS.contains(normalised)) {
			return true;
		}
		// Catch "ok bye then" and similar without matching "goodbye is a strong word".
		return FAREWELLS.stream().anyMatch(normalised::endsWith);
	}

	/** Why a conversation ended. Kept distinct for logging even though the player sees one line. */
	public enum EndReason {
		FAREWELL,
		DRIFTED,
		TIMED_OUT,
		VILLAGER_GONE,
		/** Hit the reply-count backstop with neither side having said goodbye. */
		TOO_LONG,
		/** Superseded by a new conversation; the player is told nothing. */
		REPLACED
	}
}
