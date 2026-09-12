package qa;

import com.remrin.client.gui.*;
import com.remrin.client.rules.CarpetRuleClient;
import com.remrin.rules.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.*;
import net.minecraft.server.players.NameAndId;
import java.util.*;
import org.lwjgl.glfw.GLFW;

public final class CarpetRulesQa implements ClientModInitializer {
   private int stage, ticks, checks;
   private CommandGUIScreen screen;
   private RuleData bool, number, org;
   private volatile boolean serverReady, deniedVerified;
   private volatile Throwable serverFailure;
   public void onInitializeClient() {
      ClientTickEvents.END_CLIENT_TICK.register(mc -> {
         try {
            if (++ticks > 2400) throw new AssertionError("timeout " + stage + " " + mc.gui.screen());
            if (mc.gui.overlay() != null) return;
            switch(stage) {
               case 0 -> { if (mc.gui.screen() instanceof TitleScreen && ticks > 35) {
                  mc.createWorldOpenFlows().openWorld("CarpetRulesQA", () -> {}); next();
               } }
               case 1 -> { if (mc.player != null && mc.getSingleplayerServer() != null) {
                  var server=mc.getSingleplayerServer();
                  server.execute(() -> { server.getWorldData().setAllowCommands(true); server.getPlayerList().op(new NameAndId(server.getPlayerList().getPlayers().getFirst().getGameProfile())); serverReady=true; }); next();
               } }
               case 2 -> { if(serverReady && CarpetRuleClient.canView()) {
                  screen=new CommandGUIScreen(); mc.gui.setScreen(screen); openTab(); next();
               } }
               case 3 -> { if(!CarpetRuleClient.busy() && !CarpetRuleClient.rules().isEmpty()) {
                  require(CarpetRuleClient.rules().size()>100,"live catalogue");
                  bool=find("flippinCactus"); number=find("pushLimit"); org=find("commandRuleSearch");
                  for (var level : net.minecraft.server.permissions.PermissionLevel.values()) {
                     var permissions=net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(level);
                     require(RuleAccess.allowed(permissions,false,false)==(level.id()>=1),"dedicated OP boundary "+level);
                     require(!RuleAccess.allowed(permissions,true,false),"singleplayer cheats off "+level);
                     require(RuleAccess.allowed(permissions,true,true)==(level.id()>=1),"singleplayer cheats and permission "+level);
                  }
                  require(number.command("set","14").equals("carpet pushLimit 14"),"numeric command");
                  require(org.command("setDefault",org.builtinDefault()).equals("carpet setDefault commandRuleSearch "+org.builtinDefault()),"Org native setDefault command");
                  try {bool.command("set","true\nstop");throw new AssertionError("control characters accepted");} catch(IllegalArgumentException expected){checks++;}
                  try {bool.command("set","1234");throw new AssertionError("strict value accepted");} catch(IllegalArgumentException expected){checks++;}
                  try {bool.command("stop","true");throw new AssertionError("unknown operation accepted");} catch(IllegalArgumentException expected){checks++;}
                  require(!org.categories().isEmpty(),"Org native categories");
                  require(CarpetRuleClient.rules().stream().anyMatch(r->r.options().size()>2),"nonboolean options");
                  var tab=tab();
                  ((GuiSearchBox)field(screen,"searchField")).setValue("flippinCactus");
                  click(tab.getButtons().getFirst()); next();
               } }
               case 4 -> { if(ticks>15) {
                  require(mc.gui.screen() instanceof CarpetRuleEditScreen,"rule editor opened"); shot(mc,"carpet-rule-editor");
                  clickByText(mc,"true"); clickByText(mc,"加入待执行");
                  require(CarpetRuleClient.pending.containsKey(bool.key()),"editor stages without execution");
                  CarpetRuleClient.stage(number,"set","14"); CarpetRuleClient.stage(org,"setDefault",org.builtinDefault());
                  require(CarpetRuleClient.pending.size()==3,"mixed batch staged");
                  ((GuiSearchBox)field(screen,"searchField")).setValue("");
                  mc.gui.setScreen(new CarpetRuleConfirmScreen(screen)); next();
               } }
               case 5 -> { if(ticks>15 && !CarpetRuleClient.busy()) {
                  shot(mc,"carpet-rule-confirm"); clickByText(mc,"确认执行 3 项"); next();
               } }
               case 6 -> { if(ticks>70 && !CarpetRuleClient.busy()) {
                  require(CarpetRuleClient.pending.isEmpty(),"batch acknowledged");
                  require(find("flippinCactus").value().equals("true"),"boolean applied");
                  require(find("pushLimit").value().equals("14"),"numeric applied");
                  require(find("commandRuleSearch").value().equals(org.builtinDefault()),"Org setDefault applied");
                  require(java.nio.file.Files.readString(java.nio.file.Path.of(mc.gameDirectory.toString(),"saves","CarpetRulesQA","carpetorgaddition","config.json")).contains("commandRuleSearch"),"Org persisted its own config");
                  openTab(); bounds(); shot(mc,"carpet-rules-wide");
                  CarpetRuleClient.stage(find("commandRuleSearch"),"removeDefault",org.builtinDefault());
                  CarpetRuleClient.stage(find("flippinCactus"),"set",bool.value());
                  CarpetRuleClient.stage(find("pushLimit"),"set",number.value());
                  CarpetRuleClient.apply(); next();
               } }
               case 7 -> { if(ticks>70 && !CarpetRuleClient.busy()) {
                  require(find("pushLimit").value().equals(number.value()),"numeric restored");
                  require(find("flippinCactus").value().equals(bool.value()),"boolean restored");
                  require(!java.nio.file.Files.readString(java.nio.file.Path.of(mc.gameDirectory.toString(),"saves","CarpetRulesQA","carpetorgaddition","config.json")).contains("commandRuleSearch"),"Org removeDefault removed saved entry");
                  GLFW.glfwSetWindowSize(mc.getWindow().handle(),960,720); next();
               } }
               case 8 -> { if(ticks>30) {
                  openTab(); bounds(); shot(mc,"carpet-rules-narrow");
                  var server=mc.getSingleplayerServer(); serverReady=false;
                  server.execute(()->{server.getWorldData().setAllowCommands(false);serverReady=true;}); next();
               } }
               case 9 -> { if(serverReady && ticks>20) {
                  require(!CarpetRuleClient.canView(),"cheats off hides rules even for owner");
                  var tabs=((TabNavigationBar)field(screen,"tabNavigationBar")).getTabs();
                  require(tabs.stream().noneMatch(t->t instanceof CarpetRulesTab),"tab removed after revocation");
                  var change=new RuleData.Change(bool.key(),bool.value(),"set",bool.value().equals("true")?"false":"true");
                  ClientPlayNetworking.send(new RulePayloads.Apply(100000,new com.google.gson.Gson().toJson(List.of(change)))); next();
               } }
               case 10 -> { if(ticks>25 && !deniedVerified) {
                  deniedVerified=true;
                  var server=mc.getSingleplayerServer(); serverReady=false;
                  server.execute(()->{
                     try { require(CarpetRuleCatalog.read().stream().filter(r->r.key().equals(bool.key())).findFirst().orElseThrow().value().equals(bool.value()),"forged unauthorized packet rejected"); }
                     catch(Throwable e){serverFailure=e;} finally {serverReady=true;}
                  });
               } else if(ticks>40 && serverReady) {
                  if(serverFailure!=null) throw new AssertionError("server verification failed",serverFailure);
                  System.out.println("CARPET_QA_COMPLETE checks="+checks+" rules="+CarpetRuleCatalog.read().size()); mc.stop(); next();
               } }
            }
         } catch(Throwable e){e.printStackTrace();System.out.println("CARPET_QA_FAILED stage="+stage);mc.stop();stage=99;}
      });
   }
   private void next(){stage++;ticks=0;System.out.println("CARPET_QA_STAGE "+stage);}
   private RuleData find(String name){return CarpetRuleClient.rules().stream().filter(r->r.name().equals(name)).findFirst().orElseThrow();}
   private CarpetRulesTab tab() throws Exception {return (CarpetRulesTab)((TabNavigationBar)field(screen,"tabNavigationBar")).getTabs().stream().filter(t->t instanceof CarpetRulesTab).findFirst().orElseThrow();}
   private void openTab() throws Exception {var bar=(TabNavigationBar)field(screen,"tabNavigationBar");bar.selectTab(bar.getTabs().indexOf(tab()),false);screen.tick();}
   private void click(Button b){var event=new MouseButtonEvent(b.getX()+b.getWidth()/2.0,b.getY()+b.getHeight()/2.0,new MouseButtonInfo(0,0));Minecraft.getInstance().gui.screen().mouseClicked(event,false);Minecraft.getInstance().gui.screen().mouseReleased(event);}
   private void clickByText(Minecraft mc,String label){click((Button)mc.gui.screen().children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals(label)).findFirst().orElseThrow());}
   private void bounds()throws Exception{var bs=tab().getButtons();for(var b:bs)require(b.getX()>=0&&b.getY()>=0&&b.getRight()<=screen.width&&b.getBottom()<=screen.height,"bounds");for(int i=0;i<bs.size();i++)for(int j=i+1;j<bs.size();j++){var a=bs.get(i);var b=bs.get(j);require(!(a.getX()<b.getRight()&&a.getRight()>b.getX()&&a.getY()<b.getBottom()&&a.getBottom()>b.getY()),"overlap");}}
   private void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name+".png",mc.gameRenderer.mainRenderTarget(),1,msg->{});}
   private void require(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
   private static Object field(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
}
