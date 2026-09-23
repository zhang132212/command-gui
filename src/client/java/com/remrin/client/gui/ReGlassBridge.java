package com.remrin.client.gui;

import com.remrin.CommandGUI;
import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Optional ReGlass-ev 26.2 API. Resolve once; never load it on a dedicated server. */
final class ReGlassBridge {
   private static Api api;
   private static boolean resolved;

   private ReGlassBridge() {}

   private static Api api() {
      if (GuiTuning.getInt("GuiTheme.REGLASS_ENABLED", 1) == 0) return null;
      if (!resolved) {
         resolved = true;
         if (FabricLoader.getInstance().isModLoaded("reglass")) {
            try {
               api = new Api();
               CommandGUI.LOGGER.info("[GUI] ReGlass liquid glass renderer enabled");
            } catch (ReflectiveOperationException | LinkageError exception) {
               disable(exception);
            }
         }
      }
      return api;
   }

   private static void disable(Throwable exception) {
      api = null;
      CommandGUI.LOGGER.warn("[GUI] ReGlass API unavailable; using built-in glass", exception);
   }

   /** Claim the world's blur before vanilla adds its full-screen blur/darkening. */
   static boolean prepare(GuiGraphicsExtractor g) {
      Api a = api();
      if (a == null) return false;
      try {
         a.blur.invoke(a.uniforms, g);
         return true;
      } catch (ReflectiveOperationException | LinkageError exception) {
         disable(exception);
         return false;
      }
   }

   static boolean draw(GuiGraphicsExtractor g, int x, int y, int w, int h,
                       int radius, int color, int layer, boolean hovered, boolean selected) {
      Api a = api();
      if (a == null || w <= 0 || h <= 0) return false;
      try {
         // Upstream has a shared 64-widget UBO. Leave room for vanilla tooltips/HUD.
         if ((int)a.count.invoke(a.uniforms) >= a.widgetBudget) return false;
         Object style = a.styleCreate.invoke(null);
         a.tint.invoke(style, color & 0xFFFFFF, (color >>> 24) / 255.0F);
         a.layer.invoke(style, layer);
         a.smoothing.invoke(style, 0.003F);
         a.blurRadius.invoke(style, Math.max(1, Math.min(32, GuiTuning.getInt("GuiTheme.GLASS_BLUR", 8))));
         a.shadow.invoke(style, 5.0F, 0.12F, 0.0F, 2.0F);
         Object builder = a.create.invoke(null, g);
         a.dimensions.invoke(builder, x, y, w, h);
         a.radius.invoke(builder, (float)radius);
         a.style.invoke(builder, style);
         a.hover.invoke(builder, hovered ? 1.0F : 0.0F);
         a.selected.invoke(builder, selected ? 1.0F : 0.0F);
         a.render.invoke(builder);
         return true;
      } catch (ReflectiveOperationException | LinkageError exception) {
         disable(exception);
         return false;
      }
   }

   private static final class Api {
      final Object uniforms;
      final int widgetBudget;
      final Method blur, count, create, dimensions, radius, style, hover, selected, render;
      final Method styleCreate, tint, layer, smoothing, blurRadius, shadow;

      Api() throws ReflectiveOperationException {
         Class<?> apiClass = Class.forName("restudio.reglass.client.api.ReGlassApi");
         Class<?> builder = Class.forName("restudio.reglass.client.api.ReGlassApi$Builder");
         Class<?> styleClass = Class.forName("restudio.reglass.client.api.WidgetStyle");
         Class<?> uniformClass = Class.forName("restudio.reglass.client.LiquidGlassUniforms");
         uniforms = uniformClass.getMethod("get").invoke(null);
         widgetBudget = Math.max(0, uniformClass.getField("MAX_WIDGETS").getInt(null) - 8);
         blur = uniformClass.getMethod("tryApplyBlur", GuiGraphicsExtractor.class);
         count = uniformClass.getMethod("getCount");
         create = apiClass.getMethod("create", GuiGraphicsExtractor.class);
         dimensions = builder.getMethod("dimensions", int.class, int.class, int.class, int.class);
         radius = builder.getMethod("cornerRadius", float.class);
         style = builder.getMethod("style", styleClass);
         hover = builder.getMethod("hover", float.class);
         selected = builder.getMethod("selected", float.class);
         render = builder.getMethod("render");
         styleCreate = styleClass.getMethod("create");
         tint = styleClass.getMethod("tint", int.class, float.class);
         layer = styleClass.getMethod("layer", int.class);
         smoothing = styleClass.getMethod("smoothing", float.class);
         blurRadius = styleClass.getMethod("blurRadius", int.class);
         shadow = styleClass.getMethod("shadow", float.class, float.class, float.class, float.class);
      }
   }
}
