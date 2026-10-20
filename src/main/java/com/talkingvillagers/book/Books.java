package com.talkingvillagers.book;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * Turning a plain book into a written one, and the pieces every such book is built from.
 *
 * <p>A plain {@code minecraft:book} is the right item for this precisely because vanilla gives it
 * no right-click behaviour of its own: nothing to suppress, and no screen the client opens on its
 * own initiative that would have to be talked back out of. The mod fills the book and hands it
 * over, and reading it is ordinary vanilla behaviour from then on.
 *
 * <p>The books are snapshots. They are signed, they say when they were taken, and they do not
 * update — which is the honest thing for a written record, and avoids pretending the mod can
 * rewrite an item sitting in a chest somewhere.
 */
public final class Books {
	private Books() {
	}

	/** Whether this stack is a plain book, the input for every record the mod writes. */
	public static boolean isBlankBook(ItemStack stack) {
		return stack.is(Items.BOOK);
	}

	/**
	 * Whether this stack is an already-written book — treated the same as a blank one, so
	 * sneak-clicking with a record the mod already wrote simply rewrites it, the same gesture a
	 * player would use to re-sign any other written book.
	 */
	public static boolean isWrittenBook(ItemStack stack) {
		return stack.is(Items.WRITTEN_BOOK);
	}

	/**
	 * Spends one book from the player's hand and gives them the written one.
	 *
	 * <p>A stack of several books is decremented and the result goes to the inventory, so a player
	 * carrying a stack does not lose the rest of it. The book is handed over closed: vanilla's own
	 * book-and-quill does not force its screen open either, and forcing it here is what caused a
	 * sneak-click to always pop the reading screen even when all the player wanted was the book.
	 */
	public static void exchange(ServerPlayer player, InteractionHand hand, ItemStack written) {
		ItemStack held = player.getItemInHand(hand);
		held.shrink(1);

		if (held.isEmpty()) {
			player.setItemInHand(hand, written);
		} else {
			// placeItemBackInInventory drops the item at the player's feet when there is no room,
			// so the book is never silently destroyed by a full inventory. SERVER_ONLY because the
			// placement is driven from the server, as with vanilla's own MerchantMenu give-backs.
			player.getInventory().placeItemBackInInventory(written, Prediction.SERVER_ONLY);
		}
		player.containerMenu.broadcastChanges();
	}

	/**
	 * Builds a signed book.
	 *
	 * @param author who took the record down, shown by vanilla as "by ..."
	 */
	public static ItemStack write(String title, String author, List<Component> pages) {
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
			Filterable.passThrough(trimTitle(title)),
			author,
			// Generation 0 is an original, so a player who wants a copy can still make one.
			0,
			pages.stream().map(Filterable::passThrough).toList(),
			// Left unresolved for vanilla to resolve when the book is first opened, exactly as it
			// does for a player-signed book. There is nothing here that needs resolving, and
			// claiming otherwise would only invite the client to trust text it should not.
			false));
		return stack;
	}

	private static String trimTitle(String title) {
		return title.length() > WrittenBookContent.TITLE_MAX_LENGTH
			? title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH)
			: title;
	}

	/**
	 * A clickable line that asks the server to carry out an action when tapped in the book.
	 *
	 * <p>This is the mod's only interactive control. A written book page is an ordinary chat
	 * component, so it carries click events, and that makes a book the closest thing to a custom
	 * screen available without shipping anything to the client.
	 *
	 * <p>A {@code custom} click event rather than a command: the tap comes back as a packet naming
	 * the action, so the control needs no command for a player to find, read off the page, or type
	 * themselves. See {@link com.talkingvillagers.ui.CustomClicks} for what the ids mean and
	 * {@link com.talkingvillagers.ui.CustomClicks#settlement} for building the payload.
	 */
	public static Component action(
		String label, Identifier action, Optional<Tag> payload, String tooltip
	) {
		return Component.literal(label).withStyle(style -> style
			.withColor(ChatFormatting.DARK_AQUA)
			.withUnderlined(Boolean.TRUE)
			.withClickEvent(new ClickEvent.Custom(action, payload))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip))));
	}

	/**
	 * Takes a control out of every copy of a book the player is carrying.
	 *
	 * <p>The books are snapshots and do not otherwise update, which is the honest thing for a
	 * written record — but a control is a promise that something can still be done, and once it
	 * cannot, leaving it drawn invites a player to tap it and watch nothing happen. So this is the
	 * one edit the mod makes to a book already in someone's hands, and it only ever removes.
	 *
	 * <p>Scoped to the player who used the control. A copy in a chest, or in someone else's
	 * inventory, keeps its stale button — the action re-checks standing anyway and simply declines.
	 */
	public static void removeControl(ServerPlayer player, ClickEvent.Custom control) {
		var inventory = player.getInventory();
		boolean changed = false;

		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!isWrittenBook(stack)) {
				continue;
			}
			WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
			if (content == null) {
				continue;
			}

			List<Filterable<Component>> stripped = new ArrayList<>();
			boolean bookChanged = false;
			for (Filterable<Component> page : content.pages()) {
				Component without = withoutControl(page.raw(), control);
				if (without == page.raw()) {
					stripped.add(page);
					continue;
				}
				bookChanged = true;
				stripped.add(new Filterable<>(without,
					page.filtered().map(filtered -> withoutControl(filtered, control))));
			}
			if (bookChanged) {
				stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content.withReplacedPages(stripped));
				changed = true;
			}
		}

		if (changed) {
			player.containerMenu.broadcastChanges();
		}
	}

	/**
	 * A page with the control gone, or the page itself when it was not on it.
	 *
	 * <p>Controls are appended to the top level of a page as {@code "\n"} followed by the clickable
	 * line, so both siblings are dropped together — leaving the newline behind would put a blank
	 * line at the end of the page where the button used to be.
	 */
	private static Component withoutControl(Component page, ClickEvent.Custom control) {
		List<Component> siblings = page.getSiblings();
		int found = -1;
		for (int index = 0; index < siblings.size(); index++) {
			if (control.equals(siblings.get(index).getStyle().getClickEvent())) {
				found = index;
				break;
			}
		}
		if (found < 0) {
			return page;
		}

		// The newline that spaced the control off from the text above it.
		int from = found > 0 && isNewline(siblings.get(found - 1)) ? found - 1 : found;

		MutableComponent rebuilt = MutableComponent.create(page.getContents()).setStyle(page.getStyle());
		for (int index = 0; index < siblings.size(); index++) {
			if (index < from || index > found) {
				rebuilt.append(siblings.get(index));
			}
		}
		return rebuilt;
	}

	private static boolean isNewline(Component sibling) {
		return sibling.getSiblings().isEmpty() && sibling.getString().equals("\n");
	}

	/** A page heading, followed by a blank line. */
	public static Component heading(String text) {
		return Component.literal(text).withStyle(ChatFormatting.BOLD)
			.append(Component.literal("\n\n").withStyle(style -> style.withBold(Boolean.FALSE)));
	}

	/**
	 * Lays a list of lines out under a heading, across as many pages as they need.
	 *
	 * @return one page per group of lines, each headed; empty if there are no lines
	 */
	public static List<Component> section(String heading, List<String> lines) {
		List<Component> pages = new ArrayList<>();
		for (List<String> group : Pagination.group(lines)) {
			MutableComponent page = Component.empty().append(heading(heading));
			for (String line : group) {
				page.append(line + "\n");
			}
			pages.add(page);
		}
		return pages;
	}
}
