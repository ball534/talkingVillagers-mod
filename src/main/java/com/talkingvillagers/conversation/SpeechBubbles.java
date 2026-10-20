package com.talkingvillagers.conversation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.talkingvillagers.Tuning;
import com.talkingvillagers.data.Attachments;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Floating speech above an entity's head, built out of vanilla Text Display entities.
 *
 * <p>This is how a server-side-only mod puts text in the world: the server spawns a real
 * vanilla entity, so an unmodded client renders it with no cooperation from us. The cost is
 * that we own its whole lifecycle — a bubble has to be followed along behind its speaker,
 * removed when it expires, and, crucially, removed if its speaker dies or unloads, or it is
 * left orphaned in the world forever.
 */
public final class SpeechBubbles {
	/** One bubble per speaker; a new line replaces whatever they were saying. */
	private final Map<UUID, Bubble> bubbles = new HashMap<>();

	/**
	 * Shows {@code text} above {@code speaker} for {@code durationTicks}.
	 *
	 * <p>The line carries no speaker label: it is already floating over the speaker's head, which
	 * says who is talking better than a name prefix does, and the name is on the villager anyway.
	 *
	 * <p>Safe to call for a speaker who already has a bubble: the old one is discarded, so a
	 * villager in a fast exchange does not accumulate a stack of overlapping text.
	 */
	public void show(LivingEntity speaker, String text, int durationTicks) {
		spawn(speaker, null, Component.literal(text).withStyle(ChatFormatting.WHITE),
			140, text, durationTicks, false, null);
	}

	/**
	 * Shows a line of narration above {@code anchor} — used for villager-to-villager
	 * conversations, which are presented as a summary of what is happening ("Mira and Tobias
	 * are bickering") rather than as quoted speech, and so carry no speaker prefix.
	 */
	public void showNarration(LivingEntity anchor, String text, int durationTicks) {
		spawn(anchor, null, narrationText(text), 110, text, durationTicks, false, null);
	}

	/**
	 * Shows an animated "{@code <name>} is thinking..." above {@code speaker}, for as long as
	 * the speaker is composing their reply.
	 *
	 * <p>The dots flick while the model works. There is deliberately no time limit — a slow
	 * model is exactly when a player most needs to see that the villager heard them — so the
	 * indicator stays up until the answer's own speech bubble replaces it, {@link #remove}
	 * clears it, or the speaker dies or unloads, which the regular sweep already handles.
	 */
	public void showThinking(LivingEntity speaker, String name) {
		spawn(speaker, null, thinkingText(name, 0), 110, name + " is thinking…", 0, true, name);
	}

	private static Component narrationText(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	}

	/** The thinking line for a given dot phase — 0, 1 or 2 dots after the words. */
	private static Component thinkingText(String name, int phase) {
		String dots = ".".repeat(phase + 1);
		return Component.literal(name + " is thinking" + dots)
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	}

	/**
	 * Shows narration floating midway between two participants, and follows both of them.
	 *
	 * <p>This is how a villager-to-villager conversation is presented: the line describes the
	 * pair rather than either one of them, so hanging it over just one villager's head reads as
	 * that villager talking to themselves.
	 */
	public void showBetween(LivingEntity one, LivingEntity two, String text, int durationTicks) {
		// Clear both, so a pair cannot end up with a shared bubble and a leftover solo one.
		remove(two.getUUID());
		spawn(one, two, narrationText(text), 110, text, durationTicks, false, null);
	}

	/**
	 * Spawns the display entity and registers it.
	 *
	 * @param partner            the second anchor, or null for a bubble over one head
	 * @param backgroundAlpha    0-255 alpha of the text background
	 * @param plainText          the unstyled text, checked for emptiness
	 * @param thinking           true for a thinking indicator: no expiry, animated dots instead
	 * @param thinkerName        the speaker's name, read only while thinking
	 */
	private void spawn(
		LivingEntity anchor, @Nullable LivingEntity partner, Component content, int backgroundAlpha,
		String plainText, int durationTicks, boolean thinking, @Nullable String thinkerName
	) {
		if (!(anchor.level() instanceof ServerLevel level) || plainText.isBlank()) {
			return;
		}

		remove(anchor.getUUID());

		Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		if (display == null) {
			return;
		}

		// A thinking indicator has no deadline of its own — it lasts exactly as long as the
		// wait it describes, however long the model takes.
		Bubble bubble = new Bubble(display, anchor, partner,
			thinking ? Long.MAX_VALUE : level.getGameTime() + durationTicks, thinking, thinkerName);
		Vec3 position = bubble.anchorPosition();
		display.snapTo(position.x, position.y, position.z, 0.0F, 0.0F);
		display.setText(content);
		// CENTER keeps the text facing whoever is reading it, from any angle.
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setLineWidth(200);
		display.setBackgroundColor(ARGB.color(backgroundAlpha, 0, 0, 0));
		// 0.5 view range is about 32 blocks: far enough to overhear, near enough that a busy
		// village does not fill every client's screen with distant chatter.
		display.setViewRange(0.5F);

		if (!level.addFreshEntity(display)) {
			return;
		}
		// Marked only after spawning: the entity-load event fires inside addFreshEntity, and the
		// leftover-cleanup handler there would otherwise delete this bubble at once.
		display.setAttached(Attachments.SPEECH_BUBBLE, Boolean.TRUE);

		this.bubbles.put(anchor.getUUID(), bubble);
	}

