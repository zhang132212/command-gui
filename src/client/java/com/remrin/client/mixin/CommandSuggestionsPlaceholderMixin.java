package com.remrin.client.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.remrin.client.gui.BaseCommandEditorScreen;
import com.remrin.client.gui.StepEditorScreen;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Integrates the mod's {@code {placeholder}} system into the vanilla command-suggestion machinery,
 * but ONLY inside the mod's own command editor screens ({@link BaseCommandEditorScreen} and
 * {@link StepEditorScreen}).
 * <p>
 * Two behaviours are added, gated by the owning screen type so the vanilla chat box / command block
 * UIs are never affected:
 * <ul>
 *   <li><b>Placeholder tinting</b>: the vanilla syntax-highlight formatter ({@code formatChat})
 *       marks unparsed text red, which would flag {@code {player}} etc. as errors. The returned
 *       {@link FormattedCharSequence} is wrapped so any {@code {…}} token is recoloured green while
 *       every other segment keeps its original style (including genuine error red).</li>
 *   <li><b>Placeholder tab-completion</b>: while the user is typing a command in an editor's field
 *       (which starts with {@code /}), the vanilla completion future gets the mod's placeholder
 *       tokens merged in whenever the current word is empty (a fresh argument position) or starts
 *       with {@code {}. The merge happens at the {@code pendingSuggestions} level, so the
 *       placeholder entries still show up even when the vanilla dispatcher produces no suggestions
 *       of its own for that position.</li>
 * </ul>
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsPlaceholderMixin {

  /** Matches a single {@code {token}} placeholder, e.g. {@code {player}} or {@code {coords}}. */
  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z_]{1,16}\\}");
  /** Green style used to render placeholder tokens as valid. */
  private static final Style PLACEHOLDER_STYLE = Style.EMPTY.withColor(0xFF55FF55);

  @Shadow
  @Final
  private Screen screen;

  @Shadow
  @Final
  private EditBox input;

  /**
   * Recolours placeholder tokens inside the vanilla syntax-highlighted text. Returns the original
   * sequence unchanged for every screen other than the mod's editors, so the chat box and command
   * block UIs keep their exact vanilla behaviour (placeholders stay red errors there).
   */
  @Inject(method = "formatChat", at = @At("RETURN"), cancellable = true)
  private void tintPlaceholders(String text, int offset,
      CallbackInfoReturnable<FormattedCharSequence> cir) {
    FormattedCharSequence original = cir.getReturnValue();
    if (original == null || !isEditorScreen()) {
      return;
    }
    cir.setReturnValue(sink -> {
      StringBuilder segment = new StringBuilder();
      Style[] segmentStyle = new Style[1];
      FormattedCharSink collector = (index, style, codePoint) -> {
        if (segmentStyle[0] != null && !segmentStyle[0].equals(style)) {
          emitSegment(sink, segment, segmentStyle[0]);
          segment.setLength(0);
        }
        segmentStyle[0] = style;
        segment.appendCodePoint(codePoint);
        return true;
      };
      boolean keepGoing = original.accept(collector);
      emitSegment(sink, segment, segmentStyle[0]);
      return keepGoing;
    });
  }

  /**
   * Replaces every {@code {…}} placeholder with a same-length run of valid word characters before
   * the command is parsed. Carpet's {@code /player <name>} argument is
   * {@code StringArgumentType.word()} (only {@code [A-Za-z0-9_]+}), so a raw {@code {player_all}}
   * breaks the parse and kills every later suggestion / highlight. Replacing it with the same
   * number of {@code a}s keeps the string length identical, so all position ranges stay aligned
   * with the real text while the command tree can be walked past the placeholder.
   */
  private static String sanitizePlaceholders(String text) {
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
    StringBuilder builder = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group();
      String replacement = switch (token) {
        case "{number}", "{time}", "{coords}" -> "1".repeat(token.length());
        default -> "a".repeat(token.length()); // {player} / {player_all} / {player_fake} / {name}
      };
      matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(builder);
    return builder.toString();
  }

  /**
   * Feeds the sanitised text (placeholders replaced by same-length word runs) into the command
   * parser inside {@code updateCommandInfo}, so the parse result (and therefore both the
   * syntax highlighting and the later-argument suggestions) treats the placeholders as valid
   * tokens. Only applies inside the custom command editor; every other screen parses the raw text.
   */
  @ModifyArg(
      method = "updateCommandInfo",
      at = @At(
          value = "INVOKE",
          target = "Lcom/mojang/brigadier/StringReader;<init>(Ljava/lang/String;)V"),
      index = 0)
  private String sanitisedParseInput(String text) {
    if (isEditorScreen() && text.startsWith("/")) {
      return sanitizePlaceholders(text);
    }
    return text;
  }

  /**
   * Merges the mod's placeholder tokens into the vanilla completion future, but only inside the
   * custom command editor ({@link BaseCommandEditorScreen}) while typing a {@code /} command whose
   * current word is empty (just after a space — an argument position) or starts with {@code {}.
   * <p>
   * Merging at this level (rather than only appending to the rendered list) guarantees the
   * suggestions popup opens even when the vanilla dispatcher returns no suggestions for the current
   * position — otherwise {@code showSuggestions} would bail out early on an empty result.
   * <p>
   * Because the parse input is sanitised (same-length placeholders) the vanilla future already
   * contains the NEXT argument's suggestions (e.g. {@code spawn}/{@code use}/{@code kill} after
   * {@code /player <name> }), and its ranges are aligned with the real text.
   */
  @Redirect(
      method = "updateCommandInfo",
      at = @At(
          value = "INVOKE",
          target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions"
              + "(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;"))
  private CompletableFuture<Suggestions> mergePlaceholderSuggestions(
      CommandDispatcher<ClientSuggestionProvider> dispatcher,
      ParseResults<ClientSuggestionProvider> parse, int cursor) {
    CompletableFuture<Suggestions> original = dispatcher.getCompletionSuggestions(parse, cursor);
    if (!(screen instanceof BaseCommandEditorScreen editor)) {
      return original;
    }
    String text = input.getValue();
    int cursorPos = input.getCursorPosition();
    if (text.isEmpty() || !text.startsWith("/") || cursorPos < 0 || cursorPos > text.length()) {
      return original;
    }
    int wordStart = text.lastIndexOf(' ', cursorPos - 1) + 1;
    String currentWord = text.substring(wordStart, cursorPos);
    boolean argumentPosition = currentWord.isEmpty();
    boolean typingPlaceholder = currentWord.startsWith("{");
    if (!argumentPosition && !typingPlaceholder) {
      return original;
    }
    // Placeholder suggestions only make sense while the command still expects input AT the cursor.
    // A fully parsed command has no exceptions (finished — no suggestions), and an exception that
    // occurred BEFORE the current word means a token was typed that is simply wrong (e.g. the bogus
    // action in "/player {player_all} attack after ") — appending placeholders there would pile up
    // endless {player_all} copies. Only when the parse error position is at or past the current
    // word (i.e. the dispatcher is genuinely waiting for a value) do we offer placeholders.
    if (!hasParseErrorAtOrAfter(parse, wordStart)) {
      return original;
    }
    return original.thenApply(suggestions -> {
      List<Suggestion> merged = new ArrayList<>(suggestions.getList());
      StringRange range = StringRange.between(wordStart, cursorPos);
      for (String placeholder : editor.PLACEHOLDERS) {
        if (placeholder.startsWith(currentWord)) {
          merged.add(new Suggestion(range, placeholder));
        }
      }
      return new Suggestions(suggestions.getRange(), merged);
    });
  }

  /**
   * Whether the (sanitised) parse failed at or after the current word's start. A clean parse means
   * the command is complete; an error strictly before the word means a wrong token was typed
   * (offering placeholders would keep stacking them). An error at/after the word means the
   * dispatcher is waiting for input right where the cursor is.
   */
  private static boolean hasParseErrorAtOrAfter(ParseResults<ClientSuggestionProvider> parse,
      int wordStart) {
    if (parse.getExceptions().isEmpty()) {
      return false;
    }
    for (CommandSyntaxException exception : parse.getExceptions().values()) {
      if (exception.getCursor() >= wordStart) {
        return true;
      }
    }
    return false;
  }

  private boolean isEditorScreen() {
    return screen instanceof BaseCommandEditorScreen || screen instanceof StepEditorScreen;
  }

  /**
   * Forwards one same-styled text segment to the sink, splitting out any placeholder tokens so they
   * are rendered green while the rest keeps the segment's original style.
   */
  private static void emitSegment(FormattedCharSink sink, StringBuilder segment, Style style) {
    if (segment.length() == 0) {
      return;
    }
    Style baseStyle = style != null ? style : Style.EMPTY;
    String text = segment.toString();
    int pos = 0;
    Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
    while (matcher.find()) {
      if (matcher.start() > pos) {
        FormattedCharSequence.forward(text.substring(pos, matcher.start()), baseStyle)
            .accept(sink);
      }
      FormattedCharSequence.forward(matcher.group(), PLACEHOLDER_STYLE).accept(sink);
      pos = matcher.end();
    }
    if (pos < text.length()) {
      FormattedCharSequence.forward(text.substring(pos), baseStyle).accept(sink);
    }
  }
}
