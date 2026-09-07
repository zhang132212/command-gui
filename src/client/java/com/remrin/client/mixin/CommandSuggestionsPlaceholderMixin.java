package com.remrin.client.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.context.SuggestionContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import com.remrin.client.gui.AddCommandScreen;
import com.remrin.client.gui.BaseCommandEditorScreen;
import com.remrin.client.gui.PlaceholderResolver;
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

@Mixin({CommandSuggestions.class})
public abstract class CommandSuggestionsPlaceholderMixin {
   private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{[a-zA-Z_]{1,16}\\}");
   private static final Style PLACEHOLDER_STYLE = Style.EMPTY.withColor(-11141291);
   @Shadow
   @Final
   private Screen screen;
   @Shadow
   @Final
   private EditBox input;

   @Inject(
      method = {"formatChat"},
      at = {@At("RETURN")},
      cancellable = true
   )
   private void tintPlaceholders(String text, int offset, CallbackInfoReturnable<FormattedCharSequence> cir) {
      FormattedCharSequence original = (FormattedCharSequence)cir.getReturnValue();
      if (original != null && this.isEditorScreen()) {
         cir.setReturnValue((FormattedCharSequence)sink -> {
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
   }

   private static String sanitizePlaceholders(String text) {
      Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
      StringBuilder builder = new StringBuilder();

      while (matcher.find()) {
         String token = matcher.group();

         String replacement = switch (token) {
            case "{number}", "{time}", "{coords}" -> "1".repeat(token.length());
            default -> "a".repeat(token.length());
         };
         matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
      }

      matcher.appendTail(builder);
      return builder.toString();
   }

   @ModifyArg(
      method = {"updateCommandInfo"},
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/brigadier/StringReader;<init>(Ljava/lang/String;)V"
      ),
      index = 0
   )
   private String sanitisedParseInput(String text) {
      if (this.isEditorScreen() && text.startsWith("/")) {
         return sanitizePlaceholders(text);
      }
      return text;
   }

   @Redirect(
      method = {"updateCommandInfo"},
      at = @At(
         value = "INVOKE",
         target = "Lcom/mojang/brigadier/CommandDispatcher;getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;"
      )
   )
   private CompletableFuture<Suggestions> mergePlaceholderSuggestions(
      CommandDispatcher<ClientSuggestionProvider> dispatcher, ParseResults<ClientSuggestionProvider> parse, int cursor
   ) {
      CompletableFuture<Suggestions> original = dispatcher.getCompletionSuggestions(parse, cursor);
      if (this.screen instanceof BaseCommandEditorScreen editor) {
         String var12 = this.input.getValue();
         int cursorPos = this.input.getCursorPosition();
         if (!var12.isEmpty() && var12.startsWith("/") && cursorPos >= 0 && cursorPos <= var12.length()) {
            int wordStart = var12.lastIndexOf(32, cursorPos - 1) + 1;
            String currentWord = var12.substring(wordStart, cursorPos);
            boolean argumentPosition = currentWord.isEmpty();
            boolean typingPlaceholder = currentWord.startsWith("{");
            if (!argumentPosition && !typingPlaceholder) {
               return original;
            } else {
               return !hasParseErrorAtOrAfter(parse, wordStart) ? original : original.thenApply(suggestions -> {
                  List<Suggestion> merged = new ArrayList<>(suggestions.getList());
                  StringRange range = StringRange.between(wordStart, cursorPos);
                  List<String> placeholders = List.of();

                  try {
                     SuggestionContext<ClientSuggestionProvider> context = parse.getContext().findSuggestionContext(cursorPos);
                     placeholders = PlaceholderResolver.suggestionsForNode(context.parent, parse.getContext().getNodes());
                  } catch (Exception ignored) {
                  }

                  for (String placeholder : placeholders) {
                     if (placeholder.startsWith(currentWord)) {
                        merged.add(new Suggestion(range, placeholder));
                     }
                  }

                  return new Suggestions(suggestions.getRange(), merged);
               });
            }
         } else {
            return original;
         }
      } else {
         return original;
      }
   }

   private static boolean hasParseErrorAtOrAfter(ParseResults<ClientSuggestionProvider> parse, int wordStart) {
      if (parse.getExceptions().isEmpty()) {
         return false;
      } else {
         for (CommandSyntaxException exception : parse.getExceptions().values()) {
            if (exception.getCursor() >= wordStart) {
               return true;
            }
         }

         return false;
      }
   }

   private boolean isEditorScreen() {
      return this.screen instanceof BaseCommandEditorScreen || this.screen instanceof AddCommandScreen || this.screen instanceof StepEditorScreen;
   }

   private static void emitSegment(FormattedCharSink sink, StringBuilder segment, Style style) {
      if (segment.length() != 0) {
         Style baseStyle = style != null ? style : Style.EMPTY;
         String text = segment.toString();
         int pos = 0;

         for (Matcher matcher = PLACEHOLDER_PATTERN.matcher(text); matcher.find(); pos = matcher.end()) {
            if (matcher.start() > pos) {
               FormattedCharSequence.forward(text.substring(pos, matcher.start()), baseStyle).accept(sink);
            }

            FormattedCharSequence.forward(matcher.group(), PLACEHOLDER_STYLE).accept(sink);
         }

         if (pos < text.length()) {
            FormattedCharSequence.forward(text.substring(pos), baseStyle).accept(sink);
         }
      }
   }
}