	/**
	 * Moves live bubbles to follow their speakers and clears out expired ones.
	 *
	 * <p>Called once per server tick. Bubbles are keyed by speaker, so this is proportional to
	 * the number of entities currently talking rather than to the population.
	 */
	public void tick() {
		if (this.bubbles.isEmpty()) {
			return;
		}

		Iterator<Map.Entry<UUID, Bubble>> iterator = this.bubbles.entrySet().iterator();
		while (iterator.hasNext()) {
			Bubble bubble = iterator.next().getValue();

			if (!bubble.anchorsAlive() || !(bubble.speaker.level() instanceof ServerLevel level)) {
				bubble.display.discard();
				iterator.remove();
				continue;
			}
			if (level.getGameTime() >= bubble.expiresAtGameTime || bubble.display.isRemoved()) {
				bubble.display.discard();
				iterator.remove();
				continue;
			}

			// A thinking indicator's text is a clock, not a line: the dots advance here rather
			// than being rewritten by the caller, so the flick costs nothing when nobody waits.
			if (bubble.thinking && level.getGameTime() % Tuning.Conversation.THINKING_FLICK_INTERVAL_TICKS == 0) {
				long step = level.getGameTime() / Tuning.Conversation.THINKING_FLICK_INTERVAL_TICKS;
				bubble.display.setText(thinkingText(bubble.thinkerName(), (int) (step % 3)));
			}

			Vec3 anchor = bubble.anchorPosition();
			bubble.display.snapTo(anchor.x, anchor.y, anchor.z, 0.0F, 0.0F);
		}
	}

	/** Removes a speaker's bubble immediately, if they have one. */
	public void remove(UUID speakerId) {
		Bubble existing = this.bubbles.remove(speakerId);
		if (existing != null) {
			existing.display.discard();
		}
	}

	/**
	 * Discards every bubble. Used on server stop so no display entity is left behind in the
	 * save.
	 */
	public void clear() {
		List<Bubble> all = new ArrayList<>(this.bubbles.values());
		this.bubbles.clear();
		for (Bubble bubble : all) {
			bubble.display.discard();
		}
	}

	private static final class Bubble {
		private final Display.TextDisplay display;
		private final LivingEntity speaker;
		/**
		 * The other participant, when this bubble belongs to a conversation rather than to one
		 * speaker. Present means the text floats midway between the two.
		 */
		private final @Nullable LivingEntity partner;
		private final long expiresAtGameTime;
		/** True for a thinking indicator: no expiry, and its dots advance every few ticks. */
		private final boolean thinking;
		/** The speaker's name on a thinking indicator; null on an ordinary bubble. */
		private final @Nullable String thinkerName;

		private Bubble(
			Display.TextDisplay display, LivingEntity speaker, @Nullable LivingEntity partner,
			long expiresAtGameTime, boolean thinking, @Nullable String thinkerName
		) {
			this.display = display;
			this.speaker = speaker;
			this.partner = partner;
			this.expiresAtGameTime = expiresAtGameTime;
			this.thinking = thinking;
			this.thinkerName = thinkerName;
		}

		/** The name shown on a thinking indicator's line. */
		private String thinkerName() {
			return this.thinkerName == null ? "" : this.thinkerName;
		}

		/** Whether both participants are still around to anchor this bubble. */
		private boolean anchorsAlive() {
			if (!this.speaker.isAlive() || this.speaker.isRemoved()) {
				return false;
			}
			return this.partner == null || (this.partner.isAlive() && !this.partner.isRemoved());
		}

		/** Where the text should sit: over one head, or midway between two. */
		private Vec3 anchorPosition() {
			double height = Tuning.Conversation.OVERHEAD_HEIGHT_OFFSET;
			if (this.partner == null) {
				return new Vec3(this.speaker.getX(), this.speaker.getY() + height, this.speaker.getZ());
			}
			// Midpoint, and above whichever of the two is standing higher, so the text never
			// sinks into a villager who is a block further up a slope.
			return new Vec3(
				(this.speaker.getX() + this.partner.getX()) / 2.0,
				Math.max(this.speaker.getY(), this.partner.getY()) + height,
				(this.speaker.getZ() + this.partner.getZ()) / 2.0);
		}
	}
}
